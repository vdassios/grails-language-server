package io.github.vdassios.grailsls;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import io.github.vdassios.grailsls.project.GradleBridge;
import io.github.vdassios.grailsls.project.ProjectService;

/**
 * LSP entry point. Speaks LSP over stdio.
 *
 * <p>Initialization options: {@code trusted} (required before any project code runs), {@code gradle.timeoutSeconds}
 * (default 300) and {@code cacheDir}.
 */
public class GrailsLanguageServer implements LanguageServer, LanguageClientAware {
  static final int DEFAULT_TIMEOUT_SECONDS = 300;

  public static void main(String[] args) {
    InputStream in = System.in;
    OutputStream out = System.out;
    // stdout carries LSP messages only
    System.setOut(new PrintStream(System.err));
    GrailsLanguageServer server = new GrailsLanguageServer();
    Launcher<GrailsLanguageClient> launcher = Launcher.createLauncher(server, GrailsLanguageClient.class, in, out);
    server.connect(launcher.getRemoteProxy());
    launcher.startListening();
  }

  private GrailsLanguageClient client;
  private ProjectService project;

  @Override
  public void connect(LanguageClient client) {
    this.client = (GrailsLanguageClient) client;
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    JsonObject options = params.getInitializationOptions() instanceof JsonObject ? (JsonObject) params.getInitializationOptions() : new JsonObject();
    boolean trusted = bool(options.get("trusted"));
    JsonObject gradle = options.has("gradle") && options.get("gradle").isJsonObject() ? options.getAsJsonObject("gradle") : new JsonObject();
    int timeout = gradle.has("timeoutSeconds") ? gradle.get("timeoutSeconds").getAsInt() : DEFAULT_TIMEOUT_SECONDS;
    Path cacheDir = options.has("cacheDir") ? Path.of(options.get("cacheDir").getAsString()) : GradleBridge.defaultCacheDir();
    GradleBridge bridge = new GradleBridge(cacheDir, GradleBridge.defaultJavaHome(), Duration.ofSeconds(timeout));
    project = new ProjectService(bridge, workspaceRoot(params), trusted, status -> client.status(status));

    ServerCapabilities capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
    return CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo("grails-language-server")));
  }

  @Override
  public void initialized(InitializedParams params) {
    project.start();
  }

  /** {@code grails/reloadProject}: re-resolves the project, ignoring the cache. Completes when it is done. */
  @JsonRequest("grails/reloadProject")
  public CompletableFuture<Object> reloadProject() {
    return project.reload().thenApply(done -> null);
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    if (project != null) {
      project.stop();
    }
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    System.exit(0);
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocuments;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return workspace;
  }

  /** The first workspace folder, else the root URI. */
  @SuppressWarnings("deprecation")
  private static Path workspaceRoot(InitializeParams params) {
    String uri = params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty() ? params.getWorkspaceFolders().get(0).getUri() : params.getRootUri();
    return uri == null ? null : Path.of(URI.create(uri));
  }

  private static boolean bool(JsonElement element) {
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
  }

  private final TextDocumentService textDocuments = new TextDocumentService() {
    @Override
    public void didOpen(DidOpenTextDocumentParams params) {}

    @Override
    public void didChange(DidChangeTextDocumentParams params) {}

    @Override
    public void didClose(DidCloseTextDocumentParams params) {}

    @Override
    public void didSave(DidSaveTextDocumentParams params) {}
  };

  private final WorkspaceService workspace = new WorkspaceService() {
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {}

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {}
  };
}
