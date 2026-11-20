package io.github.vdassios.grailsls.project;

import java.nio.file.Path;
import java.util.List;

/** The resolved Gradle build: its root (where the wrapper is) and one record per project. */
public final class ProjectModel {
  public final Path buildRoot;
  public final List<ModuleRecord> modules;
  public final boolean fromCache;
  /** The cache key of the build files it was read from ({@link GradleBridge#cacheKey}); null when made by hand (tests). */
  public final String key;

  public ProjectModel(Path buildRoot, List<ModuleRecord> modules, boolean fromCache) {
    this(buildRoot, modules, fromCache, null);
  }

  public ProjectModel(Path buildRoot, List<ModuleRecord> modules, boolean fromCache, String key) {
    this.buildRoot = buildRoot;
    this.modules = List.copyOf(modules);
    this.fromCache = fromCache;
    this.key = key;
  }

  public ModuleRecord module(String path) {
    return modules.stream().filter(m -> m.path.equals(path)).findFirst().orElse(null);
  }
}
