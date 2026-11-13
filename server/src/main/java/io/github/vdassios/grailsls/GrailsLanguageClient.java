package io.github.vdassios.grailsls;

import org.eclipse.lsp4j.jsonrpc.services.JsonNotification;
import org.eclipse.lsp4j.services.LanguageClient;

/** The client as this server sees it: LSP plus the {@code grails/*} notifications. */
public interface GrailsLanguageClient extends LanguageClient {
  @JsonNotification("grails/status")
  void status(StatusParams params);
}
