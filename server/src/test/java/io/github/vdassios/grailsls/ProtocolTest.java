package io.github.vdassios.grailsls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.project.FakeWrapper;

/** Drives the server in-process over JSON-RPC, as an editor would. */
@DisabledOnOs(OS.WINDOWS) // the fake wrapper is a shell script
class ProtocolTest {
  @TempDir
  Path tmp;

  private final BlockingQueue<StatusParams> statuses = new LinkedBlockingQueue<>();
  private GrailsServerProxy server;

  /** The server as the client sees it, with the custom request. */
  interface GrailsServerProxy extends LanguageServer {
    @JsonRequest("grails/reloadProject")
    CompletableFuture<Object> reloadProject();
  }

  private void start(Path root, JsonObject options) throws Exception {
    PipedInputStream serverIn = new PipedInputStream();
    PipedOutputStream clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream();
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    GrailsLanguageServer impl = new GrailsLanguageServer();
    Launcher<GrailsLanguageClient> serverLauncher = Launcher.createLauncher(impl, GrailsLanguageClient.class, serverIn, serverOut);
    impl.connect(serverLauncher.getRemoteProxy());
    serverLauncher.startListening();

    Launcher<GrailsServerProxy> clientLauncher = Launcher.createLauncher(new RecordingClient(), GrailsServerProxy.class, clientIn, clientOut);
    clientLauncher.startListening();
    server = clientLauncher.getRemoteProxy();

    InitializeParams params = new InitializeParams();
    params.setWorkspaceFolders(List.of(new WorkspaceFolder(root.toUri().toString(), "project")));
    params.setInitializationOptions(options);
    server.initialize(params).get(10, TimeUnit.SECONDS);
    server.initialized(new InitializedParams());
  }

  @AfterEach
  void shutdown() throws Exception {
    if (server != null) {
      server.shutdown().get(10, TimeUnit.SECONDS);
    }
  }

  private JsonObject trustedOptions() {
    JsonObject options = new JsonObject();
    options.addProperty("trusted", true);
    options.addProperty("cacheDir", tmp.resolve("cache").toString());
    return options;
  }

  private StatusParams next() throws InterruptedException {
    StatusParams status = statuses.poll(30, TimeUnit.SECONDS);
    assertTrue(status != null, "no grails/status within 30 s");
    return status;
  }

  @Test
  void untrustedWorkspaceRunsNothing() throws Exception {
    Path root = FakeWrapper.write(tmp.resolve("project"), FakeWrapper.WRITES_MODEL).getParent();
    start(root, new JsonObject());

    StatusParams status = next();
    assertEquals(StatusParams.FAILED, status.state);
    assertTrue(status.message.startsWith("workspace not trusted"), status.message);
    server.reloadProject().get(10, TimeUnit.SECONDS);
    assertEquals(StatusParams.FAILED, next().state);
    assertEquals(0, FakeWrapper.runs(root));
  }

  @Test
  void reportsResolvingThenReadyAndReloadsOnRequest() throws Exception {
    Path root = FakeWrapper.write(tmp.resolve("project"), FakeWrapper.WRITES_MODEL).getParent();
    Files.writeString(root.resolve("settings.gradle"), "");
    start(root, trustedOptions());

    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(new StatusParams(StatusParams.READY, "1 module"), next());

    server.reloadProject().get(30, TimeUnit.SECONDS);
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(new StatusParams(StatusParams.READY, "1 module"), next(), "reload ignores the cache");
    assertEquals(2, FakeWrapper.runs(root));
  }

  @Test
  void reportsTheFailureReason() throws Exception {
    Path root = FakeWrapper.write(tmp.resolve("project"), "printf '* What went wrong:\\nboom\\n'\nexit 1\n").getParent();
    start(root, trustedOptions());

    assertEquals(StatusParams.RESOLVING, next().state);
    StatusParams failed = next();
    assertEquals(StatusParams.FAILED, failed.state);
    assertTrue(failed.message.startsWith("Gradle failed: boom (log: "), failed.message);
  }

  private final class RecordingClient implements GrailsLanguageClient {
    @Override
    public void status(StatusParams params) {
      statuses.add(params);
    }

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {}

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
