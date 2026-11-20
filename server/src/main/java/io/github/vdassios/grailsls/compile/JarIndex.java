package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The entry names of a class path's jars, as hash codes, and its directories: tells when a resource is in none of
 * them, without searching each jar. Groovy's resolver tries thousands of names that exist nowhere in one compile (a
 * simple name in each imported package), and the URL class path searches every jar for each of them, twice.
 *
 * <p>Jars are read once: they are not rebuilt under a running server (the loader keeps no build output) and the URL
 * class path keeps them open. Directories (sibling modules' class output) are probed on every lookup, as a build
 * writes them; a directory URL (ending in a slash) counts as one before it exists, as the URL class path does.
 */
final class JarIndex {
  /** Says every resource may be there: for URLs that are not files. */
  static final JarIndex NONE = new JarIndex(null, List.of());

  /** Hash codes of every entry name, sorted and distinct (a directory entry also without its trailing slash); null for {@link #NONE}. */
  private final int[] names;
  private final List<Path> directories;

  private JarIndex(int[] names, List<Path> directories) {
    this.names = names;
    this.directories = List.copyOf(directories);
  }

  /** The index of a class path's URLs, in their order; {@link #NONE} when one is not a file URL. */
  static JarIndex of(URL[] urls) {
    List<Path> directories = new ArrayList<>();
    List<Path> jars = new ArrayList<>();
    for (URL url : urls) {
      Path path = path(url);
      if (path == null) {
        return NONE;
      }
      if (Files.isDirectory(path) || url.getPath().endsWith("/")) {
        directories.add(path);
      } else if (Files.isRegularFile(path)) {
        jars.add(path);
      }
    }
    // the jars a jar's manifest names (Class-Path) are on the class path too
    Set<Path> seen = new HashSet<>(jars);
    List<int[]> hashes = new ArrayList<>();
    for (List<Path> round = jars; !round.isEmpty();) {
      List<Jar> read = round.parallelStream().map(JarIndex::read).collect(Collectors.toList());
      List<Path> next = new ArrayList<>();
      for (Jar jar : read) {
        if (jar.classPath == null) {
          return NONE;
        }
        hashes.add(jar.names);
        for (Path named : jar.classPath) {
          if (Files.isDirectory(named)) {
            directories.add(named);
          } else if (Files.isRegularFile(named) && seen.add(named)) {
            next.add(named);
          }
        }
      }
      round = next;
    }
    return new JarIndex(hashes.stream().flatMapToInt(Arrays::stream).sorted().distinct().toArray(), directories);
  }

  /** False when no jar and no directory has the resource ({@code a/b/C.class}, {@code a/b/}); true when one may. */
  boolean mayHave(String resource) {
    if (names == null || Arrays.binarySearch(names, resource.hashCode()) >= 0) {
      return true;
    }
    for (Path directory : directories) {
      if (Files.exists(directory.resolve(resource))) {
        return true;
      }
    }
    return false;
  }

  private static Path path(URL url) {
    if (!"file".equals(url.getProtocol())) {
      return null;
    }
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }

  /** A jar's entry names and the paths its manifest names; {@code classPath} null when one is not a file URL. */
  private static final class Jar {
    final int[] names;
    final List<Path> classPath;

    Jar(int[] names, List<Path> classPath) {
      this.names = names;
      this.classPath = classPath;
    }
  }

  private static Jar read(Path file) {
    // opened as the URL class path opens it: a multi-release jar's versioned entries count under their own names
    try (JarFile jar = new JarFile(file.toFile(), false, ZipFile.OPEN_READ, JarFile.runtimeVersion())) {
      List<Path> classPath = new ArrayList<>();
      Manifest manifest = jar.getManifest();
      String value = manifest == null ? null : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      if (value != null) {
        for (String entry : value.trim().split("\\s+")) {
          URL url;
          try {
            url = new URL(file.toUri().toURL(), entry);
          } catch (IOException e) {
            continue; // the URL class path skips it too
          }
          Path path = path(url);
          if (path == null) {
            return new Jar(new int[0], null);
          }
          classPath.add(path);
        }
      }
      int[] names = Stream.concat(jar.stream(), jar.isMultiRelease() ? jar.versionedStream() : Stream.<JarEntry>empty())
          .map(JarEntry::getName)
          .flatMapToInt(name -> name.endsWith("/") ? IntStream.of(name.hashCode(), name.substring(0, name.length() - 1).hashCode()) : IntStream.of(name.hashCode()))
          .toArray();
      return new Jar(names, classPath);
    } catch (IOException e) {
      return new Jar(new int[0], List.of()); // not a jar (a .pom on the classpath): the URL class path skips it too
    }
  }
}
