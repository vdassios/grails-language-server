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

  private static SourceSetRecord sourceSet(String name, List<Path> roots, List<Path> classpath, List<Path> classesDirs) {
    SourceSetRecord record = new SourceSetRecord();
    record.name = name;
    roots.forEach(p -> record.sourceRoots.add(p.toString()));
    classpath.forEach(p -> record.compileClasspath.add(p.toString()));
    classesDirs.forEach(p -> record.classesDirs.add(p.toString()));
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

  /** {@code app} depends on {@code lib}; {@code app} joint-compiles Java, one file of main and one of test not built. */
  private Workspace workspace() throws IOException {
    Path app = tmp.resolve("app");
    Path lib = tmp.resolve("lib");
    Path appClasses = app.resolve("build/classes/groovy/main");
    touch("app/build/classes/groovy/main/a/Built.class", "");
    touch("app/build/classes/groovy/main/a/Built$Inner.class", "");
    touch("app/build/classes/groovy/main/a/Built$1.class", "");
    touch("app/build/classes/groovy/main/a/BuiltOther.class", "");
    touch("app/build/classes/groovy/main/a/GroovyClass.class", "");
    touch("app/src/main/java/a/Built.java", "package a;\npublic class Built {\n  static class Inner {}\n}\n");
    touch("app/src/main/groovy/a/Fresh.java", "package a;\npublic class Fresh {}\n");
    touch("app/src/test/groovy/a/FreshTest.java", "package a;\npublic class FreshTest {}\n");
    Path jar = touch("repo/dep.jar", "");

    SourceSetRecord appMain = sourceSet("main", List.of(app.resolve("grails-app/domain"), app.resolve("src/main/groovy"), app.resolve("src/main/java"), app.resolve("build/generated")),
        List.of(jar, lib.resolve("build/libs/lib.jar")), List.of(appClasses, app.resolve("build/classes/java/main")));
    SourceSetRecord appTest = sourceSet("test", List.of(app.resolve("src/test/groovy")), List.of(jar, appClasses, app.resolve("build/resources/main"), lib.resolve("build/libs/lib.jar")),
        List.of(app.resolve("build/classes/groovy/test")));
    SourceSetRecord libMain = sourceSet("main", List.of(lib.resolve("src/main/groovy")), List.of(jar), List.of(lib.resolve("build/classes/groovy/main")));
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
  void aSiblingAddsOnlyArtifactsTheModuleDoesNotHave() throws IOException {
    Path groovy3011 = touch("gradle/caches/modules-2/files-2.1/org.codehaus.groovy/groovy/3.0.11/a1/groovy-3.0.11.jar", "");
    Path groovy3014 = touch("gradle/caches/modules-2/files-2.1/org.codehaus.groovy/groovy/3.0.14/b2/groovy-3.0.14.jar", "");
    Path xml3014 = touch("gradle/caches/modules-2/files-2.1/org.codehaus.groovy/groovy-xml/3.0.14/c3/groovy-xml-3.0.14.jar", "");
    Path tests3014 = touch("gradle/caches/modules-2/files-2.1/org.codehaus.groovy/groovy/3.0.14/d4/groovy-3.0.14-tests.jar", "");
    Path old = touch("m2/repository/org/example/tool/1.0/tool-1.0.jar", "");
    Path newer = touch("m2/repository/org/example/tool/2.0/tool-2.0.jar", "");
    Path flat = touch("lib/libs/vendored-1.0.jar", "");
    Path app = tmp.resolve("app");
    Path lib = tmp.resolve("lib");
    SourceSetRecord appMain = sourceSet("main", List.of(app.resolve("src/main/groovy")), List.of(groovy3011, old), List.of());
    SourceSetRecord libMain = sourceSet("main", List.of(lib.resolve("src/main/groovy")), List.of(groovy3014, xml3014, tests3014, newer, flat), List.of());
    ModuleRecord root = module(":", tmp, null, List.of());
    try (Workspace workspace = new Workspace(new ProjectModel(tmp, List.of(root, module(":app", app, "3.0.11", List.of(":lib"), appMain), module(":lib", lib, "3.0.14", List.of(), libMain)), false),
        tmp.resolve("analysis.jar"))) {
      assertEquals(List.of(tmp.resolve("analysis.jar"), groovy3011, old, xml3014, tests3014, flat), workspace.contexts().get(0).classpath(),
          "app's own versions only; lib's other artifacts, a classifier and a jar of unknown layout added");
    }
  }

  @Test
  void onlyClassesOfJavaSourcesAreServedFromTheClassOutput() throws IOException {
    try (Workspace workspace = workspace()) {
      CompileContext appMain = workspace.contexts().get(0);
      assertEquals(3, appMain.javaClasses(), "Built, Built$Inner, Built$1; not BuiltOther or GroovyClass");
      assertEquals(1, appMain.javaFilesNotBuilt);
      assertEquals(1, workspace.contexts().get(1).javaFilesNotBuilt);
      assertEquals(", 2 Java files not built (run Gradle :app:classes :app:testClasses)", workspace.summary(), "each source set needs its own task");
      assertEquals(3, workspace.contexts().get(1).javaClasses(), "a test source set sees main's Java classes");
    }
  }

  /** The model lists no Java files: one added after the project was resolved is found in its root. */
  @Test
  void javaSourcesAreFoundInTheRoots() throws IOException {
    touch("app/src/main/java/a/Later.java", "package a;\npublic class Later {}\n");
    touch("app/build/classes/java/main/a/Later.class", "");
    touch("app/src/main/groovy/a/Unbuilt.java", "package a;\npublic class Unbuilt {}\n");
    try (Workspace workspace = workspace()) {
      assertEquals(4, workspace.contexts().get(0).javaClasses(), "Built, Built$Inner, Built$1, Later");
      assertEquals(2, workspace.contexts().get(0).javaFilesNotBuilt, "Fresh, Unbuilt");
    }
  }

  @Test
  void eachSourceSetIsBuiltByItsOwnGradleTask() {
    assertEquals(List.of(":classes", ":app:classes", ":app:testClasses", ":app:integrationTestClasses"),
        List.of(Workspace.classesTask(":", "main"), Workspace.classesTask(":app", "main"), Workspace.classesTask(":app", "test"),
            Workspace.classesTask(":app", "integrationTest")));
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
  void aSourceSetWithAnotherOnesOutputOnItsClasspathAlsoSearchesItsRoots() throws IOException {
    Path app = tmp.resolve("app");
    SourceSetRecord main = sourceSet("main", List.of(app.resolve("src/main/groovy")), List.of(), List.of(app.resolve("build/classes/groovy/main")));
    SourceSetRecord test = sourceSet("test", List.of(app.resolve("src/test/groovy")), List.of(), List.of(app.resolve("build/classes/groovy/test")));
    test.outputsOf.add("main");
    SourceSetRecord integrationTest = sourceSet("integrationTest", List.of(app.resolve("src/integration-test/groovy")), List.of(), List.of());
    integrationTest.outputsOf.addAll(List.of("main", "test"));
    ProjectModel model = new ProjectModel(tmp, List.of(module(":app", app, "2.5.14", List.of(), main, test, integrationTest)), false);
    try (Workspace workspace = new Workspace(model, tmp.resolve("analysis.jar"))) {
      assertEquals(List.of(app.resolve("src/test/groovy"), app.resolve("src/main/groovy")), workspace.contexts().get(1).searchRoots);
      assertEquals(List.of(app.resolve("src/integration-test/groovy"), app.resolve("src/main/groovy"), app.resolve("src/test/groovy")),
          workspace.contexts().get(2).searchRoots, "Grails puts test's output on integrationTest's classpath");
    }
  }

  @Test
  void filesMapToTheirSourceSet() throws IOException {
    try (Workspace workspace = workspace()) {
      assertSame(workspace.contexts().get(0), workspace.contextOf(tmp.resolve("app/grails-app/domain/a/Book.groovy")));
      assertSame(workspace.contexts().get(1), workspace.contextOf(tmp.resolve("app/src/test/groovy/a/BookSpec.groovy")));
      assertSame(workspace.contexts().get(2), workspace.contextOf(tmp.resolve("lib/src/main/groovy/l/Clock.groovy")));
      assertNull(workspace.contextOf(tmp.resolve("app/build.gradle")));
      assertSame(workspace.contexts().get(0), workspace.contextOf(tmp.resolve("app/src/main/groovy/build/Copy.groovy")), "a package named build is source");
      assertSame(workspace.contexts().get(0), workspace.contextOf(tmp.resolve("app/src/main/groovy/node_modules/out/bin/Tool.groovy")));
    }
  }

  /** A source root holding the module's build directory: only that directory is excluded, whatever it is named. */
  @Test
  void onlyTheModulesBuildDirectoryIsNoSource() throws IOException {
    Path app = tmp.resolve("app");
    SourceSetRecord main = sourceSet("main", List.of(app, app.resolve("out/generated")), List.of(), List.of());
    ModuleRecord module = module(":app", app, "2.5.14", List.of(), main);
    module.buildDir = app.resolve("out").toString();
    try (Workspace workspace = new Workspace(new ProjectModel(tmp, List.of(module), false), tmp.resolve("analysis.jar"))) {
      CompileContext context = workspace.contexts().get(0);
      assertEquals(List.of(app), context.ownRoots, "a root in the build directory is no source");
      assertSame(context, workspace.contextOf(app.resolve("build/b/Copy.groovy")), "build is a package here");
      assertNull(workspace.contextOf(app.resolve("out/classes/Copy.groovy")));
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

  /** A jar holding only the named (empty) class file entries: the taglib scan reads names, not bytes. */
  private Path jar(String relative, String... classes) throws IOException {
    Path jar = tmp.resolve(relative);
    Files.createDirectories(jar.getParent());
    try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
      for (String name : classes) {
        out.putNextEntry(new java.util.zip.ZipEntry(name.replace('.', '/') + ".class"));
        out.closeEntry();
      }
    }
    return jar;
  }

  /**
   * A plugin's pages render inside the apps that use it: its pages see its own jars' taglibs, then its runtime-only
   * jars', then those of each module depending on it, directly ({@code :app}) or not ({@code :shell}); never those of
   * a module that does not ({@code :other}).
   */
  @Test
  void aPluginsPagesSeeTheTaglibsOfTheModulesUsingIt() throws IOException {
    Path gsp = jar("repo/gsp.jar", "t.FormTagLib", "t.Helper");
    Path render = jar("repo/render.jar", "t.RenderTagLib");
    Path layout = jar("repo/layout.jar", "t.LayoutTagLib", "t.FormTagLib");
    Path shellJar = jar("repo/shell.jar", "t.ShellTagLib");
    Path otherJar = jar("repo/other.jar", "t.OtherTagLib");
    Path page = touch("plugin/grails-app/views/p/index.gsp", "<g:layoutBody/>");
    ModuleRecord[] modules = new ModuleRecord[4];
    String[][] spec = { { "plugin", "" }, { "app", ":plugin" }, { "shell", ":app" }, { "other", "" } };
    Path[][] classpaths = { { gsp }, { gsp, layout }, { shellJar }, { otherJar } };
    for (int i = 0; i < spec.length; i++) {
      Path dir = tmp.resolve(spec[i][0]);
      Files.createDirectories(dir.resolve("grails-app/controllers"));
      SourceSetRecord main = sourceSet("main", List.of(dir.resolve("grails-app/controllers")), List.of(classpaths[i]), List.of());
      if (i == 0) {
        main.runtimeOnly.add(render.toString());
      }
      modules[i] = module(":" + spec[i][0], dir, "4.0.33", spec[i][1].isEmpty() ? List.of() : List.of(spec[i][1]), main);
    }
    try (Workspace workspace = new Workspace(new ProjectModel(tmp, List.of(modules), false), tmp.resolve("analysis.jar"))) {
      CompileContext plugin = workspace.contextOf(page);
      assertEquals(":plugin main", plugin.name);
      assertEquals(java.util.Map.of("t.FormTagLib", gsp.toString(), "t.RenderTagLib", render.toString(), "t.LayoutTagLib", layout.toString(), "t.ShellTagLib", shellJar.toString()),
          workspace.taglibClasses(plugin));
      assertEquals(List.of("t.FormTagLib", "t.RenderTagLib", "t.LayoutTagLib", "t.ShellTagLib"), List.copyOf(workspace.taglibClasses(plugin).keySet()), "own jars first");
      CompileContext other = workspace.contextOf(touch("other/grails-app/views/o/index.gsp", ""));
      assertEquals(java.util.Map.of("t.OtherTagLib", otherJar.toString()), workspace.taglibClasses(other));
    }
  }

  /**
   * The files below {@code grails-app} that plugin jars supply, from the class path and the runtime-only jars: the
   * precompiled views a jar's {@code gsp/views.properties} lists and the files below its {@code META-INF/assets/}.
   */
  @Test
  void pluginJarsSupplyTheirPrecompiledViewsAndAssets() throws IOException {
    Path views = tmp.resolve("repo/views.jar");
    Files.createDirectories(views.getParent());
    try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(views))) {
      out.putNextEntry(new java.util.zip.ZipEntry("gsp/views.properties"));
      out.write(("#Precompiled views for p\n/WEB-INF/grails-app/views/login/auth.gsp=gsp_p_loginauth_gsp\n"
          + "/WEB-INF/grails-app/views/layouts/_fields/embedded.gsp=gsp_p_layouts__fieldsembedded_gsp\n").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
      out.closeEntry();
      out.putNextEntry(new java.util.zip.ZipEntry("gsp_p_loginauth_gsp.class"));
      out.closeEntry();
    }
    Path assets = tmp.resolve("repo/assets.jar");
    try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(assets))) {
      for (String name : List.of("META-INF/assets/", "META-INF/assets/p/", "META-INF/assets/p/plugged.js", "META-INF/assets/plugged.scss", "META-INF/assets.list")) {
        out.putNextEntry(new java.util.zip.ZipEntry(name));
        out.closeEntry();
      }
    }
    Path dir = tmp.resolve("app");
    Files.createDirectories(dir.resolve("grails-app/controllers"));
    SourceSetRecord main = sourceSet("main", List.of(dir.resolve("grails-app/controllers")), List.of(views), List.of());
    main.runtimeOnly.add(assets.toString());
    try (Workspace workspace = new Workspace(new ProjectModel(tmp, List.of(module(":app", dir, "4.0.33", List.of(), main)), false), tmp.resolve("analysis.jar"))) {
      CompileContext app = workspace.contexts().get(0);
      assertEquals(java.util.Set.of("views/login/auth.gsp", "views/layouts/_fields/embedded.gsp", "assets/p/plugged.js", "assets/plugged.scss"),
          workspace.lookup(app, p -> null).pluginFiles());
    }
  }
}
