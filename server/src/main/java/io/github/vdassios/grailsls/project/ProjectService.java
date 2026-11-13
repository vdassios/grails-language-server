package io.github.vdassios.grailsls.project;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;

import io.github.vdassios.grailsls.StatusParams;

/**
 * Resolves the project model in the background, one run at a time, and reports progress as {@code grails/status}.
 * Does nothing for an untrusted workspace: resolving runs the project's build scripts. Each new model goes to
 * {@code onModel} (state {@code indexing}) before the project is reported ready.
 */
public final class ProjectService {
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
  private volatile ProjectModel model;

  /**
   * @param onModel takes each resolved model (indexes it, sets up compiling) and returns detail for the ready message,
   *                e.g. ", 2 Java files not built", or an empty string
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

  public synchronized void stop() {
    if (running != null) {
      running.cancel(true);
    }
    executor.shutdownNow();
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
    try {
      ProjectModel resolved = bridge.resolve(workspaceRoot, force);
      model = resolved;
      status.accept(new StatusParams(StatusParams.INDEXING, summary(resolved)));
      String detail = onModel.apply(resolved);
      status.accept(new StatusParams(StatusParams.READY, summary(resolved) + detail));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (BridgeException e) {
      status.accept(new StatusParams(StatusParams.FAILED, e.getMessage()));
    } catch (RuntimeException e) {
      status.accept(new StatusParams(StatusParams.FAILED, e.toString()));
    }
  }

  static String summary(ProjectModel model) {
    int modules = model.modules.size();
    long unresolved = model.modules.stream().mapToLong(m -> m.errors.size()).sum();
    return modules + (modules == 1 ? " module" : " modules") + (model.fromCache ? " (cached)" : "")
        + (unresolved > 0 ? ", " + unresolved + " unresolved " + (unresolved == 1 ? "dependency" : "dependencies") : "");
  }
}
