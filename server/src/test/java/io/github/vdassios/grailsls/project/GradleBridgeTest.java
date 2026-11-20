package io.github.vdassios.grailsls.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS) // the fake wrapper is a shell script
class GradleBridgeTest {
  @TempDir
  Path tmp;

  private GradleBridge bridge(Duration timeout) {
    return new GradleBridge(tmp.resolve("cache"), GradleBridge.defaultJavaHome(), timeout);
  }

  private Path project(String body) throws Exception {
    Path root = tmp.resolve("project");
    FakeWrapper.write(root, body);
    Files.writeString(root.resolve("build.gradle"), "// v1\n");
    return root;
  }

  @Test
  void cachesTheModelUntilABuildFileChanges() throws Exception {
    Path root = project(FakeWrapper.WRITES_MODEL);
    GradleBridge bridge = bridge(Duration.ofSeconds(30));

    ProjectModel first = bridge.resolve(root, false);
    assertFalse(first.fromCache);
    assertEquals(":", first.modules.get(0).path);
    assertTrue(bridge.resolve(root, false).fromCache);
    assertEquals(1, FakeWrapper.runs(root));

    Files.writeString(root.resolve("build.gradle"), "// v2\n");
    assertFalse(bridge.resolve(root, false).fromCache);
    assertEquals(2, FakeWrapper.runs(root));

    assertFalse(bridge.resolve(root, true).fromCache, "force ignores the cache");
    assertEquals(3, FakeWrapper.runs(root));
  }

  @Test
  void runsGradleOnTheConfiguredJdk() throws Exception {
    Path root = project(FakeWrapper.WRITES_MODEL);
    bridge(Duration.ofSeconds(30)).resolve(root, false);
    assertEquals(List.of("JAVA_HOME=" + GradleBridge.defaultJavaHome().toRealPath()), Files.readAllLines(root.resolve("runs.txt")));
  }

  @Test
  void reportsGradlesReasonAndDoesNotCacheFailures() throws Exception {
    Path root = project("printf '\\n* What went wrong:\\nCould not resolve all files.\\n  > boom\\n\\n* Try:\\n'\nexit 1\n");
    GradleBridge bridge = bridge(Duration.ofSeconds(30));

    BridgeException e = assertThrows(BridgeException.class, () -> bridge.resolve(root, false));
    assertTrue(e.getMessage().startsWith("Gradle failed on JDK " + System.getProperty("java.version") + ": Could not resolve all files. > boom (log: "), e.getMessage());
    assertFalse(e.getMessage().contains("docs/editors.md"), "no JDK mismatch in the output");
    assertThrows(BridgeException.class, () -> bridge.resolve(root, false));
    assertEquals(2, FakeWrapper.runs(root));
  }

  /** The outputs measured: Gradle 6.9 compiling a build script on JDK 21, and a Grails 7 build on JDK 11. */
  @Test
  void pointsAtTheJdkWhenTheOutputShowsAMismatch() throws Exception {
    List<String> outputs = List.of(
        "* What went wrong:\\nCould not compile build file 'build.gradle'.\\n> startup failed:\\n  General error during semantic analysis: Unsupported class file major version 65\\n",
        "* What went wrong:\\nA problem occurred configuring root project 'app'.\\n> Could not resolve all artifacts for configuration 'classpath'.\\n"
            + "      > Dependency requires at least JVM runtime version 17. This build uses a Java 11 JVM.\\n");
    for (String output : outputs) {
      Path root = project("printf \"" + output + "\"\nexit 1\n");
      Files.createDirectories(root.resolve("gradle/wrapper"));
      Files.writeString(root.resolve("gradle/wrapper/gradle-wrapper.properties"), "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-all.zip\n");
      BridgeException e = assertThrows(BridgeException.class, () -> bridge(Duration.ofSeconds(30)).resolve(root, false));
      String jdk = System.getProperty("java.version");
      assertTrue(e.getMessage().startsWith("Gradle 8.14.3 failed on JDK " + jdk + ": "), e.getMessage());
      assertTrue(e.getMessage().endsWith(". The build does not run on JDK " + jdk + ": start the server on the JDK the project builds with (see docs/editors.md)"),
          e.getMessage());
    }
  }

  @Test
  void failsWhenGradleWritesNoModel() throws Exception {
    Path root = project("exit 0\n");
    BridgeException e = assertThrows(BridgeException.class, () -> bridge(Duration.ofSeconds(30)).resolve(root, false));
    assertTrue(e.getMessage().startsWith("Gradle wrote no project model"), e.getMessage());
  }

  @Test
  void killsGradleAfterTheTimeout() throws Exception {
    Path root = project("echo $$ > \"$(dirname \"$0\")/pid\"\nexec sleep 60\n");
    long start = System.nanoTime();

    BridgeException e = assertThrows(BridgeException.class, () -> bridge(Duration.ofSeconds(1)).resolve(root, false));

    assertTrue(e.getMessage().startsWith("Gradle did not finish within 1 s"), e.getMessage());
    assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 10);
    long pid = Long.parseLong(Files.readString(root.resolve("pid")).trim());
    Thread.sleep(200);
    assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the Gradle process is killed");
  }

  @Test
  void refusesAWrapperInAParentDirectory() throws Exception {
    // Trusting proj/app must not run proj/gradlew: the wrapper is taken only from the workspace folder.
    Path root = project(FakeWrapper.WRITES_MODEL);
    Path module = Files.createDirectories(root.resolve("app"));
    BridgeException e = assertThrows(BridgeException.class, () -> bridge(Duration.ofSeconds(30)).resolve(module, false));
    assertTrue(e.getMessage().contains("open " + root.toRealPath() + " as the workspace"), e.getMessage());
    assertEquals(0, FakeWrapper.runs(root));
  }

  @Test
  void namesAWrapperThatIsNotExecutable() throws Exception {
    Path root = project(FakeWrapper.WRITES_MODEL);
    Files.setPosixFilePermissions(root.resolve("gradlew"), PosixFilePermissions.fromString("rw-r--r--"));
    BridgeException e = assertThrows(BridgeException.class, () -> bridge(Duration.ofSeconds(30)).resolve(root, false));
    assertEquals("cannot start gradlew: " + root.toRealPath().resolve("gradlew") + " is not executable (chmod +x gradlew)", e.getMessage());
  }

  @Test
  void failsWithoutAWrapper() throws Exception {
    Path root = Files.createDirectories(tmp.resolve("bare"));
    BridgeException e = assertThrows(BridgeException.class, () -> GradleBridge.findBuildRoot(root));
    assertEquals("no Gradle wrapper (gradlew) in " + root, e.getMessage());
  }

  @Test
  void buildFilesSkipBuildOutputAndToolDirectories() throws Exception {
    Path root = project(FakeWrapper.WRITES_MODEL);
    for (String file : List.of("settings.gradle", "gradle.properties", "app/build.gradle.kts", "gradle/libs.versions.toml",
        "gradle/wrapper/gradle-wrapper.properties", "buildSrc/src/main/groovy/Conventions.groovy", "app/build/tmp/x.gradle",
        "node_modules/a/build.gradle", ".gradle/b.gradle", "app/src/main/groovy/A.groovy")) {
      Path path = root.resolve(file);
      Files.createDirectories(path.getParent());
      Files.writeString(path, "");
    }
    List<String> found = GradleBridge.buildFiles(root).stream().map(p -> root.relativize(p).toString()).sorted().collect(java.util.stream.Collectors.toList());
    assertEquals(List.of("app/build.gradle.kts", "build.gradle", "buildSrc/src/main/groovy/Conventions.groovy", "gradle.properties",
        "gradle/libs.versions.toml", "gradle/wrapper/gradle-wrapper.properties", "settings.gradle"), found);
  }
}
