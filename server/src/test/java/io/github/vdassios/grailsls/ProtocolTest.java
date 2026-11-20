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
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
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
  private final List<String> logs = new java.util.concurrent.CopyOnWriteArrayList<>();
  private GrailsServerProxy server;

  /** The server as the client sees it, with the custom request. */
  interface GrailsServerProxy extends LanguageServer {
    @JsonRequest("grails/reloadProject")
    CompletableFuture<Object> reloadProject();

    @JsonRequest("grails/downloadSources")
    CompletableFuture<JsonObject> downloadSources();
  }

  private void start(Path root, JsonObject options) throws Exception {
    start(root, options, proxy -> {});
  }

  /** Starts the server; {@code beforeInitialized} runs between initialize and initialized, before the project resolves. */
  private void start(Path root, JsonObject options, java.util.function.Consumer<GrailsServerProxy> beforeInitialized) throws Exception {
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
    beforeInitialized.accept(server);
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

  /** The jar's manifest gives the version: {@code java -jar} answers initialize with it. */
  @Test
  void theJarReportsItsVersion() throws Exception {
    Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", System.getProperty("gls.serverJar"))
        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try {
      Launcher<GrailsServerProxy> launcher = Launcher.createLauncher(new RecordingClient(), GrailsServerProxy.class, process.getInputStream(), process.getOutputStream());
      launcher.startListening();
      InitializeParams params = new InitializeParams();
      params.setWorkspaceFolders(List.of(new WorkspaceFolder(tmp.toUri().toString(), "project")));
      ServerInfo info = launcher.getRemoteProxy().initialize(params).get(30, TimeUnit.SECONDS).getServerInfo();
      assertEquals("grails-language-server", info.getName());
      assertEquals(System.getProperty("gls.version"), info.getVersion());
      launcher.getRemoteProxy().shutdown().get(10, TimeUnit.SECONDS);
    } finally {
      process.destroyForcibly();
    }
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

  /** Sources are fetched only on request, by a second Gradle run; modules that share a library count it once. */
  @Test
  void downloadsSourcesOnRequestAndReportsTheCounts() throws Exception {
    String sources = "case \"$*\" in *grailsLanguageServerSources*)\n"
        + "printf '%s' '{\"path\":\":\",\"available\":[\"a:b:1\",\"a:c:1\"],\"unavailable\":[\"a:d:1\"],\"failed\":[\"a:e:1\"],\"failure\":\"no route\"}' > \"$out/_root.json\"\n"
        + "printf '%s' '{\"path\":\":app\",\"available\":[\"a:b:1\",\"a:e:1\"],\"unavailable\":[],\"failed\":[\"a:f:1\"],\"failure\":\"no route\"}' > \"$out/app.json\"\n"
        + ";;\n*)\n" + FakeWrapper.WRITES_MODEL + ";;\nesac\n";
    Path root = FakeWrapper.write(tmp.resolve("project"), sources).getParent();
    start(root, trustedOptions());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);
    assertEquals(1, FakeWrapper.runs(root), "nothing fetched before the request");

    JsonObject report = server.downloadSources().get(30, TimeUnit.SECONDS);
    assertEquals("{\"available\":3,\"unavailable\":1,\"failed\":1,\"message\":\"no route\"}", report.toString());
    assertEquals(2, FakeWrapper.runs(root));
    assertNull(statuses.poll(500, TimeUnit.MILLISECONDS), "the project's state does not change");
  }

  @Test
  void anUntrustedWorkspaceDownloadsNothing() throws Exception {
    Path root = FakeWrapper.write(tmp.resolve("project"), FakeWrapper.WRITES_MODEL).getParent();
    start(root, new JsonObject());
    JsonObject report = server.downloadSources().get(10, TimeUnit.SECONDS);
    assertTrue(report.get("message").getAsString().startsWith("workspace not trusted"), report.toString());
    assertEquals(0, FakeWrapper.runs(root));
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

  /** Grails 3 (Groovy 2.4) stops here: the analysis layer needs Groovy 2.5 or later. */
  @Test
  void refusesGroovyOlderThan25() throws Exception {
    Path root = groovyProject(System.getProperty("gls.testGroovy"), "2.4.21");
    start(root, trustedOptions());

    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(new StatusParams(StatusParams.FAILED, "Groovy 2.4.21 (module :) is not supported: the server needs Groovy 2.5 or later (Grails 4 or later)"), next());
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(root.resolve("src/A.groovy").toUri().toString(), "groovy", 1, "class A {\n")));
    assertNull(diagnostics.poll(1, TimeUnit.SECONDS), "nothing compiles");
  }

  /**
   * Groovy 2.5.14's ASM reads class files up to Java 15: on JDK 17 (class file version 61) it cannot read the JDK's own
   * classes, and every compile would fail at line 0. Runs in {@code testJdk17}.
   */
  @Test
  @Tag("jdk17")
  void refusesAGroovyThatCannotReadTheJdksClassFiles() throws Exception {
    Path root = groovyProject(System.getProperty("gls.testGroovyOldAsm"), "2.5.14");
    start(root, trustedOptions());

    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    StatusParams failed = next();
    assertEquals(new StatusParams(StatusParams.FAILED, "Groovy 2.5.14 cannot read the class files of JDK " + System.getProperty("java.version")
        + " (Unsupported class file major version " + (Runtime.version().feature() + 44) + "): run the server on a JDK this Groovy supports, see docs/editors.md"), failed);
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(root.resolve("src/A.groovy").toUri().toString(), "groovy", 1, "class A {\n")));
    assertNull(diagnostics.poll(1, TimeUnit.SECONDS), "nothing compiles");
  }

  /** The same Groovy on the JDK the tests run on (11 in CI, where Groovy 2.5.14 still reads the JDK's class files). */
  @Test
  void acceptsAGroovyThatReadsTheJdksClassFiles() throws Exception {
    Path root = groovyProject(System.getProperty("gls.testGroovyOldAsm"), "2.5.14");
    start(root, trustedOptions());

    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    StatusParams last = next();
    if (Runtime.version().feature() <= 15) {
      assertEquals(new StatusParams(StatusParams.READY, "1 module"), last);
    } else {
      assertEquals(StatusParams.FAILED, last.state, last.toString());
    }
  }

  /** A project of one module whose main source set is {@code src} on the test's Groovy. */
  private Path groovyProject() throws Exception {
    return groovyProject(System.getProperty("gls.testGroovy"), "2.5.23");
  }

  /** A project of one module whose main source set is {@code src} on the Groovy jar given, reported as {@code version}. */
  private Path groovyProject(String groovyJar, String version) throws Exception {
    Path root = tmp.resolve("project");
    JsonObject main = new JsonObject();
    main.addProperty("name", "main");
    JsonArray roots = new JsonArray();
    roots.add(root.resolve("src").toString());
    main.add("sourceRoots", roots);
    JsonArray classpath = new JsonArray();
    classpath.add(groovyJar);
    main.add("compileClasspath", classpath);
    JsonObject module = new JsonObject();
    module.addProperty("path", ":");
    module.addProperty("name", "project");
    module.addProperty("projectDir", root.toString());
    module.addProperty("groovyVersion", version);
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

  @Test
  void workspaceSymbolsComeFromEveryFileOnceIndexed() throws Exception {
    Path root = groovyProject();
    Files.writeString(root.resolve("src/Shelf.groovy"), "package lib\nclass Shelf {\n  int shelfSize() { 1 }\n}\n");
    start(root, trustedOptions());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);

    List<? extends SymbolInformation> found = List.of();
    for (int i = 0; i < 300 && found.size() < 2; i++) {
      found = server.getWorkspaceService().symbol(new WorkspaceSymbolParams("shelf")).get(10, TimeUnit.SECONDS);
      Thread.sleep(100);
    }
    assertEquals(List.of("Class lib.Shelf null 1", "Method shelfSize lib.Shelf 2"),
        found.stream().map(s -> s.getKind() + " " + s.getName() + " " + s.getContainerName() + " " + s.getLocation().getRange().getStart().getLine()).sorted()
            .collect(java.util.stream.Collectors.toList()));
    assertEquals(root.resolve("src/Shelf.groovy").toUri().toString(), found.get(0).getLocation().getUri());
  }

  /** Each startup stage is logged with the time since initialize: how the C6 budget (first diagnostics in 3 s) is read. */
  @Test
  void logsEachStartupStageWithItsTime() throws Exception {
    Path root = groovyProject();
    start(root, trustedOptions());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(root.resolve("src/A.groovy").toUri().toString(), "groovy", 1, "class A {}\n")));
    assertTrue(diagnostics.poll(30, TimeUnit.SECONDS) != null, "no diagnostics");
    List<String> marks = List.of("model resolved", "declarations indexed", "symbols indexed", "first diagnostics");
    for (int i = 0; i < 300 && startupMarks().size() < marks.size(); i++) {
      Thread.sleep(100);
    }
    assertEquals(marks, startupMarks().stream().sorted((a, b) -> marks.indexOf(a) - marks.indexOf(b)).collect(java.util.stream.Collectors.toList()));
    assertTrue(logs.stream().filter(l -> l.startsWith("startup: ")).allMatch(l -> l.matches("startup: [a-z ]+ after \\d+ ms")), logs.toString());
  }

  /**
   * A document open when the project resolves (an editor starts the server for the file it opens) compiles before the
   * symbol index starts, so the index's batches do not hold up its first diagnostics.
   */
  @Test
  void theSymbolIndexStartsAfterTheOpenDocumentsCompile() throws Exception {
    Path root = groovyProject();
    String uri = root.resolve("src/A.groovy").toUri().toString();
    start(root, trustedOptions(), proxy -> proxy.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, "class A {}\n"))));
    assertTrue(diagnostics.poll(30, TimeUnit.SECONDS) != null, "no diagnostics");
    for (int i = 0; i < 300 && !startupMarks().contains("symbols indexed"); i++) {
      Thread.sleep(100);
    }
    List<String> marks = startupMarks();
    assertTrue(marks.contains("symbols indexed"), marks.toString());
    assertTrue(marks.indexOf("first diagnostics") < marks.indexOf("symbols indexed"), marks.toString());
  }

  private List<String> startupMarks() {
    return logs.stream().filter(l -> l.startsWith("startup: ")).map(l -> l.substring("startup: ".length(), l.indexOf(" after ")))
        .collect(java.util.stream.Collectors.toList());
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
    public void logMessage(MessageParams message) {
      logs.add(message.getMessage());
    }
  }
}
