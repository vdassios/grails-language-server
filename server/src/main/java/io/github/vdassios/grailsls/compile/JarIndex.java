package io.github.vdassios.grailsls.compile;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The entry names of a class path's jars, as hash codes, and its directories: tells when a resource is in none of
 * them, without searching each jar. Groovy's resolver tries thousands of names that exist nowhere in one compile (a
 * simple name in each imported package), and the URL class path searches every jar for each of them, twice.
 *
 * <p>Jars are read once per workspace, for every loader ({@link JarEntries}). Directories (sibling modules' class output) are probed on every lookup, as a build
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

  /** The index of a class path's URLs, in their order, from the jars' entries; {@link #NONE} when one is not a file URL. */
  static JarIndex of(URL[] urls, JarEntries entries) {
    List<Path> directories = new ArrayList<>();
    List<Path> jars = new ArrayList<>();
    for (URL url : urls) {
      Path path = JarEntries.path(url);
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
      List<JarEntries.Jar> read = round.parallelStream().map(entries::of).collect(Collectors.toList());
      List<Path> next = new ArrayList<>();
      for (JarEntries.Jar jar : read) {
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
}
