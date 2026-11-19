package io.github.vdassios.grailsls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.google.gson.JsonObject;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.project.FakeWrapper;

/**
 * The server exits when its client goes away without the {@code exit} notification (an editor that quits without
 * waiting), and stops its Gradle run first. The exit is recorded instead of ending the test JVM.
 */
@DisabledOnOs(OS.WINDOWS) // the fake wrapper is a shell script
class LifecycleTest {
  @TempDir
  Path tmp;

  private final CompletableFuture<Integer> exitCode = new CompletableFuture<>();
  private final BlockingQueue<StatusParams> statuses = new LinkedBlockingQueue<>();
  private final List<Process> started = new ArrayList<>();
  private PipedOutputStream clientOut;
  private LanguageServer server;

  /** A Gradle run that does not end by itself; it records its own pid and that of a child (as a daemon would be). */
  private Path hangingProject() throws IOException {
    Path root = tmp.resolve("project");
    FakeWrapper.write(root, "echo $$ > \"$(dirname \"$0\")/wrapper.pid\"\nsleep 120 &\necho $! > \"$(dirname \"$0\")/child.pid\"\nwait\n");
    Files.writeString(root.resolve("settings.gradle"), "");
    return root;
  }

  /** Starts the server as {@code main} does, on piped streams, and initializes it in {@code root}. */
  private void start(Path root, Long processId) throws Exception {
    PipedInputStream serverIn = new PipedInputStream();
    clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream();
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    GrailsLanguageServer impl = new GrailsLanguageServer(exitCode::complete);
    Thread serving = new Thread(() -> GrailsLanguageServer.serve(impl, serverIn, serverOut), "test-server");
    serving.setDaemon(true);
    serving.start();

    Launcher<LanguageServer> clientLauncher = Launcher.createLauncher(new StatusClient(), LanguageServer.class, clientIn, clientOut);
    clientLauncher.startListening();
    server = clientLauncher.getRemoteProxy();

    InitializeParams params = new InitializeParams();
    params.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(), "project")));
    JsonObject options = new JsonObject();
    options.addProperty("trusted", true);
    options.addProperty("cacheDir", tmp.resolve("cache").toString());
    params.setInitializationOptions(options);
    if (processId != null) {
      params.setProcessId(processId.intValue());
    }
    server.initialize(params).get(10, TimeUnit.SECONDS);
    server.initialized(new InitializedParams());
  }

  @AfterEach
  void killLeftovers() throws IOException {
    for (Process process : started) {
      process.destroyForcibly();
    }
    for (String name : List.of("wrapper.pid", "child.pid")) {
      Path file = tmp.resolve("project").resolve(name);
      if (Files.exists(file)) {
        ProcessHandle.of(Long.parseLong(Files.readString(file).trim())).ifPresent(ProcessHandle::destroyForcibly);
      }
    }
  }

  /** Waits until the fake Gradle has started and written both pids. */
  private List<ProcessHandle> gradleRunning(Path root) throws Exception {
    assertEquals(StatusParams.RESOLVING, statuses.poll(30, TimeUnit.SECONDS).state);
    List<ProcessHandle> processes = new ArrayList<>();
    for (String name : List.of("wrapper.pid", "child.pid")) {
      Path file = root.resolve(name);
      for (int i = 0; i < 300 && (!Files.exists(file) || Files.readString(file).isBlank()); i++) {
        Thread.sleep(50);
      }
      ProcessHandle process = ProcessHandle.of(Long.parseLong(Files.readString(file).trim())).orElseThrow();
      processes.add(process);
    }
    return processes;
  }

  /** Killed by the time the server exits; the system may need a moment to reap them. */
  private static void assertEnded(List<ProcessHandle> processes) throws Exception {
    for (ProcessHandle process : processes) {
      process.onExit().get(5, TimeUnit.SECONDS);
      assertFalse(process.isAlive(), "Gradle process " + process.pid() + " still running");
    }
  }

  @Test
  void theEndOfInputEndsTheServerAndItsGradleRunWithCode1() throws Exception {
    Path root = hangingProject();
    start(root, null);
    List<ProcessHandle> gradle = gradleRunning(root);

    clientOut.close();
    assertEquals(1, exitCode.get(10, TimeUnit.SECONDS), "no shutdown request came first");
    assertEnded(gradle);
  }

  @Test
  void shutdownThenTheEndOfInputExitsWithCode0() throws Exception {
    Path root = hangingProject();
    start(root, null);
    List<ProcessHandle> gradle = gradleRunning(root);

    server.shutdown().get(10, TimeUnit.SECONDS);
    assertEnded(gradle);
    assertFalse(exitCode.isDone(), "shutdown alone does not exit");
    clientOut.close();
    assertEquals(0, exitCode.get(10, TimeUnit.SECONDS));
  }

  /** The client's input stays open, as when another process inherited the pipe; its process ending is enough. */
  @Test
  void theClientProcessEndingEndsTheServer() throws Exception {
    Process client = new ProcessBuilder("sleep", "120").start();
    started.add(client);
    Path root = hangingProject();
    start(root, client.pid());
    List<ProcessHandle> gradle = gradleRunning(root);

    assertFalse(exitCode.isDone());
    client.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    assertEquals(1, exitCode.get(10, TimeUnit.SECONDS));
    assertEnded(gradle);
  }

  private final class StatusClient implements GrailsLanguageClient {
    @Override
    public void status(StatusParams params) {
      statuses.add(params);
    }

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {}

    @Override
    public void showMessage(MessageParams messageParams) {}

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {}
  }
}
