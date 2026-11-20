package io.github.vdassios.grailsls.index;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Follows changes on disk for a client that cannot watch files for the server (Neovim on Linux offers no
 * {@code workspace/didChangeWatchedFiles} registration): a {@link WatchService} over every directory below the source
 * trees, build directories skipped, and over the directories holding build files (not below them). A directory created
 * below a tree is watched as it appears.
 *
 * <p>Events can be lost: on an overflow, or when a directory cannot be registered (Linux's inotify watch limit), it says
 * so in the log (a failed registration once) and asks for a {@link Events#rescan}. After a failed registration {@link #failed()} stays true, and the
 * server also rescans on each save.
 */
public final class SourceWatcher implements AutoCloseable {
  /** Where the changes go. Called on the watcher's thread. */
  public interface Events {
    /** A file or directory was created, modified or deleted. */
    void changed(Path path);

    /** Changes were lost: read everything again. */
    void rescan();
  }

  /** Registers one directory for creates, deletes and modifications. */
  interface Registrar {
    WatchKey register(Path dir) throws IOException;
  }

  private final WatchService service;
  private final Registrar registrar;
  private final Set<Path> skip;
  private final Events events;
  private final Consumer<String> log;
  private final Map<WatchKey, Watched> keys = new ConcurrentHashMap<>();
  /** Directories that could not be registered so far. */
  private final AtomicInteger failures = new AtomicInteger();
  private volatile boolean closed;

  /**
   * Starts watching.
   *
   * @param trees      directories watched with every directory below them (source roots, {@code grails-app})
   * @param dirs       directories watched without those below them (where the build files are)
   * @param skip       directories never watched (the modules' build directories)
   * @param log        where lost events and registration failures are reported
   */
  public static SourceWatcher start(List<Path> trees, List<Path> dirs, Set<Path> skip, Events events, Consumer<String> log) throws IOException {
    WatchService service = FileSystems.getDefault().newWatchService();
    return new SourceWatcher(service, dir -> dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY), trees, dirs, skip, events, log);
  }

  SourceWatcher(WatchService service, Registrar registrar, List<Path> trees, List<Path> dirs, Set<Path> skip, Events events, Consumer<String> log) {
    this.service = service;
    this.registrar = registrar;
    this.skip = Set.copyOf(skip);
    this.events = events;
    this.log = log;
    for (Path dir : dirs) {
      if (Files.isDirectory(dir)) {
        register(dir, false);
      }
    }
    for (Path tree : outermost(trees)) {
      registerTree(tree); // after the flat ones: a directory in both is watched with those below it
    }
    if (failed()) {
      deliver(events::rescan);
    }
    Thread thread = new Thread(this::run, "grails-watcher");
    thread.setDaemon(true);
    thread.start();
  }

  /** Whether a directory could not be registered: changes below it are seen only by a rescan. */
  public boolean failed() {
    return failures.get() > 0;
  }

  /** Directories watched now. */
  int watched() {
    return keys.size();
  }

  /**
   * Stops watching. Each key is cancelled before the service is closed: the JDK's polling service (macOS) deadlocks when
   * it is closed while a key finds its directory deleted, because its close locks the key table and then each key, and
   * that key locks itself and then the table. {@link WatchKey#cancel} holds one lock at a time.
   */
  @Override
  public void close() {
    closed = true;
    keys.keySet().forEach(WatchKey::cancel);
    try {
      service.close();
    } catch (IOException e) {
      // nothing left to release
    }
  }

  /** The trees not below another one, each once. */
  private static List<Path> outermost(List<Path> trees) {
    List<Path> result = new ArrayList<>();
    for (Path tree : trees) {
      if (trees.stream().noneMatch(other -> !other.equals(tree) && tree.startsWith(other)) && !result.contains(tree)) {
        result.add(tree);
      }
    }
    return result;
  }

  private void registerTree(Path tree) {
    if (!Files.isDirectory(tree)) {
      return;
    }
    try {
      Files.walkFileTree(tree, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
          if (skip.contains(dir)) {
            return FileVisitResult.SKIP_SUBTREE;
          }
          register(dir, true);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      // gone while walking: its parent's delete event follows
    }
  }

  private void register(Path dir, boolean tree) {
    try {
      WatchKey key = registrar.register(dir);
      keys.put(key, new Watched(dir, tree));
      if (closed) {
        key.cancel(); // registered by the watcher's thread while closing
      }
    } catch (IOException | RuntimeException e) {
      if (!Files.isDirectory(dir)) {
        return; // gone already: its parent's delete event follows
      }
      if (failures.getAndIncrement() == 0) {
        log.accept("cannot watch " + dir + " (" + e + "): changes made outside the editor are read on save and on grails/reindex");
      }
    }
  }

  private void run() {
    while (true) {
      WatchKey key;
      try {
        key = service.take();
      } catch (ClosedWatchServiceException | InterruptedException e) {
        return;
      }
      Watched watched = keys.get(key);
      boolean lost = false;
      int failedBefore = failures.get();
      for (WatchEvent<?> event : key.pollEvents()) {
        if (event.kind() == OVERFLOW) {
          lost = true;
        } else if (watched != null) {
          Path path = watched.dir.resolve((Path) event.context());
          if (watched.tree && event.kind() == ENTRY_CREATE && !skip.contains(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            registerTree(path); // what was made in it before this is read by the event below
          }
          deliver(() -> events.changed(path));
        }
      }
      if (!key.reset()) {
        keys.remove(key);
      }
      if (lost) {
        log.accept("file events were lost (" + (watched == null ? "watch service" : watched.dir) + " overflowed): reading the source roots again");
      }
      if (lost || failures.get() != failedBefore) {
        deliver(events::rescan);
      }
    }
  }

  /** Runs a callback; one that throws must not end the watcher. */
  private void deliver(Runnable callback) {
    try {
      callback.run();
    } catch (RuntimeException e) {
      log.accept("a file event failed: " + e);
    }
  }

  private static final class Watched {
    final Path dir;
    /** Whether directories created below it are watched too. */
    final boolean tree;

    Watched(Path dir, boolean tree) {
      this.dir = dir;
      this.tree = tree;
    }
  }
}
