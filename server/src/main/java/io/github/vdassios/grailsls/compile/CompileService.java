package io.github.vdassios.grailsls.compile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.compile.Documents.Document;

/**
 * Compiles open documents and publishes their diagnostics. An opened document compiles at once; edits are debounced,
 * and an edit cancels the document's running compile. Nothing compiles before a {@link Workspace} is set, which only
 * happens for a trusted, resolved project.
 */
public final class CompileService {
  public static final long DEBOUNCE_MILLIS = 300;

  private final Documents documents = new Documents();
  private final Consumer<PublishDiagnosticsParams> publish;
  private final long debounceMillis;
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread thread = new Thread(r, "grails-compile-timer");
    thread.setDaemon(true);
    return thread;
  });
  private final Map<Path, Pending> pending = new ConcurrentHashMap<>();
  private volatile Workspace workspace;

  public CompileService(Consumer<PublishDiagnosticsParams> publish, long debounceMillis) {
    this.publish = publish;
    this.debounceMillis = debounceMillis;
  }

  public Documents documents() {
    return documents;
  }

  public Workspace workspace() {
    return workspace;
  }

  /** Replaces the workspace (closing the old one and its loaders) and compiles every open document again. */
  public void setWorkspace(Workspace next) {
    Workspace old = workspace;
    workspace = next;
    pending.values().forEach(Pending::cancel);
    if (old != null) {
      old.close();
    }
    for (Document document : documents.all()) {
      schedule(document.path, 0);
    }
  }

  public void open(String uri, String text, int version) {
    Document document = documents.open(uri, text, version);
    if (document.path != null) {
      schedule(document.path, 0);
    }
  }

  public void change(String uri, List<TextDocumentContentChangeEvent> changes, int version) {
    Document document = documents.change(uri, changes, version);
    if (document != null) {
      schedule(document.path, debounceMillis);
    }
  }

  public void close(String uri) {
    Path path = Documents.path(uri);
    documents.close(uri);
    Pending running = pending.remove(path);
    if (running != null) {
      running.cancel();
    }
    Workspace current = workspace;
    CompileContext context = current == null || path == null ? null : current.contextOf(path);
    if (context != null) {
      context.forget(path.toString());
    }
    publish.accept(new PublishDiagnosticsParams(uri, List.of()));
  }

  /** A file was saved, created, changed on disk or deleted: re-read its declarations. */
  public void fileChanged(Path path) {
    Workspace current = workspace;
    if (current != null) {
      current.declarations().update(path);
    }
  }

  public void shutdown() {
    timer.shutdownNow();
    Workspace current = workspace;
    workspace = null;
    if (current != null) {
      current.close();
    }
  }

  private void schedule(Path path, long delay) {
    Pending next = new Pending();
    Pending previous = pending.put(path, next);
    if (previous != null) {
      previous.cancel();
    }
    next.timer = timer.schedule(() -> run(path, next), delay, TimeUnit.MILLISECONDS);
  }

  private void run(Path path, Pending task) {
    Document document = documents.get(path);
    Workspace current = workspace;
    if (document == null || current == null || task.cancelled.get()) {
      return;
    }
    CompileContext context = current.contextOf(path);
    if (context == null) {
      return; // not a source file of a Groovy module
    }
    SourceLookup lookup = current.lookup(context, file -> {
      Document open = documents.get(file);
      return open == null ? null : open.text;
    });
    context.compile(new CompileRequest(path.toString(), document.text, lookup, task.cancelled::get)).whenComplete((result, error) -> {
      pending.remove(path, task);
      if (error != null) {
        if (!task.cancelled.get() && workspace == current) {
          System.err.println("grails-language-server: compile of " + path + " failed: " + error);
        }
        return;
      }
      Document now = documents.get(path);
      if (!result.cancelled && !task.cancelled.get() && now != null && now.version == document.version) {
        publish.accept(diagnostics(document, result));
      }
    });
  }

  private static PublishDiagnosticsParams diagnostics(Document document, CompileResult result) {
    String path = document.path.toString();
    List<Diagnostic> list = new ArrayList<>();
    for (io.github.vdassios.grailsls.api.Diagnostic d : result.diagnostics) {
      if (!d.path.equals(path)) {
        continue; // another file's: reported when that file is open
      }
      Range range = new Range(new Position(d.startLine, d.startColumn), new Position(d.endLine, d.endColumn));
      DiagnosticSeverity severity = d.severity == io.github.vdassios.grailsls.api.Diagnostic.ERROR ? DiagnosticSeverity.Error : DiagnosticSeverity.Warning;
      Diagnostic diagnostic = new Diagnostic(range, d.message, severity, "groovy");
      list.add(diagnostic);
    }
    PublishDiagnosticsParams params = new PublishDiagnosticsParams(document.uri, list);
    params.setVersion(document.version);
    return params;
  }

  /** One scheduled or running compile of a document. */
  private static final class Pending {
    final AtomicBoolean cancelled = new AtomicBoolean();
    volatile ScheduledFuture<?> timer;

    void cancel() {
      cancelled.set(true);
      ScheduledFuture<?> scheduled = timer;
      if (scheduled != null) {
        scheduled.cancel(false);
      }
    }
  }
}
