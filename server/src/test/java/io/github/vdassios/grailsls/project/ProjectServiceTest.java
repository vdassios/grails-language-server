package io.github.vdassios.grailsls.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.StatusParams;

@DisabledOnOs(OS.WINDOWS) // the fake wrapper is a shell script
class ProjectServiceTest {
  @TempDir
  Path tmp;

  /** An Error while indexing (a class the server needs fails to link, a stack overflow) ends the run as failed. */
  @Test
  void anErrorWhileIndexingReportsFailed() throws Exception {
    Path root = tmp.resolve("project");
    FakeWrapper.write(root, FakeWrapper.WRITES_MODEL);
    Files.writeString(root.resolve("settings.gradle"), "");
    List<StatusParams> statuses = new CopyOnWriteArrayList<>();
    ProjectService service = new ProjectService(new GradleBridge(tmp.resolve("cache"), GradleBridge.defaultJavaHome(), Duration.ofSeconds(30)), root, true,
        statuses::add, model -> {
          throw new NoClassDefFoundError("org/example/Missing");
        }, notice -> {});
    try {
      service.start().get(30, TimeUnit.SECONDS);
    } finally {
      service.stop();
    }
    assertEquals(List.of(StatusParams.RESOLVING, StatusParams.INDEXING, StatusParams.FAILED), statuses.stream().map(s -> s.state).collect(Collectors.toList()));
    assertEquals("java.lang.NoClassDefFoundError: org/example/Missing", statuses.get(2).message);
  }

  /**
   * A changed build file is followed by one message that the model is stale, never by a Gradle run: the key is
   * computed again and compared, and an unchanged key (an edit undone, a file touched) says nothing.
   */
  @Test
  void aBuildFileChangeShowsOneStaleNoticeAndRunsNoGradle() throws Exception {
    Path root = tmp.resolve("project");
    FakeWrapper.write(root, FakeWrapper.WRITES_MODEL);
    Files.writeString(root.resolve("settings.gradle"), "");
    java.util.concurrent.BlockingQueue<String> notices = new java.util.concurrent.LinkedBlockingQueue<>();
    ProjectService service = new ProjectService(new GradleBridge(tmp.resolve("cache"), GradleBridge.defaultJavaHome(), Duration.ofSeconds(30)), root, true,
        status -> {}, model -> "", notices::add);
    try {
      service.start().get(30, TimeUnit.SECONDS);
      service.buildFilesChanged();
      assertNull(notices.poll(500, TimeUnit.MILLISECONDS), "nothing changed");

      Files.writeString(root.resolve("settings.gradle"), "include 'app'\n");
      service.buildFilesChanged();
      String notice = notices.poll(10, TimeUnit.SECONDS);
      assertTrue(notice != null && notice.contains("grails/reloadProject"), String.valueOf(notice));
      Files.writeString(root.resolve("build.gradle"), "");
      service.buildFilesChanged();
      assertNull(notices.poll(500, TimeUnit.MILLISECONDS), "once per model");
      assertEquals(1, FakeWrapper.runs(root), "Gradle ran once, to resolve");

      service.reload().get(30, TimeUnit.SECONDS);
      Files.writeString(root.resolve("build.gradle"), "// changed\n");
      service.buildFilesChanged();
      assertTrue(notices.poll(10, TimeUnit.SECONDS) != null, "a new model is checked again");
    } finally {
      service.stop();
    }
  }

  /**
   * {@code grails/reloadProject} is always answered, also when a later reload cancels it before it started: two reloads
   * queued behind a sources download, then three in a row.
   */
  @Test
  void everyReloadIsAnsweredWhenALaterOneCancelsIt() throws Exception {
    Path root = tmp.resolve("project");
    // the download waits until the file go exists; resolving writes a model at once
    FakeWrapper.write(root, "case \"$*\" in *grailsLanguageServerSources*)\n"
        + "while [ ! -f \"$(dirname \"$0\")/go\" ]; do sleep 0.05; done\n;;\n*)\n" + FakeWrapper.WRITES_MODEL + ";;\nesac\n");
    Files.writeString(root.resolve("settings.gradle"), "");
    ProjectService service = new ProjectService(new GradleBridge(tmp.resolve("cache"), GradleBridge.defaultJavaHome(), Duration.ofSeconds(30)), root, true,
        status -> {}, model -> "", notice -> {});
    try {
      service.start().get(30, TimeUnit.SECONDS);
      CompletableFuture<SourcesReport> download = service.downloadSources();
      CompletableFuture<Void> first = service.reload();
      CompletableFuture<Void> second = service.reload();
      Files.writeString(root.resolve("go"), "");
      download.get(30, TimeUnit.SECONDS);
      CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

      CompletableFuture.allOf(service.reload(), service.reload(), service.reload()).get(30, TimeUnit.SECONDS);
    } finally {
      service.stop();
    }
  }

  /**
   * The exit does not wait for the project's thread: {@code stop} returns at once even while the resolution is in work
   * that ignores the interrupt (indexing), and the Gradle process it started is killed by the time it returns.
   */
  @Test
  void stopDoesNotWaitForTheProjectThread() throws Exception {
    Path root = tmp.resolve("project");
    FakeWrapper.write(root, FakeWrapper.WRITES_MODEL);
    Files.writeString(root.resolve("settings.gradle"), "");
    CountDownLatch indexing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ProjectService service = new ProjectService(new GradleBridge(tmp.resolve("cache"), GradleBridge.defaultJavaHome(), Duration.ofSeconds(30)), root, true,
        status -> {}, model -> {
          indexing.countDown();
          while (true) {
            try {
              release.await();
              return "";
            } catch (InterruptedException e) {
              // indexing does not stop on an interrupt
            }
          }
        }, notice -> {});
    try {
      service.start();
      assertTrue(indexing.await(30, TimeUnit.SECONDS), "indexing never started");
      long start = System.nanoTime();
      service.stop();
      long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      assertTrue(millis < 1000, "stop took " + millis + " ms");
    } finally {
      release.countDown();
    }
  }

  /**
   * Requests are answered when {@code stop} runs: the running one, one still queued behind it, and those made after
   * it. The Gradle process is killed by the time {@code stop} returns.
   */
  @Test
  void stopAnswersEveryRequestAndKillsGradle() throws Exception {
    Path root = tmp.resolve("project");
    FakeWrapper.write(root, "echo $$ > \"$(dirname \"$0\")/wrapper.pid\"\nwhile true; do sleep 0.05; done\n");
    Files.writeString(root.resolve("settings.gradle"), "");
    ProjectService service = new ProjectService(new GradleBridge(tmp.resolve("cache"), GradleBridge.defaultJavaHome(), Duration.ofSeconds(60)), root, true,
        status -> {}, model -> "", notice -> {});
    CompletableFuture<Void> start = service.start();
    Path pid = root.resolve("wrapper.pid");
    for (int i = 0; i < 300 && (!Files.exists(pid) || Files.readString(pid).isBlank()); i++) {
      Thread.sleep(50);
    }
    ProcessHandle gradle = ProcessHandle.of(Long.parseLong(Files.readString(pid).trim())).orElseThrow();
    CompletableFuture<SourcesReport> queued = service.downloadSources();

    service.stop();
    gradle.onExit().get(2, TimeUnit.SECONDS);
    start.get(2, TimeUnit.SECONDS);
    assertEquals("cancelled", queued.get(2, TimeUnit.SECONDS).message);
    service.reload().get(2, TimeUnit.SECONDS);
    assertEquals("cancelled", service.downloadSources().get(2, TimeUnit.SECONDS).message);
    assertEquals(1, FakeWrapper.runs(root), "no Gradle after stop");
  }

  /**
   * A module whose Groovy jar Gradle could not resolve is failed with Gradle's reason, not ready with nothing to
   * compile; a module that never asked for Groovy, or whose Groovy resolved, is let through.
   */
  @Test
  void aModuleWhoseGroovyDidNotResolveFails() {
    ModuleRecord javaOnly = module(":java", null, "main: Could not resolve com.example:missing:1.0. Could not find com.example:missing:1.0.");
    ModuleRecord resolved = module(":groovy", "5.1.3", "test: Could not resolve org.apache.groovy:groovy-json:6.0.0. Could not find it.");
    assertNull(ProjectService.unresolvedGroovy(new ProjectModel(tmp, List.of(javaOnly, resolved), false)));

    String reason = "main: Could not resolve org.apache.groovy:groovy:6.0.0. Incompatible because this component declares a component, compatible with Java 17"
        + " and the consumer needed a component, compatible with Java 11";
    ModuleRecord unresolved = module(":app", null, reason);
    assertEquals("Gradle could not resolve Groovy for module :app (" + reason + "): start the server on the JDK the project builds with (see docs/editors.md)",
        ProjectService.unresolvedGroovy(new ProjectModel(tmp, List.of(javaOnly, resolved, unresolved), false)));

    String offline = "main: Could not resolve org.codehaus.groovy:groovy-all:2.5.23. Could not GET 'https://repo.maven.apache.org/'.";
    assertEquals("Gradle could not resolve Groovy for module :app (" + offline + ")",
        ProjectService.unresolvedGroovy(new ProjectModel(tmp, List.of(module(":app", null, offline)), false)), "no JDK advice for another reason");
  }

  private static ModuleRecord module(String path, String groovyVersion, String error) {
    ModuleRecord module = new ModuleRecord();
    module.path = path;
    module.groovyVersion = groovyVersion;
    module.errors.add(error);
    return module;
  }
}
