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
}
