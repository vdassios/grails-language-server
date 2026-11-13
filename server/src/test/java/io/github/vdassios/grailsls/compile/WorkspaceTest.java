package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.project.ModuleRecord;
import io.github.vdassios.grailsls.project.ModuleRecord.SourceSetRecord;
import io.github.vdassios.grailsls.project.ProjectModel;

/** The loader and lookup setup per source set, on a synthetic two-module build. */
class WorkspaceTest {
  @TempDir
  Path tmp;

  private Path touch(String relative, String text) throws IOException {
    Path file = tmp.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    return file;
  }

  private static SourceSetRecord sourceSet(String name, List<Path> roots, List<Path> classpath, List<Path> classesDirs, List<Path> javaFiles) {
    SourceSetRecord record = new SourceSetRecord();
    record.name = name;
    roots.forEach(p -> record.sourceRoots.add(p.toString()));
    classpath.forEach(p -> record.compileClasspath.add(p.toString()));
    classesDirs.forEach(p -> record.classesDirs.add(p.toString()));
    javaFiles.forEach(p -> record.javaFiles.add(p.toString()));
    return record;
  }

  private static ModuleRecord module(String path, Path dir, String groovy, List<String> dependsOn, SourceSetRecord... sourceSets) {
    ModuleRecord record = new ModuleRecord();
    record.path = path;
    record.projectDir = dir.toString();
    record.groovyVersion = groovy;
    record.dependsOn.addAll(dependsOn);
    record.sourceSets.addAll(List.of(sourceSets));
    return record;
  }

  /** {@code app} depends on {@code lib}; {@code app} joint-compiles Java, one file of it not built. */
  private Workspace workspace() throws IOException {
    Path app = tmp.resolve("app");
    Path lib = tmp.resolve("lib");
    Path appClasses = app.resolve("build/classes/groovy/main");
    touch("app/build/classes/groovy/main/a/Built.class", "");
    touch("app/build/classes/groovy/main/a/Built$Inner.class", "");
    touch("app/build/classes/groovy/main/a/Built$1.class", "");
    touch("app/build/classes/groovy/main/a/BuiltOther.class", "");
    touch("app/build/classes/groovy/main/a/GroovyClass.class", "");
    Path built = touch("app/src/main/java/a/Built.java", "package a;\npublic class Built {\n  static class Inner {}\n}\n");
    Path notBuilt = touch("app/src/main/groovy/a/Fresh.java", "package a;\npublic class Fresh {}\n");
    Path jar = touch("repo/dep.jar", "");

    SourceSetRecord appMain = sourceSet("main", List.of(app.resolve("grails-app/domain"), app.resolve("src/main/groovy"), app.resolve("src/main/java"), app.resolve("build/generated")),
        List.of(jar, lib.resolve("build/libs/lib.jar")), List.of(appClasses, app.resolve("build/classes/java/main")), List.of(built, notBuilt));
    SourceSetRecord appTest = sourceSet("test", List.of(app.resolve("src/test/groovy")), List.of(jar, appClasses, app.resolve("build/resources/main"), lib.resolve("build/libs/lib.jar")),
        List.of(app.resolve("build/classes/groovy/test")), List.of());
    SourceSetRecord libMain = sourceSet("main", List.of(lib.resolve("src/main/groovy")), List.of(jar), List.of(lib.resolve("build/classes/groovy/main")), List.of());
    ModuleRecord root = module(":", tmp, null, List.of());
    return new Workspace(new ProjectModel(tmp, List.of(root, module(":app", app, "2.5.14", List.of(":lib"), appMain, appTest), module(":lib", lib, "2.5.14", List.of(), libMain)), false),
        tmp.resolve("analysis.jar"));
  }

  @Test
  void oneContextPerSourceSetOfAGroovyModule() throws IOException {
    try (Workspace workspace = workspace()) {
      assertEquals(List.of(":app main", ":app test", ":lib main"), workspace.contexts().stream().map(c -> c.name).collect(java.util.stream.Collectors.toList()));
    }
  }

  @Test
  void theLoaderHasNoBuildOutputButTheSiblingsClassDirectories() throws IOException {
    try (Workspace workspace = workspace()) {
      CompileContext appMain = workspace.contexts().get(0);
      assertEquals(List.of(tmp.resolve("analysis.jar"), tmp.resolve("repo/dep.jar"), tmp.resolve("lib/build/classes/groovy/main")), appMain.classpath(),
          "analysis jar first; lib.jar replaced by lib's class directory");
      CompileContext appTest = workspace.contexts().get(1);
      assertTrue(appTest.classpath().stream().noneMatch(p -> p.startsWith(tmp.resolve("app/build"))), "main's whole class output stays off: " + appTest.classpath());
    }
  }

  @Test
  void onlyClassesOfJavaSourcesAreServedFromTheClassOutput() throws IOException {
    try (Workspace workspace = workspace()) {
      CompileContext appMain = workspace.contexts().get(0);
      assertEquals(3, appMain.javaClasses(), "Built, Built$Inner, Built$1; not BuiltOther or GroovyClass");
      assertEquals(1, appMain.javaFilesNotBuilt);
      assertEquals(", 1 Java file not built (run the Gradle classes task)", workspace.summary());
      assertEquals(3, workspace.contexts().get(1).javaClasses(), "a test source set sees main's Java classes");
    }
  }

  @Test
  void groovyClassesResolveFromOwnThenMainThenSiblingRoots() throws IOException {
    try (Workspace workspace = workspace()) {
      CompileContext appTest = workspace.contexts().get(1);
      assertEquals(List.of(tmp.resolve("app/src/test/groovy"), tmp.resolve("app/grails-app/domain"), tmp.resolve("app/src/main/groovy"), tmp.resolve("app/src/main/java"),
          tmp.resolve("lib/src/main/groovy")), appTest.searchRoots, "build/generated is no source");
    }
  }

  @Test
  void filesMapToTheirSourceSet() throws IOException {
    try (Workspace workspace = workspace()) {
      assertSame(workspace.contexts().get(0), workspace.contextOf(tmp.resolve("app/grails-app/domain/a/Book.groovy")));
      assertSame(workspace.contexts().get(1), workspace.contextOf(tmp.resolve("app/src/test/groovy/a/BookSpec.groovy")));
      assertSame(workspace.contexts().get(2), workspace.contextOf(tmp.resolve("lib/src/main/groovy/l/Clock.groovy")));
      assertNull(workspace.contextOf(tmp.resolve("app/build.gradle")));
      assertNull(workspace.contextOf(tmp.resolve("app/src/main/groovy/build/Copy.groovy")), "a build directory below a root is no source");
    }
  }

  @Test
  void theIndexAnswersFromTheSearchRoots() throws IOException {
    Path clock = touch("lib/src/main/groovy/l/Clock.groovy", "package l\nclass Clock {}\n");
    try (Workspace workspace = workspace()) {
      workspace.index();
      assertEquals(clock.toString(), workspace.lookup(workspace.contexts().get(0), p -> null).fileOf("l.Clock"));
      assertNull(workspace.lookup(workspace.contexts().get(2), p -> null).fileOf("a.Book"));
    }
  }
}
