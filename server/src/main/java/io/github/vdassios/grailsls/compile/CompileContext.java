package io.github.vdassios.grailsls.compile;

import java.beans.Introspector;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Function;

import io.github.vdassios.grailsls.api.Analyzer;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;

/**
 * Compiles the files of one source set of one module: its class loader, the analyzer loaded in it, and the thread its
 * compiles run on. All three are created on the first compile and released by {@link #close}, so a closed context
 * leaves nothing that keeps its loader (and the project's Groovy with it) alive.
 */
public final class CompileContext implements AutoCloseable {
  static {
    // Groovy 2.5 to 4 keep each class's metadata in a java.lang.ClassValue by default. An entry on a JDK class
    // (String, Object) then references the module's Groovy, and through it the module loader, forever: Grails's
    // transformations, written in Groovy, create such entries during every compile. The other implementation
    // keeps the metadata inside the module's own Groovy. Read once by each loader's Groovy, before it first runs.
    System.setProperty("groovy.use.classvalue", "false");
  }

  /** For messages: {@code :app main}. */
  public final String name;
  /** Source roots whose files this context compiles. */
  public final List<Path> ownRoots;
  /** Roots the declaration index answers from, own roots first. */
  public final List<Path> searchRoots;
  private final List<Path> classpath;
  private final Map<String, Path> javaClasses;
  private final Map<String, List<Path>> resources;
  /** {@code .java} files of this source set whose classes are not in its class output (not built yet). */
  public final int javaFilesNotBuilt;
  /** Gradle task that builds this source set's classes, e.g. {@code :app:testClasses}. */
  public final String classesTask;
  /** ANTLR's runtime, shared with the other contexts of the same Groovy jar; null when not shared. Owned by the workspace. */
  private final AntlrLoader antlr;
  /** Where failures go: {@code true} to show to the user, {@code false} only to log. */
  private final BiConsumer<Boolean, String> reporter;
  /** Whether a failed compile has been shown to the user: later ones are only logged. */
  private final AtomicBoolean compileFailureShown = new AtomicBoolean();

  private ExecutorService thread;
  private ModuleClassLoader loader;
  private Analyzer analyzer;
  private boolean closed;

  CompileContext(String name, List<Path> ownRoots, List<Path> searchRoots, List<Path> classpath, Map<String, Path> javaClasses,
      Map<String, List<Path>> resources, int javaFilesNotBuilt, String classesTask, AntlrLoader antlr, BiConsumer<Boolean, String> reporter) {
    this.name = name;
    this.ownRoots = List.copyOf(ownRoots);
    this.searchRoots = List.copyOf(searchRoots);
    this.classpath = List.copyOf(classpath);
    this.javaClasses = Map.copyOf(javaClasses);
    this.resources = Map.copyOf(resources);
    this.javaFilesNotBuilt = javaFilesNotBuilt;
    this.classesTask = classesTask;
    this.antlr = antlr;
    this.reporter = reporter;
  }

  /** Classpath entries of the loader, analysis jar first. For tests and status. */
  public List<Path> classpath() {
    return classpath;
  }

  /** Number of classes served from the class output: those of Java sources and of extension modules. */
  public int javaClasses() {
    return javaClasses.size();
  }

  /** Runs the compile on this context's thread. */
  public CompletableFuture<CompileResult> compile(CompileRequest request) {
    ExecutorService executor;
    synchronized (this) {
      if (closed) {
        return CompletableFuture.failedFuture(new IllegalStateException(name + " is closed"));
      }
      if (thread == null) {
        start();
      }
      executor = thread;
    }
    return CompletableFuture.supplyAsync(() -> CompilerOutput.muted(() -> analyzer().compile(request)), executor);
  }

  /** Runs a query on the analyzer on this context's thread, after the compiles submitted before it. */
  public <T> CompletableFuture<T> query(Function<Analyzer, T> query) {
    ExecutorService executor;
    synchronized (this) {
      if (closed) {
        return CompletableFuture.failedFuture(new IllegalStateException(name + " is closed"));
      }
      if (thread == null) {
        start();
      }
      executor = thread;
    }
    return CompletableFuture.supplyAsync(() -> CompilerOutput.muted(() -> query.apply(analyzer())), executor);
  }

  /**
   * A compile of {@code path} threw (the analysis layer did not load, a bug): the file has no diagnostics and its
   * queries find nothing. The first failure is shown to the user, later ones are only logged.
   */
  void compileFailed(String path, Throwable error) {
    Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    reporter.accept(compileFailureShown.compareAndSet(false, true), name + ": the compile of " + path + " failed: " + cause);
  }

  /** Drops what the analyzer keeps for a closed document. */
  public synchronized void forget(String path) {
    if (analyzer != null && thread != null) {
      Analyzer current = analyzer;
      thread.execute(() -> current.forget(path));
    }
  }

  /** The analyzer, for queries; created on first use. Call from {@link #compile} results only. */
  public synchronized Analyzer analyzer() {
    if (closed) {
      throw new IllegalStateException(name + " is closed");
    }
    if (analyzer == null) {
      if (thread == null) {
        start();
      }
      try {
        Class<?> type = Class.forName(Analyzer.IMPLEMENTATION, true, loader);
        BiConsumer<Boolean, String> analysisReporter = (show, message) -> reporter.accept(show, name + ": " + message);
        analyzer = (Analyzer) type.getConstructor(BiConsumer.class).newInstance(analysisReporter);
      } catch (ReflectiveOperationException | LinkageError e) {
        throw new IllegalStateException("cannot load the analysis layer in " + name + ": " + e, e);
      }
    }
    return analyzer;
  }

  /** The module loader, or null before the first compile and after {@link #close}. For tests. */
  synchronized ClassLoader loader() {
    return loader;
  }

  private void start() {
    List<URL> urls = new ArrayList<>();
    for (Path entry : classpath) {
      urls.add(url(entry));
    }
    loader = new ModuleClassLoader(name, urls, javaClasses, resources, antlr);
    ModuleClassLoader threadLoader = loader;
    thread = Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "grails-compile " + name);
      t.setDaemon(true);
      t.setContextClassLoader(threadLoader);
      return t;
    });
  }

  /**
   * A class path entry as a URL. A class directory ends in a slash even when it is not built yet: without one the URL
   * class path takes it for a jar, fails to open it and drops it for good, so classes built later would never show.
   */
  static URL url(Path entry) {
    String name = entry.getFileName() == null ? "" : entry.getFileName().toString();
    boolean archive = Files.isRegularFile(entry) || name.endsWith(".jar") || name.endsWith(".zip");
    String uri = entry.toUri().toString();
    try {
      return new URL(archive || uri.endsWith("/") ? uri : uri + "/");
    } catch (MalformedURLException e) {
      throw new IllegalArgumentException(entry.toString(), e);
    }
  }

  /** Stops the thread (and the thread-locals project code left on it), closes the loader and drops JDK caches of its classes. */
  @Override
  public void close() {
    ExecutorService executor;
    Analyzer current;
    ModuleClassLoader closing;
    synchronized (this) {
      closed = true;
      executor = thread;
      current = analyzer;
      closing = loader;
      thread = null;
      analyzer = null;
      loader = null;
    }
    if (executor == null) {
      return;
    }
    if (current != null) {
      executor.execute(current::close);
    }
    executor.shutdown();
    try {
      executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    try {
      closing.close();
    } catch (IOException e) {
      // nothing left to release
    }
    // java.beans caches introspected classes (Grails's transformations introspect their own) behind soft
    // references: they would keep the module's classes until the heap runs short
    Introspector.flushCaches();
  }
}
