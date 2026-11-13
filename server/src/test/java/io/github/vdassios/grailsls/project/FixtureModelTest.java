package io.github.vdassios.grailsls.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.project.ModuleRecord.SourceSetRecord;

/**
 * Runs the real fixture builds under {@code fixtures/}. Each needs a JDK its Gradle version supports, given as
 * {@code GLS_TEST_JDK11} or {@code GLS_TEST_JDK17}; without it the test is skipped.
 */
@Timeout(600)
class FixtureModelTest {
  private static final Path FIXTURES = Path.of(System.getProperty("gls.fixtures", "../fixtures"));

  @TempDir
  Path tmp;

  private ProjectModel resolve(String fixture, String jdkVariable) throws Exception {
    String jdk = System.getenv(jdkVariable);
    assumeTrue(jdk != null && !jdk.isEmpty(), jdkVariable + " is not set");
    Path root = copy(FIXTURES.resolve(fixture), tmp.resolve(fixture));
    GradleBridge bridge = new GradleBridge(tmp.resolve("cache"), Path.of(jdk), Duration.ofSeconds(540));
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

  /** The fixture without its build output and Gradle caches. */
  private static Path copy(Path from, Path to) throws IOException {
    try (Stream<Path> paths = Files.walk(from)) {
      for (Path source : (Iterable<Path>) paths::iterator) {
        Path rel = from.relativize(source);
        if (containsSegment(rel, ".gradle") || containsSegment(rel, "build")) {
          continue;
        }
        Path target = to.resolve(rel.toString());
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
      }
    }
    return to;
  }

  private static boolean containsSegment(Path rel, String name) {
    for (Path segment : rel) {
      if (segment.toString().equals(name)) {
        return true;
      }
    }
    return false;
  }
}
