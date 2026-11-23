package io.github.vdassios.grailsls;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesRegistrationOptions;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ParameterInformation;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.FileSystemWatcher;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.SaveOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.SignatureHelp;
import org.eclipse.lsp4j.SignatureHelpOptions;
import org.eclipse.lsp4j.SignatureHelpParams;
import org.eclipse.lsp4j.SignatureInformation;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextDocumentSyncOptions;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.WorkspaceSymbolParams;
import org.eclipse.lsp4j.services.WorkspaceService;

import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.Symbol;
import io.github.vdassios.grailsls.compile.AnalysisJar;
import io.github.vdassios.grailsls.compile.CompileService;
import io.github.vdassios.grailsls.compile.Documents;
import io.github.vdassios.grailsls.compile.Workspace;
import io.github.vdassios.grailsls.index.SourceWatcher;
import io.github.vdassios.grailsls.index.SymbolIndex;
import io.github.vdassios.grailsls.project.GradleBridge;
import io.github.vdassios.grailsls.project.ProjectModel;
import io.github.vdassios.grailsls.project.ProjectService;
import io.github.vdassios.grailsls.project.SourcesReport;

/**
 * LSP entry point. Speaks LSP over stdio.
 *
 * <p>Initialization options: {@code trusted} (required before any project code runs), {@code gradle.timeoutSeconds}
 * (default 300; a number or a numeric string) and {@code cacheDir}.
 *
 * <p>The server exits when its client goes away, even without the {@code exit} notification: when its input ends,
 * or when the process named by {@code InitializeParams.processId} ends. The exit code is 0 after a {@code shutdown}
 * request, else 1, as LSP asks. {@code shutdown} and the exit cancel the compiles and kill the Gradle processes the
 * server started, and wait for nothing else: the module loaders are left to the end of the process.
 */
public class GrailsLanguageServer implements LanguageServer, LanguageClientAware {
  static final int DEFAULT_TIMEOUT_SECONDS = 300;

  public static void main(String[] args) {
    InputStream in = System.in;
    OutputStream out = System.out;
    // stdout carries LSP messages only
    System.setOut(new PrintStream(System.err));
    serve(new GrailsLanguageServer(), in, out);
  }

  /** Answers messages from {@code in} until it ends, then exits as {@link #clientGone()} does. */
  static void serve(GrailsLanguageServer server, InputStream in, OutputStream out) {
    Launcher<GrailsLanguageClient> launcher = Launcher.createLauncher(server, GrailsLanguageClient.class, in, out);
    server.connect(launcher.getRemoteProxy());
    try {
      launcher.startListening().get();
    } catch (ExecutionException e) {
      // the stream broke rather than ended: the client is gone all the same
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    server.clientGone();
  }

  private final IntConsumer exit;
  private final AtomicBoolean stopped = new AtomicBoolean();
  private final AtomicBoolean exited = new AtomicBoolean();
  private volatile boolean shutdownRequested;

  public GrailsLanguageServer() {
    this(System::exit);
  }

  /** @param exit ends the process with the code given; tests pass their own */
  GrailsLanguageServer(IntConsumer exit) {
    this.exit = exit;
  }

  private GrailsLanguageClient client;
  private volatile ProjectService project;
  private volatile CompileService compiles;
  /** Whether the client watches files for the server ({@code workspace/didChangeWatchedFiles} registration). */
  private boolean watchFiles;
  /** Follows the disk when the client does not; one per workspace. Guarded by {@code this}. */
  private SourceWatcher watcher;
  /** Whether events can be missed (the watcher could not watch every directory): each save then rescans. */
  private volatile boolean rescanOnSave;

  /** What the client is asked to watch: sources, pages and the build files of the model's cache key. */
  static final List<String> WATCHED_GLOBS = List.of("**/*.groovy", "**/*.java", "**/*.gsp", "**/*.gradle", "**/*.gradle.kts", "**/gradle.properties",
      "**/gradle-wrapper.properties", "**/*.versions.toml", "**/*.lockfile");

  @Override
  public void connect(LanguageClient client) {
    this.client = (GrailsLanguageClient) client;
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    JsonObject options = params.getInitializationOptions() instanceof JsonObject ? (JsonObject) params.getInitializationOptions() : new JsonObject();
    boolean trusted = bool(options.get("trusted"));
    JsonObject gradle = options.has("gradle") && options.get("gradle").isJsonObject() ? options.getAsJsonObject("gradle") : new JsonObject();
    int timeout = timeoutSeconds(gradle.get("timeoutSeconds"), message -> client.logMessage(new MessageParams(MessageType.Warning, message)));
    Path cacheDir = options.has("cacheDir") ? Path.of(options.get("cacheDir").getAsString()) : GradleBridge.defaultCacheDir();
    GradleBridge bridge = new GradleBridge(cacheDir, GradleBridge.defaultJavaHome(), Duration.ofSeconds(timeout));
    StartupMarks marks = new StartupMarks(message -> client.logMessage(new MessageParams(MessageType.Log, message)));
    compiles = new CompileService(diagnostics -> {
      client.publishDiagnostics(diagnostics);
      marks.mark(StartupMarks.FIRST_DIAGNOSTICS);
    }, CompileService.DEBOUNCE_MILLIS);
    project = new ProjectService(bridge, workspaceRoot(params), trusted, status -> client.status(status), model -> {
      marks.mark(StartupMarks.MODEL_RESOLVED);
      Workspace workspace = new Workspace(model, AnalysisJar.extract(cacheDir), this::report);
      String jdkError = workspace.jdkClassFileError();
      if (jdkError != null) {
        workspace.close();
        throw new ProjectService.Unsupported(jdkError);
      }
      workspace.index();
      marks.mark(StartupMarks.DECLARATIONS_INDEXED);
      compiles.setWorkspace(workspace);
      if (!watchFiles) {
        watch(model, workspace);
      }
      // the symbol index parses on the compile threads: started after the open documents' compiles, whose
      // diagnostics would otherwise wait for its batches and compete with it for the CPU
      compiles.afterPendingCompiles().thenCompose(compiled -> workspace.indexSymbols()).thenRun(() -> marks.mark(StartupMarks.SYMBOLS_INDEXED));
      return workspace.summary();
    }, notice -> client.showMessage(new MessageParams(MessageType.Warning, notice)));
    watchFiles = params.getCapabilities() != null && params.getCapabilities().getWorkspace() != null
        && params.getCapabilities().getWorkspace().getDidChangeWatchedFiles() != null
        && Boolean.TRUE.equals(params.getCapabilities().getWorkspace().getDidChangeWatchedFiles().getDynamicRegistration());
    if (params.getProcessId() != null) {
      // a client killed while another process holds its end of the pipe never closes our input
      ProcessHandle.of(params.getProcessId()).ifPresent(parent -> parent.onExit().thenRun(this::clientGone));
    }

    ServerCapabilities capabilities = new ServerCapabilities();
    TextDocumentSyncOptions sync = new TextDocumentSyncOptions();
    sync.setOpenClose(true);
    sync.setChange(TextDocumentSyncKind.Incremental);
    sync.setSave(new SaveOptions(false));
    capabilities.setTextDocumentSync(sync);
    capabilities.setDefinitionProvider(true);
    capabilities.setHoverProvider(true);
    capabilities.setDocumentSymbolProvider(true);
    capabilities.setWorkspaceSymbolProvider(true);
    capabilities.setCompletionProvider(new CompletionOptions(true, List.of(".")));
    capabilities.setSignatureHelpProvider(new SignatureHelpOptions(List.of("(", ",")));
    return CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo("grails-language-server", version())));
  }

  /**
   * A failure: shown to the user ({@code window/showMessage}) when it turns a feature off, so that a {@code ready}
   * status with nothing working is never the only sign; otherwise only logged ({@code window/logMessage}).
   */
  private void report(boolean show, String message) {
    if (show) {
      client.showMessage(new MessageParams(MessageType.Error, message));
    } else {
      client.logMessage(new MessageParams(MessageType.Warning, message));
    }
  }

  /**
   * {@code gradle.timeoutSeconds}: a positive whole number, also given as a string; anything else is logged and gives
   * the default, as does no value.
   */
  static int timeoutSeconds(JsonElement value, Consumer<String> log) {
    if (value == null || value.isJsonNull()) {
      return DEFAULT_TIMEOUT_SECONDS;
    }
    if (value.isJsonPrimitive() && !value.getAsJsonPrimitive().isBoolean()) {
      try {
        int seconds = Integer.parseInt(value.getAsString().trim());
        if (seconds > 0) {
          return seconds;
        }
      } catch (NumberFormatException e) {
        // logged below
      }
    }
    log.accept("gradle.timeoutSeconds is not a positive whole number (" + value + "): using " + DEFAULT_TIMEOUT_SECONDS);
    return DEFAULT_TIMEOUT_SECONDS;
  }

  /** The server's version, from the jar's manifest; null when not run from the jar (tests). */
  static String version() {
    return GrailsLanguageServer.class.getPackage().getImplementationVersion();
  }

  @Override
  public void initialized(InitializedParams params) {
    if (watchFiles) {
      // keeps the indexes current when files change outside the editor (checkout, generators); otherwise SourceWatcher does
      List<FileSystemWatcher> watchers = new ArrayList<>();
      WATCHED_GLOBS.forEach(glob -> watchers.add(new FileSystemWatcher(glob)));
      DidChangeWatchedFilesRegistrationOptions options = new DidChangeWatchedFilesRegistrationOptions(watchers);
      client.registerCapability(new RegistrationParams(List.of(new Registration("grails-files", "workspace/didChangeWatchedFiles", options))));
    }
    project.start();
  }

  /** Replaces the watcher with one over the new workspace's source trees and the model's build-file directories. */
  private synchronized void watch(ProjectModel model, Workspace workspace) {
    if (watcher != null) {
      watcher.close();
      watcher = null;
    }
    if (stopped.get()) {
      return;
    }
    Set<Path> buildFileDirs = new LinkedHashSet<>();
    buildFileDirs.add(model.buildRoot);
    model.modules.forEach(module -> buildFileDirs.add(Path.of(module.projectDir)));
    GradleBridge.buildFiles(model.buildRoot).forEach(file -> buildFileDirs.add(file.getParent()));
    SourceWatcher.Events events = new SourceWatcher.Events() {
      @Override
      public void changed(Path path) {
        fileChanged(model.buildRoot, path);
      }

      @Override
      public void rescan() {
        compiles.rescan();
      }
    };
    try {
      watcher = SourceWatcher.start(workspace.sourceTrees(), new ArrayList<>(buildFileDirs), workspace.buildDirs(), events,
          message -> client.logMessage(new MessageParams(MessageType.Warning, message)));
      rescanOnSave = watcher.failed();
    } catch (IOException e) {
      rescanOnSave = true;
      client.logMessage(new MessageParams(MessageType.Warning, "cannot watch files (" + e + "): changes made outside the editor are read on save and on grails/reindex"));
    }
  }

  /** A watched file or directory changed: a build file may make the model stale, anything else goes to the indexes. */
  private void fileChanged(Path buildRoot, Path path) {
    if (buildRoot != null && GradleBridge.isBuildFile(buildRoot, path)) {
      project.buildFilesChanged();
    } else {
      compiles.fileChanged(path);
    }
  }

  /**
   * {@code grails/reindex}: reads the source roots again, without Gradle, so the indexes match the disk after changes
   * no event reported. Completes with null once they do; at once when no project is resolved. Also checks whether the
   * build files still match the model ({@link ProjectService#buildFilesChanged}).
   */
  @JsonRequest("grails/reindex")
  public CompletableFuture<Object> reindex() {
    project.buildFilesChanged();
    return compiles.rescan().thenApply(done -> null);
  }

  /** {@code grails/reloadProject}: re-resolves the project, ignoring the cache. Completes when it is done. */
  @JsonRequest("grails/reloadProject")
  public CompletableFuture<Object> reloadProject() {
    return project.reload().thenApply(done -> null);
  }

  /**
   * {@code grails/downloadSources}: fetches the sources jars the project's libraries lack, with the project's own
   * Gradle, so definition and hover reach them. Completes with the counts when Gradle is done.
   */
  @JsonRequest("grails/downloadSources")
  public CompletableFuture<SourcesReport> downloadSources() {
    return project.downloadSources();
  }

  @Override
  public CompletableFuture<Object> shutdown() {
    shutdownRequested = true;
    stop();
    return CompletableFuture.completedFuture(null);
  }

  @Override
  public void exit() {
    clientGone();
  }

  /** Stops the work in progress (Gradle, compiles) and exits: 0 after {@code shutdown}, else 1. Runs once. */
  void clientGone() {
    if (exited.compareAndSet(false, true)) {
      try {
        stop();
      } finally {
        exit.accept(shutdownRequested ? 0 : 1);
      }
    }
  }

  /** The compile service; null before {@code initialize}. For tests. */
  CompileService compiles() {
    return compiles;
  }

  private void stop() {
    if (stopped.compareAndSet(false, true)) {
      synchronized (this) {
        if (watcher != null) {
          watcher.close();
          watcher = null;
        }
      }
      ProjectService currentProject = project;
      CompileService currentCompiles = compiles;
      try {
        if (currentProject != null) {
          currentProject.stop();
        }
      } finally {
        if (currentCompiles != null) {
          currentCompiles.shutdown(); // leaves the workspace open: closing it waits for each busy module thread
        }
      }
    }
  }

  @Override
  public TextDocumentService getTextDocumentService() {
    return textDocuments;
  }

  @Override
  public WorkspaceService getWorkspaceService() {
    return workspace;
  }

  /** The first workspace folder, else the root URI; null when there is none or it is no file. */
  @SuppressWarnings("deprecation")
  private static Path workspaceRoot(InitializeParams params) {
    String uri = params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty() ? params.getWorkspaceFolders().get(0).getUri() : params.getRootUri();
    return uri == null ? null : Documents.path(uri);
  }

  private static boolean bool(JsonElement element) {
    return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
  }

  private final TextDocumentService textDocuments = new TextDocumentService() {
    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
      compiles.open(params.getTextDocument().getUri(), params.getTextDocument().getText(), params.getTextDocument().getVersion());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
      compiles.change(params.getTextDocument().getUri(), params.getContentChanges(), params.getTextDocument().getVersion());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
      compiles.close(params.getTextDocument().getUri());
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
      Path path = Documents.path(params.getTextDocument().getUri());
      if (path != null) {
        compiles.fileChanged(path);
        if (rescanOnSave) {
          compiles.rescan(); // the watcher misses some directories: what changed there is read now
        }
      }
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
      Position at = params.getPosition();
      return CompileService.mapped(compiles.query(params.getTextDocument().getUri(), null, (analyzer, path) -> analyzer.definition(path, at.getLine(), at.getCharacter())),
          location -> Either.forLeft(location == null ? List.of() : List.of(location(location))));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
      Position at = params.getPosition();
      return CompileService.mapped(compiles.query(params.getTextDocument().getUri(), null, (analyzer, path) -> analyzer.hover(path, at.getLine(), at.getCharacter())),
          markdown -> markdown == null ? null : new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown)));
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
      String uri = params.getTextDocument().getUri();
      return CompileService.mapped(compiles.query(uri, List.<Symbol>of(), (analyzer, path) -> analyzer.symbols(path)), symbols -> {
        List<Either<SymbolInformation, DocumentSymbol>> result = new ArrayList<>();
        for (Symbol symbol : symbols) {
          result.add(Either.forLeft(new SymbolInformation(symbol.name, symbolKind(symbol.kind), new Location(uri, range(symbol.range)), symbol.container)));
        }
        return result;
      });
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
      String uri = params.getTextDocument().getUri();
      Position at = params.getPosition();
      return CompileService.mapped(compiles.queryText(uri, io.github.vdassios.grailsls.api.CompletionList.EMPTY, (analyzer, request) -> analyzer.completion(request, at.getLine(), at.getCharacter())),
          list -> Either.forRight(completionList(uri, list)));
    }

    /** Adds the signature and documentation; the item's data names its document and its key there. */
    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
      JsonObject data = item.getData() instanceof JsonObject ? (JsonObject) item.getData() : null;
      if (data == null || !data.has("uri") || !data.has("id")) {
        return CompletableFuture.completedFuture(item);
      }
      int id = data.get("id").getAsInt();
      return CompileService.mapped(compiles.query(data.get("uri").getAsString(), null, (analyzer, path) -> analyzer.resolveCompletion(path, id)), detail -> {
        if (detail != null) {
          item.setDetail(detail.detail);
          if (detail.documentation != null) {
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, detail.documentation));
          }
        }
        return item;
      });
    }

    @Override
    public CompletableFuture<SignatureHelp> signatureHelp(SignatureHelpParams params) {
      Position at = params.getPosition();
      return CompileService.mapped(compiles.queryText(params.getTextDocument().getUri(), null, (analyzer, request) -> analyzer.signatureHelp(request, at.getLine(), at.getCharacter())),
          help -> help == null ? null : toSignatureHelp(help));
    }
  };

  private static CompletionList completionList(String uri, io.github.vdassios.grailsls.api.CompletionList list) {
    List<CompletionItem> items = new ArrayList<>();
    for (Completion completion : list.items) {
      CompletionItem item = new CompletionItem(completion.label);
      item.setKind(completionKind(completion.kind));
      item.setDetail(completion.detail);
      item.setSortText(completion.sortText);
      if (completion.replace != null) {
        item.setTextEdit(Either.forLeft(new TextEdit(range(completion.replace), completion.label)));
      }
      if (!completion.additionalEdits.isEmpty()) {
        List<TextEdit> edits = new ArrayList<>();
        for (io.github.vdassios.grailsls.api.TextEdit edit : completion.additionalEdits) {
          edits.add(new TextEdit(range(edit.range), edit.newText));
        }
        item.setAdditionalTextEdits(edits);
      }
      if (completion.id >= 0) {
        JsonObject data = new JsonObject();
        data.addProperty("uri", uri);
        data.addProperty("id", completion.id);
        item.setData(data);
      }
      items.add(item);
    }
    return new CompletionList(list.incomplete, items);
  }

  private static CompletionItemKind completionKind(Completion.Kind kind) {
    switch (kind) {
      case CLASS:
        return CompletionItemKind.Class;
      case INTERFACE:
        return CompletionItemKind.Interface;
      case ENUM:
        return CompletionItemKind.Enum;
      case METHOD:
        return CompletionItemKind.Method;
      case FIELD:
        return CompletionItemKind.Field;
      case PACKAGE:
        return CompletionItemKind.Module;
      case FILE:
        return CompletionItemKind.File;
      default:
        return CompletionItemKind.Variable;
    }
  }

  private static SignatureHelp toSignatureHelp(io.github.vdassios.grailsls.api.SignatureHelp help) {
    List<SignatureInformation> signatures = new ArrayList<>();
    for (io.github.vdassios.grailsls.api.SignatureHelp.Signature signature : help.signatures) {
      SignatureInformation information = new SignatureInformation(signature.label);
      List<ParameterInformation> parameters = new ArrayList<>();
      for (String parameter : signature.parameters) {
        parameters.add(new ParameterInformation(parameter));
      }
      information.setParameters(parameters);
      if (signature.documentation != null) {
        information.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, signature.documentation));
      }
      signatures.add(information);
    }
    return new SignatureHelp(signatures, help.activeSignature, help.activeParameter);
  }

  private static Location location(io.github.vdassios.grailsls.api.Location location) {
    return new Location(Path.of(location.path).toUri().toString(), range(location.range));
  }

  private static Range range(io.github.vdassios.grailsls.api.Range range) {
    return new Range(new Position(range.startLine, range.startColumn), new Position(range.endLine, range.endColumn));
  }

  private static SymbolKind symbolKind(Symbol.Kind kind) {
    switch (kind) {
      case CLASS:
        return SymbolKind.Class;
      case INTERFACE:
        return SymbolKind.Interface;
      case ENUM:
        return SymbolKind.Enum;
      case METHOD:
        return SymbolKind.Method;
      case FIELD:
        return SymbolKind.Field;
      default:
        return SymbolKind.Variable;
    }
  }

  private final WorkspaceService workspace = new WorkspaceService() {
    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {}

    /** Classes and members of every Groovy source file whose name contains the query, from the symbol index. */
    @Override
    public CompletableFuture<List<? extends SymbolInformation>> symbol(WorkspaceSymbolParams params) {
      Workspace current = compiles == null ? null : compiles.workspace();
      List<SymbolInformation> result = new ArrayList<>();
      if (current != null) {
        for (SymbolIndex.Found found : current.symbols().search(params.getQuery())) {
          Location location = new Location(found.file.toUri().toString(), range(found.symbol.range));
          result.add(new SymbolInformation(found.symbol.name, symbolKind(found.symbol.kind), location, found.symbol.container));
        }
      }
      return CompletableFuture.completedFuture(result);
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
      ProjectModel model = project.model();
      for (FileEvent event : params.getChanges()) {
        Path path = Documents.path(event.getUri());
        if (path != null) {
          fileChanged(model == null ? null : model.buildRoot, path);
        }
      }
    }
  };
}
