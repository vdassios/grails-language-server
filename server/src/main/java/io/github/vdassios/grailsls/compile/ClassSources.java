package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Source files for classes that compiles read from class files, for definition and hover into libraries. A jar's
 * sources are the {@code -sources.jar} already on disk next to it: in its directory (a Maven repository) or in a
 * sibling hash directory of Gradle's module cache. JDK classes come from {@code lib/src.zip} of the JDK this server
 * runs on. Nothing is downloaded: a library whose sources were never fetched has none here.
 *
 * <p>The one file asked for is extracted, once, under {@code dir} and made read-only, so every editor opens it as a
 * plain file.
 */
final class ClassSources {
  private static final String[] EXTENSIONS = { ".java", ".groovy" };

  private final Path dir;

  ClassSources(Path dir) {
    this.dir = dir;
  }

  /**
   * @param className binary name of a top-level class
   * @param classFile where the module loader finds its class file: a {@code jar:} or {@code jrt:} URL
   * @return the extracted source file, or null when no source is on disk
   */
  Path sourceOf(String className, URL classFile) {
    String entry = className.replace('.', '/');
    try {
      if ("jrt".equals(classFile.getProtocol())) {
        // jrt:/java.base/java/lang/String.class
        String path = classFile.getPath();
        int slash = path.indexOf('/', 1);
        Path archive = Path.of(System.getProperty("java.home"), "lib", "src.zip");
        return slash < 0 ? null : extract(archive, path.substring(1, slash) + "/" + entry, "jdk-" + System.getProperty("java.version"));
      }
      String spec = classFile.toString();
      int end = spec.indexOf("!/");
      if (!"jar".equals(classFile.getProtocol()) || end < 0) {
        return null;
      }
      Path jar = Path.of(URI.create(spec.substring("jar:".length(), end)));
      for (Path sources : sourcesJars(jar)) {
        String name = sources.getFileName().toString();
        Path source = extract(sources, entry, name.substring(0, name.length() - ".jar".length()));
        if (source != null) {
          return source;
        }
      }
    } catch (IOException | RuntimeException e) {
      // unreadable archive, odd URL: no source
    }
    return null;
  }

  /** The sources jars on disk for a jar: beside it, then in the other hash directories of its version in Gradle's cache. */
  private static List<Path> sourcesJars(Path jar) throws IOException {
    String name = jar.getFileName().toString();
    if (!name.endsWith(".jar")) {
      return List.of();
    }
    String sourcesName = name.substring(0, name.length() - ".jar".length()) + "-sources.jar";
    List<Path> found = new ArrayList<>();
    Path beside = jar.resolveSibling(sourcesName);
    if (Files.isRegularFile(beside)) {
      found.add(beside);
    }
    // files-2.1/<group>/<artifact>/<version>/<sha1>/<artifact>-<version>.jar
    Path hashDir = jar.getParent();
    if (hashDir != null && hashDir.getParent() != null && hashDir.getFileName().toString().matches("[0-9a-f]{1,40}")) {
      try (DirectoryStream<Path> siblings = Files.newDirectoryStream(hashDir.getParent())) {
        for (Path sibling : siblings) {
          Path candidate = sibling.resolve(sourcesName);
          if (!sibling.equals(hashDir) && Files.isRegularFile(candidate)) {
            found.add(candidate);
          }
        }
      }
    }
    return found;
  }

  /** The archive's {@code <entry>.java} or {@code .groovy} as a file under {@code dir/<into>}; null when it has neither. */
  private Path extract(Path archive, String entry, String into) throws IOException {
    for (String extension : EXTENSIONS) {
      Path target = dir.resolve(into).resolve(entry + extension).normalize();
      if (Files.isRegularFile(target)) {
        return target;
      }
    }
    if (!Files.isRegularFile(archive)) {
      return null;
    }
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      for (String extension : EXTENSIONS) {
        ZipEntry found = zip.getEntry(entry + extension);
        if (found == null) {
          continue;
        }
        Path target = dir.resolve(into).resolve(entry + extension).normalize();
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), "source", ".tmp");
        try (InputStream in = zip.getInputStream(found)) {
          Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.toFile().setReadOnly();
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return target;
      }
    }
    return null;
  }
}
