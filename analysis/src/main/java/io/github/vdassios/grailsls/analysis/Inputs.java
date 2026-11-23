package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;

/**
 * A compile's lookup, recording what the compile asked it: which file declares each class name looked up, the classes
 * each simple name has, and each source pulled in, by its open text or, read from disk, its modification time and
 * size. {@link #same} tells whether a lookup still answers so, i.e. whether the classes the compile read from other
 * sources are still what a new compile would read. The compile's resolver ({@link PathResolver}) and
 * {@link Injections} use only {@link #fileOf}, {@link #openText} and {@link #classesNamed}; the rest is passed on
 * unrecorded. Thread-safe: classes read from class files resolve their references lazily, through the resolver, while
 * requests read the compile.
 */
final class Inputs implements SourceLookup {
  private final SourceLookup lookup;
  /** Answers of {@link #fileOf}, empty for null. */
  private final Map<String, Optional<String>> files = new ConcurrentHashMap<>();
  private final Map<String, List<String>> named = new ConcurrentHashMap<>();
  /** The open text of each source pulled in that was open. */
  private final Map<String, String> open = new ConcurrentHashMap<>();
  /** The modification time and size of each source pulled in from disk. */
  private final Map<String, String> disk = new ConcurrentHashMap<>();

  Inputs(SourceLookup lookup) {
    this.lookup = lookup;
  }

  /** Whether {@code now} answers as the lookup did, and the sources read from disk have the same time and size. */
  boolean same(SourceLookup now) {
    for (Map.Entry<String, Optional<String>> file : files.entrySet()) {
      if (!Optional.ofNullable(now.fileOf(file.getKey())).equals(file.getValue())) {
        return false;
      }
    }
    for (Map.Entry<String, List<String>> classes : named.entrySet()) {
      if (!now.classesNamed(classes.getKey()).equals(classes.getValue())) {
        return false;
      }
    }
    for (Map.Entry<String, String> text : open.entrySet()) {
      if (!text.getValue().equals(now.openText(text.getKey()))) {
        return false;
      }
    }
    for (Map.Entry<String, String> file : disk.entrySet()) {
      if (now.openText(file.getKey()) != null || !stamp(file.getKey()).equals(file.getValue())) {
        return false;
      }
    }
    return true;
  }

  private static String stamp(String path) {
    try {
      BasicFileAttributes attributes = Files.readAttributes(Path.of(path), BasicFileAttributes.class);
      return attributes.lastModifiedTime().toMillis() + ":" + attributes.size();
    } catch (IOException | RuntimeException e) {
      return "none";
    }
  }

  @Override
  public String fileOf(String className) {
    String file = lookup.fileOf(className);
    files.putIfAbsent(className, Optional.ofNullable(file));
    return file;
  }

  /** The open text, or null when the caller reads the file: then its time and size are recorded. */
  @Override
  public String openText(String path) {
    String text = lookup.openText(path);
    if (text != null) {
      open.putIfAbsent(path, text);
    } else {
      disk.putIfAbsent(path, stamp(path));
    }
    return text;
  }

  @Override
  public List<String> classesNamed(String simpleName) {
    List<String> classes = lookup.classesNamed(simpleName);
    named.putIfAbsent(simpleName, classes);
    return classes;
  }

  @Override
  public List<Symbol> types() {
    return lookup.types();
  }

  @Override
  public List<String> classpathPackages(String parent) {
    return lookup.classpathPackages(parent);
  }

  @Override
  public List<String> classpathClasses(String packageName) {
    return lookup.classpathClasses(packageName);
  }

  @Override
  public String classSource(String className) {
    return lookup.classSource(className);
  }

  @Override
  public List<String> grailsAppDirs() {
    return lookup.grailsAppDirs();
  }

  @Override
  public Map<String, String> taglibClasses() {
    return lookup.taglibClasses();
  }

  @Override
  public Set<String> pluginFiles() {
    return lookup.pluginFiles();
  }
}
