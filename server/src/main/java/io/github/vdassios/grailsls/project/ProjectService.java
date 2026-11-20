package io.github.vdassios.grailsls.project;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.vdassios.grailsls.StatusParams;

/**
 * Resolves the project model in the background, one run at a time, and reports progress as {@code grails/status}.
 * Does nothing for an untrusted workspace: resolving runs the project's build scripts. Each new model goes to
 * {@code onModel} (state {@code indexing}) before the project is reported ready.
 */
public final class ProjectService {
  static final int STOP_WAIT_SECONDS = 5;
  static final String UNTRUSTED = "workspace not trusted: the client must send initializationOptions.trusted = true";

  private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "grails-project");
    thread.setDaemon(true);
    return thread;
  });
  private final GradleBridge bridge;
  private final Path workspaceRoot;
  private final boolean trusted;
  private final Consumer<StatusParams> status;
  private final Function<ProjectModel, String> onModel;
  private Future<?> running;
  /** Whether the cache has been pruned: once, before the first resolution. */
  private boolean pruned;
  private volatile ProjectModel model;

  /**
   * @param onModel takes each resolved model (indexes it, sets up compiling) and returns detail for the ready message,
   *                e.g. ", 2 Java files not built", or an empty string; throws {@link Unsupported} to report it failed
   */
  public ProjectService(GradleBridge bridge, Path workspaceRoot, boolean trusted, Consumer<StatusParams> status, Function<ProjectModel, String> onModel) {
    this.bridge = bridge;
    this.workspaceRoot = workspaceRoot;
    this.trusted = trusted;
    this.status = status;
    this.onModel = onModel;
  }

  /** The last model resolved, or null. */
  public ProjectModel model() {
    return model;
  }

  /** Resolves the project, from the cache when the build files are unchanged. */
  public CompletableFuture<Void> start() {
    return submit(false);
  }

  /** Cancels a running resolution and resolves again, ignoring the cache ({@code grails/reloadProject}). */
  public CompletableFuture<Void> reload() {
    return submit(true);
  }

  /**
   * Downloads the sources jars the project's libraries lack ({@code grails/downloadSources}), after any resolution
   * still running. Only ever on request, and never for an untrusted workspace: it runs the build scripts and reaches
   * the network. A failure is the report's message; the project's state does not change.
   */
  public synchronized CompletableFuture<SourcesReport> downloadSources() {
    if (!trusted || workspaceRoot == null) {
      return CompletableFuture.completedFuture(new SourcesReport(0, 0, 0, trusted ? "no workspace folder" : UNTRUSTED));
    }
    CompletableFuture<SourcesReport> done = new CompletableFuture<>();
    executor.submit(() -> {
      try {
        done.complete(bridge.downloadSources(workspaceRoot));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        done.complete(new SourcesReport(0, 0, 0, "cancelled"));
      } catch (BridgeException e) {
        done.complete(new SourcesReport(0, 0, 0, e.getMessage()));
      } catch (Throwable e) {
        done.complete(new SourcesReport(0, 0, 0, e.toString())); // an Error too: the request must complete
      }
    });
    return done;
  }

  /**
   * Cancels the running resolution or download and waits, at most {@link #STOP_WAIT_SECONDS}, until it has killed its
   * Gradle process: the server may exit right after.
   */
  public synchronized void stop() {
    if (running != null) {
      running.cancel(true);
    }
    executor.shutdownNow();
    try {
      executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private synchronized CompletableFuture<Void> submit(boolean force) {
    CompletableFuture<Void> done = new CompletableFuture<>();
    if (!trusted) {
      status.accept(new StatusParams(StatusParams.FAILED, UNTRUSTED));
      done.complete(null);
      return done;
    }
    if (workspaceRoot == null) {
      status.accept(new StatusParams(StatusParams.FAILED, "no workspace folder"));
      done.complete(null);
      return done;
    }
    if (running != null) {
      running.cancel(true); // kills its Gradle process; a cancelled run reports nothing
    }
    running = executor.submit(() -> {
      try {
        resolve(force);
      } finally {
        done.complete(null);
      }
    });
    return done;
  }

  private void resolve(boolean force) {
    status.accept(new StatusParams(StatusParams.RESOLVING, "reading the Gradle build in " + workspaceRoot));
    if (!pruned) {
      pruned = true;
      bridge.prune(); // on this thread: never while this server reads or writes its own entries
    }
    try {
      ProjectModel resolved = bridge.resolve(workspaceRoot, force);
      String unsupported = unsupportedGroovy(resolved);
      if (unsupported != null) {
        status.accept(new StatusParams(StatusParams.FAILED, unsupported));
        return;
      }
      model = resolved;
      status.accept(new StatusParams(StatusParams.INDEXING, summary(resolved)));
      String detail = onModel.apply(resolved);
      status.accept(new StatusParams(StatusParams.READY, summary(resolved) + detail));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (BridgeException e) {
      status.accept(new StatusParams(StatusParams.FAILED, e.getMessage()));
    } catch (Unsupported e) {
      status.accept(new StatusParams(StatusParams.FAILED, e.getMessage()));
    } catch (Throwable e) {
      // an Error too (a class that fails to link, a stack overflow): the state must not stay at indexing
      status.accept(new StatusParams(StatusParams.FAILED, e.toString()));
    }
  }

  /**
   * Why the model cannot be served, or null: a module on Groovy older than 2.5 (Grails 3), whose compiler the analysis
   * layer is not built for. A version that does not parse is let through.
   */
  static String unsupportedGroovy(ProjectModel model) {
    for (ModuleRecord module : model.modules) {
      String[] parts = module.groovyVersion == null ? new String[0] : module.groovyVersion.split("[^0-9]+");
      if (parts.length >= 2 && !parts[0].isEmpty()) {
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        if (major < 2 || major == 2 && minor < 5) {
          return "Groovy " + module.groovyVersion + " (module " + module.path + ") is not supported: the server needs Groovy 2.5 or later (Grails 4 or later)";
        }
      }
    }
    return null;
  }

  /** Thrown by {@code onModel} when the project cannot be served; its message is the reason reported as {@code failed}. */
  public static final class Unsupported extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public Unsupported(String message) {
      super(message);
    }
  }

  static String summary(ProjectModel model) {
    int modules = model.modules.size();
    long unresolved = model.modules.stream().mapToLong(m -> m.errors.size()).sum();
    return modules + (modules == 1 ? " module" : " modules") + (model.fromCache ? " (cached)" : "")
        + (unresolved > 0 ? ", " + unresolved + " unresolved " + (unresolved == 1 ? "dependency" : "dependencies") : "");
  }
}
