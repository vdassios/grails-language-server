package io.github.vdassios.grailsls.compile;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;

/** A lookup that answers as another, but with {@link #pluginFiles()} given: as if a plugin jar on the class path supplied them. */
final class WithPluginFiles implements SourceLookup {
  private final SourceLookup lookup;
  private final Set<String> pluginFiles;

  WithPluginFiles(SourceLookup lookup, Set<String> pluginFiles) {
    this.lookup = lookup;
    this.pluginFiles = pluginFiles;
  }

  @Override
  public String fileOf(String className) {
    return lookup.fileOf(className);
  }

  @Override
  public String openText(String path) {
    return lookup.openText(path);
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
  public List<String> classesNamed(String simpleName) {
    return lookup.classesNamed(simpleName);
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
    return pluginFiles;
  }
}
