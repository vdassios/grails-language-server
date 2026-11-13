package io.github.vdassios.grailsls.api;

/**
 * Compiles Groovy for one module. Implemented by the {@code analysis} layer and loaded inside the module's class loader,
 * next to the project's own Groovy and Grails jars. Only types of this package and the JDK cross the boundary.
 *
 * <p>Calls for one document are not concurrent; calls for different documents may be.
 */
public interface Analyzer extends AutoCloseable {
  /** Binary name of the implementation, loaded by name in each module loader. */
  String IMPLEMENTATION = "io.github.vdassios.grailsls.analysis.GroovyAnalyzer";

  /** Version of the Groovy the module loader provides (the project's). */
  String groovyVersion();

  /**
   * Compiles one document to canonicalization, with the classes it uses pulled in from source through {@code lookup}.
   * The result is kept for queries on that document until the next compile of it or {@link #forget}.
   */
  CompileResult compile(CompileRequest request);

  /** A class as the last compile of {@code documentPath} sees it, or null when that compile does not contain it. */
  CompiledClass compiledClass(String documentPath, String className);

  /** Drops the result kept for a document. */
  void forget(String documentPath);

  /** Drops every kept result. The module loader is closed after this. */
  @Override
  void close();
}
