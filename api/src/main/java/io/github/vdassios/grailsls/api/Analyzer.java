package io.github.vdassios.grailsls.api;

import java.util.List;

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

  // Queries on the last compile of a document. Positions are 0-based. Each returns null (or an empty list) when the
  // document has no kept compile or nothing is found at the position.

  /** Where the class, member or variable at a position is declared; null when it has no source (jars, the JDK). */
  Location definition(String documentPath, int line, int column);

  /** Markdown describing the class, member or variable at a position: its signature and documentation. */
  String hover(String documentPath, int line, int column);

  /** Classes of the document and their members, in source order. Members added by transformations are left out. */
  List<Symbol> symbols(String documentPath);

  /**
   * Completion at a position of {@code request.text}, the document's current text. Answered from the kept compile when
   * it is of that text. When nothing is found there (an unfinished expression such as {@code book.} does not parse),
   * a copy with a placeholder name at the position is compiled for the answer and not kept.
   */
  CompletionList completion(CompileRequest request, int line, int column);

  /** Signature and documentation of an item of the document's last completion; null once a compile has replaced it. */
  CompletionDetail resolveCompletion(String documentPath, int id);

  /**
   * The overloads of the method called around a position of {@code request.text}; null outside a call. Like
   * {@link #completion}, an unfinished call is answered from a copy with the closing parenthesis added.
   */
  SignatureHelp signatureHelp(CompileRequest request, int line, int column);

  /** Drops the result kept for a document. */
  void forget(String documentPath);

  /** Drops every kept result. The module loader is closed after this. */
  @Override
  void close();
}
