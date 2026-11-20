package io.github.vdassios.grailsls.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceWatcherTest {
  @TempDir
  Path tmp;

  private final BlockingQueue<Path> changed = new LinkedBlockingQueue<>();
  private final BlockingQueue<Boolean> rescans = new LinkedBlockingQueue<>();
  private final List<String> logged = new CopyOnWriteArrayList<>();

  private final SourceWatcher.Events events = new SourceWatcher.Events() {
    @Override
    public void changed(Path path) {
      changed.add(path);
    }

    @Override
    public void rescan() {
      rescans.add(true);
    }
  };

  private Path dir(String relative) throws IOException {
    return Files.createDirectories(tmp.resolve(relative));
  }

  /** Every directory below a tree is watched, build directories skipped; a flat directory alone. */
  @Test
  void watchesTheTreesBelowAndTheFlatDirectoriesAlone() throws IOException {
    Path src = dir("app/src/main/groovy/p/q");
    dir("app/src/main/groovy/build-dir/classes");
    dir("app/gradle/wrapper");
    FakeService service = new FakeService();
    Map<Path, FakeKey> registered = new ConcurrentHashMap<>();
    try (SourceWatcher watcher = new SourceWatcher(service, d -> registered.computeIfAbsent(d, FakeKey::new), List.of(tmp.resolve("app/src/main/groovy"), src),
        List.of(tmp.resolve("app"), tmp.resolve("app/gradle")), Set.of(tmp.resolve("app/src/main/groovy/build-dir")), events, logged::add)) {
      assertEquals(Set.of(tmp.resolve("app"), tmp.resolve("app/gradle"), tmp.resolve("app/src/main/groovy"), tmp.resolve("app/src/main/groovy/p"), src),
          registered.keySet());
      assertFalse(watcher.failed());
      assertEquals(List.of(), logged);
    }
  }

  /** Lost events (the kernel's queue overflowed) are said in the log and followed by a rescan. */
  @Test
  void anOverflowAsksForARescan() throws Exception {
    Path src = dir("src");
    FakeService service = new FakeService();
    Map<Path, FakeKey> registered = new ConcurrentHashMap<>();
    try (SourceWatcher watcher = new SourceWatcher(service, d -> registered.computeIfAbsent(d, FakeKey::new), List.of(src), List.of(), Set.of(), events, logged::add)) {
      service.signal(registered.get(src), StandardWatchEventKinds.OVERFLOW, null);
      assertEquals(Boolean.TRUE, rescans.poll(10, TimeUnit.SECONDS), "no rescan");
      assertTrue(logged.stream().anyMatch(m -> m.startsWith("file events were lost")), logged.toString());
      service.signal(registered.get(src), StandardWatchEventKinds.ENTRY_CREATE, Path.of("A.groovy"));
      assertEquals(src.resolve("A.groovy"), changed.poll(10, TimeUnit.SECONDS), "it goes on watching");
    }
  }

  /**
   * A directory that cannot be registered (inotify's watch limit): logged once, a rescan, and {@link SourceWatcher#failed}
   * so the server rescans on save. The others are still watched; a later failure (a new directory) rescans too.
   */
  @Test
  void aDirectoryThatCannotBeWatchedAsksForARescan() throws Exception {
    Path src = dir("src");
    Path full = dir("src/full");
    FakeService service = new FakeService();
    Map<Path, FakeKey> registered = new ConcurrentHashMap<>();
    Set<Path> refused = ConcurrentHashMap.newKeySet();
    refused.add(full);
    SourceWatcher.Registrar registrar = d -> {
      if (refused.contains(d)) {
        throw new IOException("User limit of inotify watches reached");
      }
      return registered.computeIfAbsent(d, FakeKey::new);
    };
    try (SourceWatcher watcher = new SourceWatcher(service, registrar, List.of(src), List.of(), Set.of(), events, logged::add)) {
      assertTrue(watcher.failed());
      assertEquals(Boolean.TRUE, rescans.poll(10, TimeUnit.SECONDS), "no rescan");
      assertEquals(1, logged.size(), logged.toString());
      assertTrue(logged.get(0).startsWith("cannot watch " + full + " (java.io.IOException: User limit of inotify watches reached)"), logged.get(0));
      assertEquals(Set.of(src), registered.keySet());

      Path later = dir("src/later");
      refused.add(later);
      service.signal(registered.get(src), StandardWatchEventKinds.ENTRY_CREATE, Path.of("later"));
      assertEquals(later, changed.poll(10, TimeUnit.SECONDS), "the directory itself is reported");
      assertEquals(Boolean.TRUE, rescans.poll(10, TimeUnit.SECONDS), "a later failure rescans too");
      assertEquals(1, logged.size(), "logged once: " + logged);
    }
  }

  /** The JDK's polling service can deadlock in close() while a key polls a deleted directory; cancelled keys cannot. */
  @Test
  void closeCancelsEveryKeyBeforeTheService() throws IOException {
    Path src = dir("src/p").getParent();
    FakeService service = new FakeService();
    Map<Path, FakeKey> registered = new ConcurrentHashMap<>();
    SourceWatcher watcher = new SourceWatcher(service, d -> registered.computeIfAbsent(d, FakeKey::new), List.of(src), List.of(), Set.of(), events, logged::add);
    assertEquals(2, registered.size());
    watcher.close();
    assertTrue(service.cancelledAtClose.containsAll(registered.keySet()), "cancelled at close: " + service.cancelledAtClose);
  }

  /**
   * The real watch service (inotify on Linux; on macOS the JDK polls, every 10 s): a file created, a directory created
   * with a file in it, a file created in that directory once it is watched, then the directory deleted.
   */
  @Test
  void followsTheDiskWithTheSystemsWatchService() throws Exception {
    Path src = dir("src");
    try (SourceWatcher watcher = SourceWatcher.start(List.of(src), List.of(), Set.of(), events, logged::add)) {
      Path a = Files.writeString(src.resolve("A.groovy"), "class A {}\n");
      awaitChanged(a);
      Path made = dir("src/made");
      Files.writeString(made.resolve("B.groovy"), "class B {}\n");
      awaitChanged(made);
      for (int i = 0; i < 300 && watcher.watched() < 2; i++) {
        Thread.sleep(100);
      }
      assertEquals(2, watcher.watched(), "the new directory is watched");
      Path c = Files.writeString(made.resolve("C.groovy"), "class C {}\n");
      awaitChanged(c);
      Files.delete(made.resolve("B.groovy"));
      Files.delete(c);
      Files.delete(made);
      awaitChanged(made);
      assertEquals(List.of(), logged);
    }
  }

  private void awaitChanged(Path path) throws InterruptedException {
    Set<Path> seen = new HashSet<>();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (!seen.contains(path) && System.nanoTime() < end) {
      Path next = changed.poll(1, TimeUnit.SECONDS);
      if (next != null) {
        seen.add(next);
      }
    }
    assertTrue(seen.contains(path), "no event for " + path + ", only " + seen);
  }

  /** A watch service whose keys are signalled by the test. */
  private static final class FakeService implements WatchService {
    private static final WatchKey CLOSED = new FakeKey(null);
    private final BlockingQueue<WatchKey> signalled = new LinkedBlockingQueue<>();
    private volatile boolean closed;
    /** The directories whose keys were cancelled when the service was closed (by any test). */
    volatile Set<Path> cancelledAtClose;

    <T> void signal(FakeKey key, WatchEvent.Kind<T> kind, T context) {
      key.events.add(new WatchEvent<T>() {
        @Override
        public Kind<T> kind() {
          return kind;
        }

        @Override
        public int count() {
          return 1;
        }

        @Override
        public T context() {
          return context;
        }
      });
      signalled.add(key);
    }

    @Override
    public void close() {
      cancelledAtClose = Set.copyOf(FakeKey.cancelled);
      closed = true;
      signalled.add(CLOSED);
    }

    @Override
    public WatchKey poll() {
      return signalled.poll();
    }

    @Override
    public WatchKey poll(long timeout, TimeUnit unit) throws InterruptedException {
      return signalled.poll(timeout, unit);
    }

    @Override
    public WatchKey take() throws InterruptedException {
      WatchKey key = signalled.take();
      if (closed) {
        throw new ClosedWatchServiceException();
      }
      return key;
    }
  }

  private static final class FakeKey implements WatchKey {
    static final Set<Path> cancelled = ConcurrentHashMap.newKeySet();
    final Path dir;
    final List<WatchEvent<?>> events = new CopyOnWriteArrayList<>();

    FakeKey(Path dir) {
      this.dir = dir;
    }

    @Override
    public boolean isValid() {
      return true;
    }

    @Override
    public List<WatchEvent<?>> pollEvents() {
      List<WatchEvent<?>> taken = new ArrayList<>(events);
      events.removeAll(taken);
      return taken;
    }

    @Override
    public boolean reset() {
      return true;
    }

    @Override
    public void cancel() {
      cancelled.add(dir);
    }

    @Override
    public Watchable watchable() {
      return dir;
    }
  }
}
