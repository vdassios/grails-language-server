package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The packages and top-level classes of jars and of the JDK, for import completion. Each jar's come from its entries,
 * read once per workspace ({@link JarEntries}); the JDK's are its modules' exported packages, read once per server. Entries under {@code META-INF/} (other releases of a multi-release jar),
 * {@code module-info}, {@code package-info} and names with a {@code $} (nested and generated classes) are left out.
 */
final class ClasspathNames {
  private final JarEntries jars;
  private static volatile Map<String, List<String>> jdk;

  ClasspathNames(JarEntries jars) {
    this.jars = jars;
  }

  /** The packages directly below {@code parent} that lead to classes in these jars or the JDK, sorted. */
  List<String> packages(List<Path> classpath, String parent) {
    String prefix = parent.isEmpty() ? "" : parent + ".";
    Set<String> found = new TreeSet<>();
    for (Map<String, List<String>> names : sources(classpath)) {
      for (String name : names.keySet()) {
        if (name.length() > prefix.length() && name.startsWith(prefix)) {
          int end = name.indexOf('.', prefix.length());
          found.add(end < 0 ? name : name.substring(0, end));
        }
      }
    }
    return new ArrayList<>(found);
  }

  /** The top-level classes of a package in these jars or the JDK, qualified and sorted. */
  List<String> classes(List<Path> classpath, String packageName) {
    Set<String> found = new TreeSet<>();
    for (Map<String, List<String>> names : sources(classpath)) {
      for (String simpleName : names.getOrDefault(packageName, List.of())) {
        found.add(packageName + "." + simpleName);
      }
    }
    return new ArrayList<>(found);
  }

  private List<Map<String, List<String>>> sources(List<Path> classpath) {
    List<Path> jarFiles = classpath.stream().filter(entry -> entry.getFileName() != null && entry.getFileName().toString().endsWith(".jar")).collect(Collectors.toList());
    List<Map<String, List<String>>> sources = jarFiles.parallelStream().map(jar -> jars.of(jar).classes).collect(Collectors.toList());
    sources.add(jdk());
    return sources;
  }

  private static Map<String, List<String>> jdk() {
    Map<String, List<String>> names = jdk;
    if (names == null) {
      names = new HashMap<>();
      for (ModuleReference module : ModuleFinder.ofSystem().findAll()) {
        Set<String> exported = module.descriptor().exports().stream().filter(e -> !e.isQualified()).map(ModuleDescriptor.Exports::source).collect(Collectors.toSet());
        if (exported.isEmpty()) {
          continue;
        }
        try (ModuleReader reader = module.open(); Stream<String> resources = reader.list()) {
          Map<String, List<String>> into = names;
          resources.forEach(resource -> {
            int slash = resource.lastIndexOf('/');
            if (slash > 0 && exported.contains(resource.substring(0, slash).replace('/', '.'))) {
              add(into, resource);
            }
          });
        } catch (IOException | UncheckedIOException e) {
          // unreadable: offer the other modules
        }
      }
      jdk = names;
    }
    return names;
  }

  /** Records a class file entry ({@code a/b/C.class}) under its package. */
  static void add(Map<String, List<String>> names, String entry) {
    int slash = entry.lastIndexOf('/');
    if (slash <= 0 || !entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.indexOf('$') >= 0 || entry.indexOf('-') >= 0) {
      return;
    }
    String simpleName = entry.substring(slash + 1, entry.length() - ".class".length());
    names.computeIfAbsent(entry.substring(0, slash).replace('/', '.'), p -> new ArrayList<>()).add(simpleName);
  }
}
