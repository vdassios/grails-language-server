package io.github.vdassios.grailsls;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

/** LSP entry point. Speaks LSP over stdio. */
public class GrailsLanguageServer implements LanguageServer, LanguageClientAware {

  public static void main(String[] args) {
    InputStream in = System.in;
    OutputStream out = System.out;
    // stdout carries LSP messages only
    System.setOut(new PrintStream(System.err));
    GrailsLanguageServer server = new GrailsLanguageServer();
    Launcher<LanguageClient> launcher = Launcher.createLauncher(server, LanguageClient.class, in, out);
    server.connect(launcher.getRemoteProxy());
    launcher.startListening();
  }

  private LanguageClient client;

  @Override
  public void connect(LanguageClient client) {
    this.client = client;
  }

  @Override
  public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
    ServerCapabilities capabilities = new ServerCapabilities();
    capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
    return CompletableFuture.completedFuture(new InitializeResult(capabilities, new ServerInfo("grails-language-server")));
  }

  @Override
  public CompletableFuture<Object> shutdown() {
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
