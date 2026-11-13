package io.github.vdassios.grailsls.project;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The projects under {@code fixtures/}, copied without build output. Each needs a JDK its Gradle version supports,
 * given as {@code GLS_TEST_JDK11} or {@code GLS_TEST_JDK17}; without it the calling test is skipped.
 */
public final class Fixtures {
  public static final Path ROOT = Path.of(System.getProperty("gls.fixtures", "../fixtures"));
  private static final Map<String, ProjectModel> BUILT = new HashMap<>();

  private Fixtures() {}

  /** The JDK in {@code variable}; skips the test when it is not set. */
  public static Path jdk(String variable) {
    String jdk = System.getenv(variable);
    assumeTrue(jdk != null && !jdk.isEmpty(), variable + " is not set");
    return Path.of(jdk);
  }

  /**
   * A copy of the fixture, built with {@code ./gradlew classes} (so joint-compiled Java has class files), and its
   * model. Built once per test JVM and shared: callers must not change it.
   */
  public static synchronized ProjectModel built(String fixture, String jdkVariable) throws Exception {
    Path jdk = jdk(jdkVariable);
    ProjectModel model = BUILT.get(fixture);
    if (model != null) {
      return model;
    }
    Path work = Files.createTempDirectory("gls-fixture-");
    Path root = copy(ROOT.resolve(fixture), work.resolve(fixture));
    ProcessBuilder builder = new ProcessBuilder(root.resolve("gradlew").toString(), "classes", "--console=plain").directory(root.toFile()).redirectErrorStream(true)
        .redirectOutput(work.resolve("classes.log").toFile());
    builder.environment().put("JAVA_HOME", jdk.toString());
    Process process = builder.start();
    if (!process.waitFor(540, TimeUnit.SECONDS) || process.exitValue() != 0) {
      process.destroyForcibly();
      throw new IllegalStateException("./gradlew classes failed in " + root + ", see " + work.resolve("classes.log"));
    }
    model = new GradleBridge(work.resolve("cache"), jdk, Duration.ofSeconds(540)).resolve(root, false);
    BUILT.put(fixture, model);
    return model;
  }

  /** The fixture without its build output and Gradle caches. */
  public static Path copy(Path from, Path to) throws IOException {
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
