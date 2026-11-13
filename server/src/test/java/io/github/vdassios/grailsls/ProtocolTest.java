package io.github.vdassios.grailsls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
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
  private final BlockingQueue<PublishDiagnosticsParams> diagnostics = new LinkedBlockingQueue<>();
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

    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(root.resolve("A.groovy").toUri().toString(), "groovy", 1, "class A {\n")));
    assertNull(diagnostics.poll(1, TimeUnit.SECONDS), "no compile, so no project code ran");
  }

  @Test
  void reportsResolvingThenReadyAndReloadsOnRequest() throws Exception {
    Path root = FakeWrapper.write(tmp.resolve("project"), FakeWrapper.WRITES_MODEL).getParent();
    Files.writeString(root.resolve("settings.gradle"), "");
    start(root, trustedOptions());

    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(new StatusParams(StatusParams.INDEXING, "1 module"), next());
    assertEquals(new StatusParams(StatusParams.READY, "1 module"), next());

    server.reloadProject().get(30, TimeUnit.SECONDS);
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
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

  /** A project of one module whose main source set is {@code src} on the test's Groovy. */
  private Path groovyProject() throws Exception {
    Path root = tmp.resolve("project");
    JsonObject main = new JsonObject();
    main.addProperty("name", "main");
    JsonArray roots = new JsonArray();
    roots.add(root.resolve("src").toString());
    main.add("sourceRoots", roots);
    JsonArray classpath = new JsonArray();
    classpath.add(System.getProperty("gls.testGroovy"));
    main.add("compileClasspath", classpath);
    JsonObject module = new JsonObject();
    module.addProperty("path", ":");
    module.addProperty("name", "project");
    module.addProperty("projectDir", root.toString());
    module.addProperty("groovyVersion", "2.5.23");
    JsonArray sourceSets = new JsonArray();
    sourceSets.add(main);
    module.add("sourceSets", sourceSets);
    FakeWrapper.write(root, "printf '%s' '" + module + "' > \"$out/_root.json\"\n");
    Files.writeString(root.resolve("settings.gradle"), "");
    Files.createDirectories(root.resolve("src"));
    return root;
  }

  @Test
  void completionItemsResolveToTheirSignatureAndSignatureHelpAnswers() throws Exception {
    Path root = groovyProject();
    start(root, trustedOptions());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);

    String uri = root.resolve("src/A.groovy").toUri().toString();
    String text = "class A {\n  String name\n  def run() {\n    name.\n  }\n}\n";
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, text)));
    TextDocumentIdentifier document = new TextDocumentIdentifier(uri);

    List<CompletionItem> items = server.getTextDocumentService().completion(new CompletionParams(document, new Position(3, 9))).get(30, TimeUnit.SECONDS).getRight().getItems();
    CompletionItem upper = items.stream().filter(item -> item.getLabel().equals("toUpperCase")).findFirst().orElseThrow();
    assertNull(upper.getDetail(), "left to resolve");
    CompletionItem resolved = server.getTextDocumentService().resolveCompletionItem(upper).get(30, TimeUnit.SECONDS);
    assertTrue(resolved.getDetail().startsWith("public String toUpperCase("), resolved.getDetail());

    String other = root.resolve("src/B.groovy").toUri().toString();
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(other, "groovy", 1, "class B {\n  int count(String what) { 1 }\n  def run() { count('x') }\n}\n")));
    SignatureHelp help = server.getTextDocumentService().signatureHelp(new SignatureHelpParams(new TextDocumentIdentifier(other), new Position(2, 21))).get(30, TimeUnit.SECONDS);
    assertEquals("public int count(String what)", help.getSignatures().get(0).getLabel());
    assertEquals(0, help.getActiveParameter());
  }

  private final class RecordingClient implements GrailsLanguageClient {
    @Override
    public void status(StatusParams params) {
      statuses.add(params);
    }

    @Override
    public void telemetryEvent(Object object) {}

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams params) {
      diagnostics.add(params);
    }

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
