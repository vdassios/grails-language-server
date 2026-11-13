package io.github.vdassios.grailsls.index;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps type names to the Groovy files that declare them, per source root. Built by a scan of the roots, then kept
 * current file by file ({@link #update}). Build output and tool directories below a root are never source.
 */
public final class DeclarationIndex {
  /** Directories that hold build output or copies of sources, never sources themselves. */
  public static final Set<String> NOT_SOURCE = Set.of("build", "bin", "out", ".gradle", "node_modules");

  private final Map<Path, Root> roots = new ConcurrentHashMap<>();

  /** Scans each root not indexed yet. */
  public void addRoots(List<Path> paths) {
    for (Path path : paths) {
      roots.computeIfAbsent(path, Root::scan);
    }
  }

  /** The file in the first of {@code searchRoots} that declares {@code className}, or null. */
  public Path fileOf(String className, List<Path> searchRoots) {
    for (Path path : searchRoots) {
      Root root = roots.get(path);
      Path file = root == null ? null : root.files.get(className);
      if (file != null) {
        return file;
      }
    }
    return null;
  }

  /** Re-reads one file after it was saved, created or deleted. Files outside every root are ignored. */
  public void update(Path file) {
    if (!file.toString().endsWith(".groovy")) {
      return;
    }
    for (Root root : roots.values()) {
      if (file.startsWith(root.path) && isSource(root.path, file)) {
        root.reindex(file);
      }
    }
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
    return roots.values().stream().mapToInt(r -> r.namesByFile.size()).sum();
  }

  /** True unless the file lies in a build or tool directory below {@code root}. */
  public static boolean isSource(Path root, Path file) {
    for (Path segment : root.relativize(file)) {
      if (NOT_SOURCE.contains(segment.toString())) {
        return false;
      }
    }
    return true;
  }

  private static final class Root {
    final Path path;
    final Map<String, Path> files = new ConcurrentHashMap<>();
    final Map<Path, List<String>> namesByFile = new HashMap<>();

    Root(Path path) {
      this.path = path;
    }

    static Root scan(Path path) {
      Root root = new Root(path);
      if (!Files.isDirectory(path)) {
        return root;
      }
      try {
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            return !dir.equals(path) && NOT_SOURCE.contains(dir.getFileName().toString()) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.toString().endsWith(".groovy")) {
              root.reindex(file);
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
      return root;
    }

    synchronized List<Path> files() {
      return new ArrayList<>(namesByFile.keySet());
    }

    synchronized void reindex(Path file) {
      List<String> old = namesByFile.remove(file);
      if (old != null) {
        old.forEach(name -> files.remove(name, file));
      }
      List<String> names;
      try {
        names = Declarations.of(Files.readString(file, StandardCharsets.UTF_8));
      } catch (IOException e) {
        return; // deleted or unreadable
      }
      List<String> added = new ArrayList<>();
      for (String name : names) {
        if (files.putIfAbsent(name, file) == null) {
          added.add(name);
        }
      }
      namesByFile.put(file, added);
    }
  }
}
