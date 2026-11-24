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
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.CompletionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CompletionTriggerKind;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesCapabilities;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceClientCapabilities;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.compile.Workspace;
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
  private GrailsLanguageServer impl;

  /** The server as the client sees it, with the custom request. */
  interface GrailsServerProxy extends LanguageServer {
    @JsonRequest("grails/reloadProject")
    CompletableFuture<Object> reloadProject();

    @JsonRequest("grails/downloadSources")
    CompletableFuture<JsonObject> downloadSources();

    @JsonRequest("grails/reindex")
    CompletableFuture<Object> reindex();
  }

  /** Whether the client offers to watch files for the server (Neovim does on macOS, not on Linux). */
  private boolean clientWatchesFiles;
  private final BlockingQueue<List<String>> registeredGlobs = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> shown = new LinkedBlockingQueue<>();

  private void start(Path root, JsonObject options) throws Exception {
    start(root, options, proxy -> {});
  }

  private void start(Path root, JsonObject options, java.util.function.Consumer<GrailsServerProxy> beforeInitialized) throws Exception {
    start(root.toUri().toString(), options, beforeInitialized);
  }

  /** Starts the server; {@code beforeInitialized} runs between initialize and initialized, before the project resolves. */
  private void start(String rootUri, JsonObject options, java.util.function.Consumer<GrailsServerProxy> beforeInitialized) throws Exception {
    PipedInputStream serverIn = new PipedInputStream();
    PipedOutputStream clientOut = new PipedOutputStream(serverIn);
    PipedInputStream clientIn = new PipedInputStream();
    PipedOutputStream serverOut = new PipedOutputStream(clientIn);

    impl = new GrailsLanguageServer();
    Launcher<GrailsLanguageClient> serverLauncher = Launcher.createLauncher(impl, GrailsLanguageClient.class, serverIn, serverOut);
    impl.connect(serverLauncher.getRemoteProxy());
    serverLauncher.startListening();

    Launcher<GrailsServerProxy> clientLauncher = Launcher.createLauncher(new RecordingClient(), GrailsServerProxy.class, clientIn, clientOut);
    clientLauncher.startListening();
    server = clientLauncher.getRemoteProxy();

    InitializeParams params = new InitializeParams();
    params.setWorkspaceFolders(List.of(new WorkspaceFolder(rootUri, "project")));
    params.setInitializationOptions(options);
    if (clientWatchesFiles) {
      WorkspaceClientCapabilities workspace = new WorkspaceClientCapabilities();
      workspace.setDidChangeWatchedFiles(new DidChangeWatchedFilesCapabilities(true));
      ClientCapabilities capabilities = new ClientCapabilities();
      capabilities.setWorkspace(workspace);
      params.setCapabilities(capabilities);
    }
    initializeResult = server.initialize(params).get(10, TimeUnit.SECONDS);
    beforeInitialized.accept(server);
    server.initialized(new InitializedParams());
  }

  private InitializeResult initializeResult;

  @AfterEach
  void shutdown() throws Exception {
    if (server != null) {
      Workspace left = impl.compiles() == null ? null : impl.compiles().workspace();
      server.shutdown().get(10, TimeUnit.SECONDS);
      if (left != null) {
        left.close(); // the server leaves it to the end of the process; this JVM goes on
      }
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

  /** Grails 3 (Groovy 2.4) stops here: the analysis layer needs Groovy 2.5 or later. */
  /** {@code gradle.timeoutSeconds} sent as a string that is no number: initialize succeeds and the project resolves. */
  @Test
  void aTimeoutThatIsNoNumberFallsBackToTheDefault() throws Exception {
    Path root = FakeWrapper.write(tmp.resolve("project"), FakeWrapper.WRITES_MODEL).getParent();
    Files.writeString(root.resolve("settings.gradle"), "");
    JsonObject options = trustedOptions();
    JsonObject gradle = new JsonObject();
    gradle.addProperty("timeoutSeconds", "five minutes");
    options.add("gradle", gradle);
    start(root, options);
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);
    assertTrue(logs.contains("gradle.timeoutSeconds is not a positive whole number (\"five minutes\"): using 300"), logs.toString());
  }

  @Test
  void theTimeoutIsANumberOrANumericString() {
    List<String> logged = new java.util.ArrayList<>();
    assertEquals(120, GrailsLanguageServer.timeoutSeconds(com.google.gson.JsonParser.parseString("120"), logged::add));
    assertEquals(120, GrailsLanguageServer.timeoutSeconds(com.google.gson.JsonParser.parseString("\" 120 \""), logged::add));
    assertEquals(List.of(), logged);
    for (String bad : List.of("\"abc\"", "0", "-5", "1.5", "\"\"", "{}", "[1]", "true", "99999999999")) {
      assertEquals(GrailsLanguageServer.DEFAULT_TIMEOUT_SECONDS, GrailsLanguageServer.timeoutSeconds(com.google.gson.JsonParser.parseString(bad), logged::add), bad);
    }
    assertEquals(9, logged.size(), logged.toString());
    assertEquals(GrailsLanguageServer.DEFAULT_TIMEOUT_SECONDS, GrailsLanguageServer.timeoutSeconds(null, logged::add), "not given");
    assertEquals(9, logged.size(), "not given is no mistake");
  }

  @Test
  void theCacheDirIsAPathStringOrTheDefault() {
    Path fallback = io.github.vdassios.grailsls.project.GradleBridge.defaultCacheDir();
    List<String> logged = new java.util.ArrayList<>();
    assertEquals(Path.of("/tmp/gls"), GrailsLanguageServer.cacheDir(com.google.gson.JsonParser.parseString("\"/tmp/gls\""), logged::add));
    assertEquals(List.of(), logged);
    for (String bad : List.of("{}", "[\"/tmp/gls\"]", "1", "true", "\"\"", "\"  \"", "\"a\\u0000b\"")) {
      assertEquals(fallback, GrailsLanguageServer.cacheDir(com.google.gson.JsonParser.parseString(bad), logged::add), bad);
    }
    assertEquals(7, logged.size(), logged.toString());
    assertEquals("cacheDir is not a path ({}): using " + fallback, logged.get(0));
    assertEquals(fallback, GrailsLanguageServer.cacheDir(null, logged::add), "not given");
    assertEquals(fallback, GrailsLanguageServer.cacheDir(com.google.gson.JsonNull.INSTANCE, logged::add), "null");
    assertEquals(7, logged.size(), "not given is no mistake");
  }

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
    Path root = tmp.toRealPath().resolve("project"); // Gradle reports the project's real paths
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


  /** The messages of the next diagnostics published for {@code uri} within {@code seconds}, or null. */
  private List<String> nextDiagnostics(String uri, int seconds) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    while (System.nanoTime() < end) {
      PublishDiagnosticsParams published = diagnostics.poll(100, TimeUnit.MILLISECONDS);
      if (published != null && published.getUri().equals(uri)) {
        return published.getDiagnostics().stream().map(d -> d.getMessage()).collect(java.util.stream.Collectors.toList());
      }
    }
    return null;
  }

  /** Diagnostics for {@code uri} until they are {@code expected}; fails after {@code seconds}. */
  private void awaitDiagnostics(String uri, List<String> expected, int seconds) throws InterruptedException {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
    List<String> last = null;
    while (System.nanoTime() < end && !expected.equals(last)) {
      List<String> next = nextDiagnostics(uri, 1);
      last = next == null ? last : next;
    }
    assertEquals(expected, last);
  }

  private void startReady(Path root) throws Exception {
    start(root, trustedOptions());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);
  }

  /**
   * A client that does not watch files (Neovim on Linux): the server watches the source roots itself, so a class
   * created in a terminal resolves without a reload, and the open document compiles again on its own. On macOS the
   * JDK's watch service polls every 10 s.
   */
  @Test
  void withoutAClientWatcherAClassCreatedOnDiskResolvesWithoutAReload() throws Exception {
    Path root = groovyProject();
    startReady(root);
    String uri = root.resolve("src/A.groovy").toUri().toString();
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, "class A {\n  lib.Shelf shelf\n}\n")));
    assertEquals(List.of("unable to resolve class lib.Shelf"), nextDiagnostics(uri, 30));

    Files.createDirectories(root.resolve("src/lib"));
    Path shelf = Files.writeString(root.resolve("src/lib/Shelf.groovy"), "package lib\nclass Shelf {}\n");
    awaitDiagnostics(uri, List.of(), 60);
    Files.delete(shelf);
    Files.delete(root.resolve("src/lib"));
    awaitDiagnostics(uri, List.of("unable to resolve class lib.Shelf"), 60);
    assertEquals(1, FakeWrapper.runs(root));
  }

  /**
   * {@code grails/reindex} reads the disk again without Gradle: changes no event reported (the client watches files,
   * but was told nothing) are applied, symbols included, by the time it answers.
   */
  @Test
  void reindexAppliesChangesNoEventReportedWithoutGradle() throws Exception {
    clientWatchesFiles = true;
    Path root = groovyProject();
    startReady(root);
    assertEquals(List.of("**/*.groovy", "**/*.java", "**/*.gsp", "**/*.gradle", "**/*.gradle.kts", "**/gradle.properties", "**/gradle-wrapper.properties",
        "**/*.versions.toml", "**/*.lockfile"), registeredGlobs.poll(10, TimeUnit.SECONDS), "sources, pages and build files");
    String uri = root.resolve("src/A.groovy").toUri().toString();
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, "class A {\n  Shelf shelf\n}\n")));
    assertEquals(List.of("unable to resolve class Shelf"), nextDiagnostics(uri, 30));

    Files.writeString(root.resolve("src/Shelf.groovy"), "class Shelf {\n  int shelfSize() { 1 }\n}\n");
    assertNull(nextDiagnostics(uri, 2), "no watcher of the server's own: nothing happens yet");
    server.reindex().get(30, TimeUnit.SECONDS);
    assertEquals(List.of("Method shelfSize"), server.getWorkspaceService().symbol(new WorkspaceSymbolParams("shelfSize")).get(10, TimeUnit.SECONDS).stream()
        .map(found -> found.getKind() + " " + found.getName()).collect(java.util.stream.Collectors.toList()), "symbols are current when it answers");
    awaitDiagnostics(uri, List.of(), 30);
    assertEquals(1, FakeWrapper.runs(root), "Gradle ran once, to resolve");
  }

  /**
   * Client events: a rename delivered as create-then-delete keeps the class, and a changed build file is followed by
   * one message that the model is stale, not by a Gradle run.
   */
  @Test
  void clientEventsFollowARenameAndABuildFileChange() throws Exception {
    clientWatchesFiles = true;
    Path root = groovyProject();
    Path old = Files.writeString(root.resolve("src/Shelf.groovy"), "class Shelf {}\n");
    startReady(root);
    String uri = root.resolve("src/A.groovy").toUri().toString();
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, "class A {\n  Shelf shelf\n}\n")));
    assertEquals(List.of(), nextDiagnostics(uri, 30));

    Files.createDirectories(root.resolve("src/moved"));
    Path moved = Files.move(old, root.resolve("src/moved/Shelf.groovy"));
    server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of(new FileEvent(moved.toUri().toString(), FileChangeType.Created))));
    server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of(new FileEvent(old.toUri().toString(), FileChangeType.Deleted))));
    server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, 2),
        List.of(new TextDocumentContentChangeEvent("class A {\n  Shelf shelf\n  int n\n}\n"))));
    awaitDiagnostics(uri, List.of(), 30);

    Files.writeString(root.resolve("settings.gradle"), "rootProject.name = 'renamed'\n");
    server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(List.of(new FileEvent(root.resolve("settings.gradle").toUri().toString(),
        FileChangeType.Changed))));
    String notice = shown.poll(10, TimeUnit.SECONDS);
    assertTrue(notice != null && notice.contains("grails/reloadProject"), String.valueOf(notice));
    assertEquals(1, FakeWrapper.runs(root), "never Gradle on its own");
  }

  /**
   * A workspace opened through a symlink: Gradle reports the project's real paths, the editor the paths it opened.
   * On macOS {@code /tmp} against {@code /private/tmp} is enough. Diagnostics go to the editor's URI.
   */
  @Test
  void aWorkspaceOpenedThroughASymlinkCompilesAndAnswers() throws Exception {
    Path root = groovyProject();
    Path link = Files.createSymbolicLink(tmp.resolve("link"), root);
    start(link, trustedOptions());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);

    String uri = link.resolve("src/A.groovy").toUri().toString();
    String text = "class A {\n  String name\n  Missing m\n  def run() { name.size() }\n}\n";
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, text)));
    PublishDiagnosticsParams published = diagnostics.poll(30, TimeUnit.SECONDS);
    assertTrue(published != null, "no diagnostics");
    assertEquals(uri, published.getUri());
    assertEquals(List.of("unable to resolve class Missing"), published.getDiagnostics().stream().map(d -> d.getMessage()).collect(java.util.stream.Collectors.toList()));
    Hover hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier(uri), new Position(3, 15))).get(30, TimeUnit.SECONDS);
    assertEquals("```groovy\nprivate String name\n```", hover == null ? null : hover.getContents().getRight().getValue());
  }

  /** Documents that are no files ({@code untitled:}, {@code jdt:}) are ignored, and the others still compile. */
  @Test
  void documentsThatAreNoFilesAreIgnored() throws Exception {
    List<LogRecord> warnings = endpointWarnings();
    Path root = groovyProject();
    start(root, trustedOptions());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);

    for (String uri : List.of("untitled:Untitled-1", "jdt://contents/rt.jar/java.lang/String.class")) {
      server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, "class A {\n")));
      server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(new VersionedTextDocumentIdentifier(uri, 2), List.of(new TextDocumentContentChangeEvent("class B {\n"))));
      Hover hover = server.getTextDocumentService().hover(new HoverParams(new TextDocumentIdentifier(uri), new Position(0, 7))).get(30, TimeUnit.SECONDS);
      assertNull(hover, uri);
      server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
    }
    String file = root.resolve("src/A.groovy").toUri().toString();
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(file, "groovy", 1, "class A {\n")));
    PublishDiagnosticsParams published = diagnostics.poll(30, TimeUnit.SECONDS);
    assertTrue(published != null, "no diagnostics");
    assertEquals(file, published.getUri(), "the only diagnostics are the file's");
    assertEquals(List.of(), warnings.stream().map(r -> r.getMessage() + " " + r.getThrown()).collect(java.util.stream.Collectors.toList()));
  }

  /** A root that is no file starts nothing and says why; initialize does not fail. */
  @Test
  void aRootThatIsNoFileIsNoWorkspaceFolder() throws Exception {
    start("untitled:project", trustedOptions(), proxy -> {});
    assertEquals(new StatusParams(StatusParams.FAILED, "no workspace folder"), next());
  }

  /** The warnings lsp4j logs when a notification handler throws. */
  private static List<LogRecord> endpointWarnings() {
    List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
    Logger logger = Logger.getLogger(RemoteEndpoint.class.getName());
    Handler handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
          records.add(record);
        }
      }

      @Override
      public void flush() {}

      @Override
      public void close() {}
    };
    logger.addHandler(handler);
    HANDLERS.add(handler);
    return records;
  }

  private static final List<Handler> HANDLERS = new java.util.concurrent.CopyOnWriteArrayList<>();

  @AfterEach
  void removeHandlers() {
    HANDLERS.forEach(Logger.getLogger(RemoteEndpoint.class.getName())::removeHandler);
    HANDLERS.clear();
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

  /**
   * Clients that complete only on trigger characters (Neovim's built-in completion) open GSP tag names after
   * {@code <g:}; a {@code :} anywhere else asks for nothing, so it compiles nothing.
   */
  @Test
  void aColonTriggersGspTagNamesOnly() throws Exception {
    Path root = groovyProject();
    start(root, trustedOptions());
    assertEquals(List.of(".", ":"), initializeResult.getCapabilities().getCompletionProvider().getTriggerCharacters());
    assertEquals(StatusParams.RESOLVING, next().state);
    assertEquals(StatusParams.INDEXING, next().state);
    assertEquals(StatusParams.READY, next().state);

    String uri = root.resolve("src/A.groovy").toUri().toString();
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, "class A {\n  def run() {\n    def m = [a:\n  }\n}\n")));
    CompletionParams colon = new CompletionParams(new TextDocumentIdentifier(uri), new Position(2, 15), new CompletionContext(CompletionTriggerKind.TriggerCharacter, ":"));
    assertEquals(List.of(), server.getTextDocumentService().completion(colon).get(30, TimeUnit.SECONDS).getRight().getItems());

    CompletionContext typed = new CompletionContext(CompletionTriggerKind.TriggerCharacter, ":");
    assertTrue(GrailsLanguageServer.answers(typed, "/v/a.gsp", "<div>\n  <g:", 1, 5));
    assertTrue(GrailsLanguageServer.answers(typed, "/v/a.gsp", "<ns2:", 0, 5));
    assertTrue(GrailsLanguageServer.answers(null, "/v/a.gsp", "${a ? b :", 0, 9), "no context: invoked");
    assertTrue(GrailsLanguageServer.answers(new CompletionContext(CompletionTriggerKind.Invoked), "/v/a.gsp", "${a ? b :", 0, 9));
    assertTrue(GrailsLanguageServer.answers(new CompletionContext(CompletionTriggerKind.TriggerCharacter, "."), "/A.groovy", "a.", 0, 2));
    assertEquals(false, GrailsLanguageServer.answers(typed, "/v/a.gsp", "${a ? b :", 0, 9), "page code");
    assertEquals(false, GrailsLanguageServer.answers(typed, "/v/a.gsp", "<a href=\"http:", 0, 14), "html");
    assertEquals(false, GrailsLanguageServer.answers(typed, "/v/a.gsp", "< g:", 0, 4), "no tag");
    assertEquals(false, GrailsLanguageServer.answers(typed, "/A.groovy", "'<g:", 0, 4), "groovy");
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

  /** Closing a document clears its diagnostics, which is no compile: an untrusted workspace logs no first diagnostics. */
  @Test
  void closingADocumentIsNoFirstDiagnostics() throws Exception {
    Path root = FakeWrapper.write(tmp.resolve("project"), FakeWrapper.WRITES_MODEL).getParent();
    start(root, new JsonObject());
    assertEquals(StatusParams.FAILED, next().state);
    String uri = root.resolve("A.groovy").toUri().toString();
    server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(new TextDocumentItem(uri, "groovy", 1, "class A {}\n")));
    server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(new TextDocumentIdentifier(uri)));
    PublishDiagnosticsParams cleared = diagnostics.poll(10, TimeUnit.SECONDS);
    assertTrue(cleared != null && cleared.getDiagnostics().isEmpty(), String.valueOf(cleared));
    Thread.sleep(500); // a mark would be logged right after
    assertEquals(List.of(), startupMarks());
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
    public void showMessage(MessageParams messageParams) {
      shown.add(messageParams.getMessage());
    }

    @Override
    public CompletableFuture<Void> registerCapability(RegistrationParams params) {
      for (Registration registration : params.getRegistrations()) {
        DidChangeWatchedFilesRegistrationOptions options = new com.google.gson.Gson().fromJson(String.valueOf(registration.getRegisterOptions()),
            DidChangeWatchedFilesRegistrationOptions.class);
        registeredGlobs.add(options.getWatchers().stream().map(w -> w.getGlobPattern()).collect(java.util.stream.Collectors.toList()));
      }
      return CompletableFuture.completedFuture(null);
    }

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
