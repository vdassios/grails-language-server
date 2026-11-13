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
    SourceSetRecord main = app.sourceSet("main");
    assertEquals(List.of("grails-app/controllers", "grails-app/domain", "grails-app/services", "src/main/groovy", "src/main/java"), relative(app, main.sourceRoots));
    assertEquals(Set.of("Isbn.java", "Slug.java"), names(main.javaFiles), "joint-compiled Java in src/main/java and src/main/groovy");
    assertTrue(relative(app, main.classesDirs).contains("build/classes/groovy/main"));
    assertTrue(names(main.compileClasspath).contains("grails-core-4.1.4.jar"));
    assertTrue(names(main.compileClasspath).contains("groovy-2.5.14.jar"));
    assertNotNull(app.sourceSet("test"));

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
