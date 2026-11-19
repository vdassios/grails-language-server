package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The packages and top-level classes of jars and of the JDK, for import completion. Each jar is read once, on the
 * first import completion of a context holding it, and shared by every context; the JDK's are its modules' exported
 * packages, read once per server. Entries under {@code META-INF/} (other releases of a multi-release jar),
 * {@code module-info}, {@code package-info} and names with a {@code $} (nested and generated classes) are left out.
 */
final class ClasspathNames {
  /** Top-level classes by package, per jar; an unreadable jar has none. */
  private final Map<Path, Map<String, List<String>>> jars = new ConcurrentHashMap<>();
  private static volatile Map<String, List<String>> jdk;

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
    List<Map<String, List<String>>> sources = jarFiles.parallelStream().map(jar -> jars.computeIfAbsent(jar, ClasspathNames::read)).collect(Collectors.toList());
    sources.add(jdk());
    return sources;
  }

  private static Map<String, List<String>> read(Path jar) {
    Map<String, List<String>> names = new HashMap<>();
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      Enumeration<? extends ZipEntry> entries = zip.entries();
      while (entries.hasMoreElements()) {
        add(names, entries.nextElement().getName());
      }
    } catch (IOException e) {
      // not a jar: the class path skips it too
    }
    return names;
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
  private static void add(Map<String, List<String>> names, String entry) {
    int slash = entry.lastIndexOf('/');
    if (slash <= 0 || !entry.endsWith(".class") || entry.startsWith("META-INF/") || entry.indexOf('$') >= 0 || entry.indexOf('-') >= 0) {
      return;
    }
    String simpleName = entry.substring(slash + 1, entry.length() - ".class".length());
    names.computeIfAbsent(entry.substring(0, slash).replace('/', '.'), p -> new ArrayList<>()).add(simpleName);
  }
}
