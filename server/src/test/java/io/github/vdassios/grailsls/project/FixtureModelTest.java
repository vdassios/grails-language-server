package io.github.vdassios.grailsls.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.project.ModuleRecord.SourceSetRecord;

/** Resolves the real fixture builds under {@code fixtures/} (see {@link Fixtures} for the JDKs they need). */
@Timeout(600)
class FixtureModelTest {
  @TempDir
  Path tmp;

  private ProjectModel resolve(String fixture, String jdkVariable) throws Exception {
    Path jdk = Fixtures.jdk(jdkVariable);
    Path root = Fixtures.copy(Fixtures.ROOT.resolve(fixture), tmp.resolve(fixture));
    GradleBridge bridge = new GradleBridge(tmp.resolve("cache"), jdk, Duration.ofSeconds(540));
    ProjectModel model = bridge.resolve(root, false);
    assertFalse(model.fromCache);
    assertTrue(bridge.resolve(root, false).fromCache);
    return model;
  }

  @Test
  void grails4() throws Exception {
    ProjectModel model = resolve("grails4", "GLS_TEST_JDK11");
    assertEquals(List.of(":", ":app", ":support"), paths(model));

    ModuleRecord app = model.module(":app");
    assertEquals("2.5.14", app.groovyVersion);
    assertEquals(List.of(":support"), app.dependsOn);
    assertEquals(List.of(), app.errors);
    assertEquals(List.of("build"), relative(app, List.of(app.buildDir)));
    SourceSetRecord main = app.sourceSet("main");
    assertEquals(List.of("grails-app/controllers", "grails-app/domain", "grails-app/services", "grails-app/taglib", "src/main/groovy", "src/main/java"),
        relative(app, main.sourceRoots));
    assertEquals(Set.of("Isbn.java", "Slug.java"), names(main.javaFiles), "joint-compiled Java in src/main/java and src/main/groovy");
    assertTrue(relative(app, main.classesDirs).contains("build/classes/groovy/main"));
    assertTrue(names(main.compileClasspath).contains("grails-core-4.1.4.jar"));
    assertTrue(names(main.compileClasspath).contains("groovy-2.5.14.jar"));
    assertNotNull(app.sourceSet("test"));
    assertEquals(List.of("main"), app.sourceSet("test").outputsOf);
    assertEquals(List.of("main", "test"), app.sourceSet("integrationTest").outputsOf, "added by Grails' integration test plugin");
    assertEquals(List.of(), main.outputsOf);

    ModuleRecord support = model.module(":support");
    assertEquals("2.5.14", support.groovyVersion);
    assertEquals(List.of(), support.dependsOn);
    assertEquals(List.of("src/main/groovy", "src/main/java"), relative(support, support.sourceSet("main").sourceRoots));
  }

  @Test
  void grails7() throws Exception {
    ProjectModel model = resolve("grails7", "GLS_TEST_JDK17");
    assertEquals(List.of(":", ":app", ":support"), paths(model));

    ModuleRecord app = model.module(":app");
    assertEquals("4.0.33", app.groovyVersion);
    assertEquals(List.of(":support"), app.dependsOn);
    assertEquals(List.of(), app.errors);
    SourceSetRecord main = app.sourceSet("main");
    assertTrue(relative(app, main.sourceRoots).containsAll(List.of("grails-app/controllers", "grails-app/domain", "grails-app/services")));
    assertTrue(names(main.compileClasspath).contains("grails-core-7.2.4.jar"));
    assertTrue(names(main.compileClasspath).contains("groovy-4.0.33.jar"));
  }

  @Test
  void groovyLibrary() throws Exception {
    ProjectModel model = resolve("groovy-library", "GLS_TEST_JDK17");
    assertEquals(List.of(":"), paths(model));
    ModuleRecord root = model.module(":");
    assertEquals("3.0.25", root.groovyVersion);
    assertEquals(List.of("src/main/groovy", "src/main/java"), relative(root, root.sourceSet("main").sourceRoots));
  }

  /**
   * The real task on the fixture's Gradle: every library of the compile classpaths is counted, none fails, and a
   * library's sources jar is in Gradle's cache afterwards, in a directory beside its jar's. Needs the network the
   * first time, like resolving the fixture itself.
   */
  private void downloadsSources(String fixture, String jdkVariable, String jarPrefix) throws Exception {
    downloadsSources(fixture, jdkVariable, jarPrefix, false);
  }

  /** {@code runtimeOnly}: the jar is one of {@code :app} main's runtime-only jars, whose sources are fetched too. */
  private void downloadsSources(String fixture, String jdkVariable, String jarPrefix, boolean runtimeOnly) throws Exception {
    Path jdk = Fixtures.jdk(jdkVariable);
    Path root = Fixtures.copy(Fixtures.ROOT.resolve(fixture), tmp.resolve(fixture));
    GradleBridge bridge = new GradleBridge(tmp.resolve("cache"), jdk, Duration.ofSeconds(540));
    ProjectModel model = bridge.resolve(root, false);
    SourcesReport report = bridge.downloadSources(root);
    assertEquals(0, report.failed, String.valueOf(report.message));
    assertTrue(report.available > 10, "available: " + report.available);
    List<String> jars = runtimeOnly ? model.module(":app").sourceSet("main").runtimeOnly
        : model.modules.stream().flatMap(m -> m.sourceSets.stream()).flatMap(s -> s.compileClasspath.stream()).collect(Collectors.toList());
    Path jar = jars.stream().map(Path::of).filter(p -> p.getFileName().toString().startsWith(jarPrefix)).findFirst()
        .orElseThrow(() -> new AssertionError(jarPrefix + " not in " + jars));
    String sourcesName = jar.getFileName().toString().replace(".jar", "-sources.jar");
    try (java.util.stream.Stream<Path> hashDirs = java.nio.file.Files.list(jar.getParent().getParent())) {
      assertTrue(hashDirs.anyMatch(dir -> java.nio.file.Files.isRegularFile(dir.resolve(sourcesName))), sourcesName + " beside " + jar);
    }
    assertTrue(bridge.resolve(root, false).fromCache, "the model cache is untouched");
  }

  @Test
  void grails4DownloadsSources() throws Exception {
    downloadsSources("grails4", "GLS_TEST_JDK11", "grails-core-");
  }

  @Test
  void grails7DownloadsSources() throws Exception {
    downloadsSources("grails7", "GLS_TEST_JDK17", "grails-core-");
  }

  /** Grails 7 declares the taglib of {@code g:render} for run time only: the model lists it, and its sources come too. */
  @Test
  void grails7RuntimeOnlyTaglibsAndTheirSources() throws Exception {
    downloadsSources("grails7", "GLS_TEST_JDK17", "grails-web-gsp-taglib-", true);
  }

  private static List<String> paths(ProjectModel model) {
    return model.modules.stream().map(m -> m.path).collect(Collectors.toList());
  }

  private static List<String> relative(ModuleRecord module, List<String> paths) {
    Path dir = Path.of(module.projectDir);
    return paths.stream().map(p -> dir.relativize(Path.of(p)).toString()).sorted().collect(Collectors.toList());
  }

  private static Set<String> names(List<String> paths) {
    return paths.stream().map(p -> Path.of(p).getFileName().toString()).collect(Collectors.toSet());
  }
}
