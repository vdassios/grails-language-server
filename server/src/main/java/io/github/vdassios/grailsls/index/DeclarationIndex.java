package io.github.vdassios.grailsls.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Maps type names to the Groovy files that declare them, per source root. Built by a scan of the roots, then kept
 * current file by file ({@link #update}) and by {@link #rescan}; the names of a file come from its text
 * ({@link Declarations}) until a parse of it gives them ({@link #parsed}). The modules' build directories are never
 * source, wherever they lie.
 */
public final class DeclarationIndex {
  private final Map<Path, Root> roots = new ConcurrentHashMap<>();
  /** The modules' build directories: they hold build output and copies of sources, never sources themselves. */
  private final Set<Path> buildDirs;

  public DeclarationIndex(Set<Path> buildDirs) {
    this.buildDirs = Set.copyOf(buildDirs);
  }

  /** Scans each root not indexed yet. */
  public void addRoots(List<Path> paths) {
    for (Path path : paths) {
      roots.computeIfAbsent(path, this::scan);
    }
  }

  /** The file in the first of {@code searchRoots} that declares {@code className}, or null. */
  public Path fileOf(String className, List<Path> searchRoots) {
    for (Path path : searchRoots) {
      Root root = roots.get(path);
      List<Path> declaring = root == null ? null : root.files.get(className);
      if (declaring != null) {
        return declaring.get(0);
      }
    }
    return null;
  }

  /** Top-level classes named {@code simpleName} in any package, in the order of {@code searchRoots}, each once. */
  public List<String> classesNamed(String simpleName, List<Path> searchRoots) {
    Set<String> found = new LinkedHashSet<>();
    for (Path path : searchRoots) {
      Root root = roots.get(path);
      if (root == null) {
        continue;
      }
      for (String name : root.files.keySet()) {
        if (name.indexOf('$') < 0 && (name.equals(simpleName) || name.endsWith("." + simpleName))) {
          found.add(name);
        }
      }
    }
    return new ArrayList<>(found);
  }

  /**
   * Re-reads what changed at {@code path}: a Groovy file that was saved, created, changed on disk or deleted, or a
   * directory that was created or deleted (a deleted one takes every file below it). Other files, and paths outside
   * every root or in a build directory, are ignored.
   */
  public Changes update(Path path) {
    if (path.toString().endsWith(".groovy")) {
      Changes changes = new Changes();
      for (Root root : roots.values()) {
        if (path.startsWith(root.path) && isSource(path)) {
          root.reindex(path, changes);
        }
      }
      return changes;
    }
    if (Files.exists(path) && !Files.isDirectory(path)) {
      return new Changes();
    }
    return sync(path);
  }

  /**
   * Replaces the names read from {@code file} by {@code classes}, the binary names a parse of it found, so that a
   * mistake of the text scan ({@link Declarations}) lasts only until the file is parsed. Ignored for a file not indexed
   * (deleted since, or no source). Returns whether the names changed.
   */
  public boolean parsed(Path file, List<String> classes) {
    boolean changed = false;
    for (Root root : roots.values()) {
      changed |= root.replace(file, classes);
    }
    return changed;
  }

  /** Brings every root in line with the disk: files created, deleted or modified since they were read (by modification time). */
  public Changes rescan() {
    Changes changes = new Changes();
    for (Root root : roots.values()) {
      sync(root, root.path, changes);
    }
    return changes;
  }

  /** {@link #rescan} below {@code dir}, an existing or deleted directory. */
  private Changes sync(Path dir) {
    Changes changes = new Changes();
    for (Root root : roots.values()) {
      if (dir.startsWith(root.path) && isSource(dir)) {
        sync(root, dir, changes);
      } else if (root.path.startsWith(dir)) {
        sync(root, root.path, changes); // the root itself went or came back
      }
    }
    return changes;
  }

  private void sync(Root root, Path dir, Changes changes) {
    Map<Path, FileTime> onDisk = new HashMap<>();
    walk(dir, (file, attrs) -> onDisk.put(file, attrs.lastModifiedTime()));
    Map<Path, FileTime> known = root.modifiedBelow(dir);
    onDisk.forEach((file, modified) -> {
      if (!modified.equals(known.get(file))) {
        root.reindex(file, changes);
      }
    });
    for (Path file : known.keySet()) {
      if (!onDisk.containsKey(file)) {
        root.reindex(file, changes);
      }
    }
  }

  /** What an update or rescan changed. */
  public static final class Changes {
    /** The files read again, deleted ones included. */
    public final List<Path> files = new ArrayList<>();
    /** Whether a type name was added or removed, or moved to another file. */
    public boolean declarations;
  }

  /** The Groovy files indexed below {@code paths}, each once. */
  public List<Path> sources(List<Path> paths) {
    Set<Path> files = new LinkedHashSet<>();
    for (Path path : paths) {
      Root root = roots.get(path);
      if (root != null) {
        files.addAll(root.files());
      }
    }
    return new ArrayList<>(files);
  }

  /** Names indexed over all roots. */
  public int size() {
    return roots.values().stream().mapToInt(r -> r.files.size()).sum();
  }

  /** Files indexed over all roots. */
  public int files() {
    return roots.values().stream().mapToInt(Root::fileCount).sum();
  }

  /** True unless the file or directory lies in a module's build directory. */
  public boolean isSource(Path file) {
    return buildDirs.stream().noneMatch(file::startsWith);
  }

  private Root scan(Path path) {
    Root root = new Root(path);
    walk(path, (file, attrs) -> root.reindex(file, new Changes()));
    return root;
  }

  /** Visits the Groovy files below {@code dir} (nothing when it does not exist), skipping the build directories. */
  private void walk(Path dir, BiConsumer<Path, BasicFileAttributes> visit) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try {
      Files.walkFileTree(dir, new SimpleFileVisitor<>() {
        @Override
        public FileVisitResult preVisitDirectory(Path child, BasicFileAttributes attrs) {
          return buildDirs.contains(child) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
          if (attrs.isRegularFile() && file.toString().endsWith(".groovy")) {
            visit.accept(file, attrs);
          }
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException e) {
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * One root's files and names. Every file declaring a name is kept, in the order they were read, and the first answers:
   * a rename delivered as create-then-delete has two for a moment, and the class must outlive the delete.
   */
  private static final class Root {
    final Path path;
    /** Name to its declaring files; each list is replaced, never changed, so readers need no lock. */
    final Map<String, List<Path>> files = new ConcurrentHashMap<>();
    final Map<Path, Entry> byFile = new HashMap<>();

    Root(Path path) {
      this.path = path;
    }

    synchronized List<Path> files() {
      return new ArrayList<>(byFile.keySet());
    }

    synchronized int fileCount() {
      return byFile.size();
    }

    /** Modification times of the files indexed below {@code dir}, as last read. */
    synchronized Map<Path, FileTime> modifiedBelow(Path dir) {
      Map<Path, FileTime> found = new HashMap<>();
      byFile.forEach((file, entry) -> {
        if (file.startsWith(dir)) {
          found.put(file, entry.modified);
        }
      });
      return found;
    }

    synchronized void reindex(Path file, Changes changes) {
      changes.files.add(file);
      Entry old = byFile.remove(file);
      List<String> oldNames = old == null ? List.of() : old.names;
      unlink(file, oldNames);
      List<String> names = List.of();
      try {
        // the time first: a write after it is seen by the next rescan
        FileTime modified = Files.getLastModifiedTime(file);
        names = List.copyOf(new LinkedHashSet<>(Declarations.of(Files.readString(file, StandardCharsets.UTF_8))));
        byFile.put(file, new Entry(modified, names));
      } catch (IOException e) {
        // deleted or unreadable
      }
      link(file, names);
      if (!oldNames.equals(names)) {
        changes.declarations = true;
      }
    }

    /** {@link DeclarationIndex#parsed} for this root. */
    synchronized boolean replace(Path file, List<String> classes) {
      Entry entry = byFile.get(file);
      List<String> names = List.copyOf(new LinkedHashSet<>(classes));
      if (entry == null || Set.copyOf(entry.names).equals(Set.copyOf(names))) {
        return false;
      }
      unlink(file, entry.names);
      byFile.put(file, new Entry(entry.modified, names));
      link(file, names);
      return true;
    }

    private void unlink(Path file, List<String> names) {
      for (String name : names) {
        files.computeIfPresent(name, (n, declaring) -> {
          List<Path> rest = new ArrayList<>(declaring);
          rest.remove(file);
          return rest.isEmpty() ? null : List.copyOf(rest);
        });
      }
    }

    private void link(Path file, List<String> names) {
      for (String name : names) {
        files.merge(name, List.of(file), (declaring, added) -> {
          List<Path> all = new ArrayList<>(declaring);
          all.add(file);
          return List.copyOf(all);
        });
      }
    }
  }

  private static final class Entry {
    final FileTime modified;
    final List<String> names;

    Entry(FileTime modified, List<String> names) {
      this.modified = modified;
      this.names = names;
    }
  }
}
