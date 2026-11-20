package io.github.vdassios.grailsls.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        });
    try {
      service.start().get(30, TimeUnit.SECONDS);
    } finally {
      service.stop();
    }
    assertEquals(List.of(StatusParams.RESOLVING, StatusParams.INDEXING, StatusParams.FAILED), statuses.stream().map(s -> s.state).collect(Collectors.toList()));
    assertEquals("java.lang.NoClassDefFoundError: org/example/Missing", statuses.get(2).message);
  }
}
