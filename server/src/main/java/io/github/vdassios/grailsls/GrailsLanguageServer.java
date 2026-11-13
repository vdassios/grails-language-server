package io.github.vdassios.grailsls;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
import io.github.vdassios.grailsls.index.SymbolIndex;
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
  private CompileService compiles;
  private boolean watchFiles;

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
    compiles = new CompileService(diagnostics -> client.publishDiagnostics(diagnostics), CompileService.DEBOUNCE_MILLIS);
    project = new ProjectService(bridge, workspaceRoot(params), trusted, status -> client.status(status), model -> {
      Workspace workspace = new Workspace(model, AnalysisJar.extract(cacheDir));
      workspace.index();
      compiles.setWorkspace(workspace);
      workspace.indexSymbols();
      return workspace.summary();
    });
    watchFiles = params.getCapabilities() != null && params.getCapabilities().getWorkspace() != null
        && params.getCapabilities().getWorkspace().getDidChangeWatchedFiles() != null
        && Boolean.TRUE.equals(params.getCapabilities().getWorkspace().getDidChangeWatchedFiles().getDynamicRegistration());

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
    return CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo("grails-language-server")));
  }

  @Override
  public void initialized(InitializedParams params) {
    if (watchFiles) {
      // keeps the declaration index current when files change outside the editor (checkout, generators)
      DidChangeWatchedFilesRegistrationOptions options = new DidChangeWatchedFilesRegistrationOptions(List.of(new FileSystemWatcher("**/*.groovy")));
      client.registerCapability(new RegistrationParams(List.of(new Registration("groovy-files", "workspace/didChangeWatchedFiles", options))));
    }
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
    if (compiles != null) {
      compiles.shutdown();
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
      }
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
      Position at = params.getPosition();
      return compiles.query(params.getTextDocument().getUri(), null, (analyzer, path) -> analyzer.definition(path, at.getLine(), at.getCharacter()))
          .thenApply(location -> Either.forLeft(location == null ? List.of() : List.of(location(location))));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
      Position at = params.getPosition();
      return compiles.query(params.getTextDocument().getUri(), null, (analyzer, path) -> analyzer.hover(path, at.getLine(), at.getCharacter()))
          .thenApply(markdown -> markdown == null ? null : new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown)));
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> documentSymbol(DocumentSymbolParams params) {
      String uri = params.getTextDocument().getUri();
      return compiles.query(uri, List.<Symbol>of(), (analyzer, path) -> analyzer.symbols(path)).thenApply(symbols -> {
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
      return compiles.queryText(uri, io.github.vdassios.grailsls.api.CompletionList.EMPTY, (analyzer, request) -> analyzer.completion(request, at.getLine(), at.getCharacter()))
          .thenApply(list -> Either.forRight(completionList(uri, list)));
    }

    /** Adds the signature and documentation; the item's data names its document and its key there. */
    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem item) {
      JsonObject data = item.getData() instanceof JsonObject ? (JsonObject) item.getData() : null;
      if (data == null || !data.has("uri") || !data.has("id")) {
        return CompletableFuture.completedFuture(item);
      }
      int id = data.get("id").getAsInt();
      return compiles.query(data.get("uri").getAsString(), null, (analyzer, path) -> analyzer.resolveCompletion(path, id)).thenApply(detail -> {
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
      return compiles.queryText(params.getTextDocument().getUri(), null, (analyzer, request) -> analyzer.signatureHelp(request, at.getLine(), at.getCharacter()))
          .thenApply(help -> help == null ? null : toSignatureHelp(help));
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
      for (FileEvent event : params.getChanges()) {
        Path path = Documents.path(event.getUri());
        if (path != null) {
          compiles.fileChanged(path);
        }
      }
    }
  };
}
