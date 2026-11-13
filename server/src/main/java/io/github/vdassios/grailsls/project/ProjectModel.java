package io.github.vdassios.grailsls.project;

import java.nio.file.Path;
import java.util.List;

/** The resolved Gradle build: its root (where the wrapper is) and one record per project. */
public final class ProjectModel {
  public final Path buildRoot;
  public final List<ModuleRecord> modules;
  public final boolean fromCache;

  ProjectModel(Path buildRoot, List<ModuleRecord> modules, boolean fromCache) {
    this.buildRoot = buildRoot;
    this.modules = List.copyOf(modules);
    this.fromCache = fromCache;
  }

  public ModuleRecord module(String path) {
    return modules.stream().filter(m -> m.path.equals(path)).findFirst().orElse(null);
  }
}
