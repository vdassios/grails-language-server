package io.github.vdassios.grailsls.analysis;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.ExceptionMessage;
import org.codehaus.groovy.control.messages.Message;
import org.codehaus.groovy.control.messages.SimpleMessage;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.syntax.SyntaxException;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
import io.github.vdassios.grailsls.api.Analyzer;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.CompletionDetail;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.SignatureHelp;
import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.control.io.StringReaderSourceWithURI;

/**
 * Compiles one document per {@link CompilationUnit}, in the module loader that loaded this class: the project's own
 * Groovy, its AST transformations included. Classes the document uses come from source through {@link PathResolver},
 * or from the loader (jars, class directories, compiled Java).
 */
public final class GroovyAnalyzer implements Analyzer {
  private final Map<String, Kept> units = new ConcurrentHashMap<>();
  /** The last completion per document, for resolve; dropped by the next compile of the document. */
  private final Map<String, LastCompletion> completions = new ConcurrentHashMap<>();
  private final TransformCaches caches = new TransformCaches(GroovyAnalyzer.class.getClassLoader());

  public GroovyAnalyzer() {
    // @Grab would download and load code at compile time
    System.setProperty("groovy.grape.enable", "false");
  }

  @Override
  public String groovyVersion() {
    return GroovySystem.getVersion();
  }

  @Override
  public CompileResult compile(CompileRequest request) {
    long start = System.nanoTime();
    units.remove(request.path);
    completions.remove(request.path);
    List<Diagnostic> diagnostics = new ArrayList<>();
    Kept kept = run(request, diagnostics);
    if (kept.cancelled) {
      return new CompileResult(List.of(), true, kept.sources, millisSince(start));
    }
    units.put(request.path, kept);
    return new CompileResult(diagnostics, false, kept.sources, millisSince(start));
  }

  /** Compiles a document to canonicalization, the next phase only if the request is not cancelled. */
  private Kept run(CompileRequest request, List<Diagnostic> diagnostics) {
    try {
      return runUnit(request, diagnostics);
    } finally {
      caches.clear();
    }
  }

  private Kept runUnit(CompileRequest request, List<Diagnostic> diagnostics) {
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    config.addCompilationCustomizers(new AstTestRemover());
    // attaches Groovydoc comments to nodes for hover (CompilerConfiguration.GROOVYDOC, Groovy 3+; 2.5 has none)
    config.getOptimizationOptions().put("groovydoc", true);
    GroovyClassLoader loader = new GroovyClassLoader(GroovyAnalyzer.class.getClassLoader(), config, false);
    // source lookup goes through the resolver only, never through the loader (no URL-named sources)
    loader.setResourceLoader(name -> null);
    DocumentUnit unit = new DocumentUnit(config, loader);
    SourceUnit document = unit.addSource(source(request.path, request.text, config, loader, unit.getErrorCollector()));
    PathResolver resolver = new PathResolver(request.lookup, document);
    unit.setClassNodeResolver(resolver);

    for (int phase = Phases.INITIALIZATION; phase <= Phases.CANONICALIZATION; phase++) {
      if (request.cancelled.getAsBoolean()) {
        return new Kept(unit, request.text, document.getSource().getURI(), resolver.sources(), true);
      }
      try {
        unit.compile(phase);
      } catch (CompilationFailedException e) {
        // its messages are in the error collector
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        diagnostics.add(new Diagnostic(request.path, 0, 0, 0, 0, Diagnostic.ERROR, "internal compiler error in phase " + phase + ": " + e));
        break;
      }
    }
    collect(unit.getErrorCollector(), request.path, diagnostics);
    return new Kept(unit, request.text, document.getSource().getURI(), resolver.sources(), false);
  }

  @Override
  public CompiledClass compiledClass(String documentPath, String className) {
    Kept kept = units.get(documentPath);
    ClassNode node = kept == null ? null : kept.unit.getAST().getClass(className);
    if (node == null) {
      return null;
    }
    List<String> interfaces = new ArrayList<>();
    for (ClassNode type : node.getAllInterfaces()) {
      interfaces.add(type.getName());
    }
    TreeSet<String> methods = new TreeSet<>();
    for (MethodNode method : node.getMethods()) {
      methods.add(method.getName());
    }
    SourceUnit source = node.getModule() == null ? null : node.getModule().getContext();
    return new CompiledClass(node.getName(), source == null ? null : source.getName(), interfaces, new ArrayList<>(methods));
  }

  @Override
  public Location definition(String documentPath, int line, int column) {
    Kept kept = units.get(documentPath);
    return kept == null ? null : Navigation.definition(kept.ast(), kept.uri, line, column);
  }

  @Override
  public String hover(String documentPath, int line, int column) {
    Kept kept = units.get(documentPath);
    return kept == null ? null : Navigation.hover(kept.ast(), kept.uri, line, column);
  }

  @Override
  public List<Symbol> symbols(String documentPath) {
    Kept kept = units.get(documentPath);
    return kept == null ? List.of() : Navigation.symbols(kept.ast(), kept.uri);
  }

  @Override
  public CompletionList completion(CompileRequest request, int line, int column) {
    ClasspathTypes types = sourceTypes(request.lookup);
    Kept kept = current(request);
    Completions found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, line, column);
    if (found == null) {
      kept = compileCopy(request, line, column, Completions.placeholder(lineBefore(request.text, line, column)));
      found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, line, column);
    }
    if (found == null) {
      completions.remove(request.path);
      return CompletionList.EMPTY;
    }
    completions.put(request.path, new LastCompletion(found, kept));
    return found.list();
  }

  @Override
  public CompletionDetail resolveCompletion(String documentPath, int id) {
    LastCompletion last = completions.get(documentPath);
    ASTNode declaration = last == null ? null : last.completions.declaration(id);
    return declaration == null ? null : Completions.detail(declaration, last.compile.ast());
  }

  @Override
  public SignatureHelp signatureHelp(CompileRequest request, int line, int column) {
    Kept kept = current(request);
    if (kept != null && kept.ast().getNodeAtLineAndColumn(kept.uri, line, column) != null) {
      return Signatures.at(kept.ast(), kept.uri, line, column);
    }
    // an unfinished call, "render(", does not parse
    Kept copy = compileCopy(request, line, column, ")");
    return copy == null ? null : Signatures.at(copy.ast(), copy.uri, line, column);
  }

  /** The kept compile of a document, if it is of the request's text. */
  private Kept current(CompileRequest request) {
    Kept kept = units.get(request.path);
    return kept != null && kept.text.equals(request.text) ? kept : null;
  }

  /** Compiles the document with text inserted at a position, for one query; null when cancelled. Not kept. */
  private Kept compileCopy(CompileRequest request, int line, int column, String insert) {
    int offset = offset(request.text, line, column);
    String text = request.text.substring(0, offset) + insert + request.text.substring(offset);
    Kept copy = run(new CompileRequest(request.path, text, request.lookup, request.cancelled), new ArrayList<>());
    return copy.cancelled ? null : copy;
  }

  /** The offset of a position in a text; a column past the end of its line is the end of the line. */
  private static int offset(String text, int line, int column) {
    int start = 0;
    for (int i = 0; i < line; i++) {
      int next = text.indexOf('\n', start);
      if (next < 0) {
        return text.length();
      }
      start = next + 1;
    }
    int end = text.indexOf('\n', start);
    return Math.min(start + column, end < 0 ? text.length() : end);
  }

  private static String lineBefore(String text, int line, int column) {
    int offset = offset(text, line, column);
    return text.substring(text.lastIndexOf('\n', offset - 1) + 1, offset);
  }

  @Override
  public Map<String, List<Symbol>> declarations(List<String> paths) {
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    // a parse only, outside any compilation unit: no class is resolved, no transformation runs
    GroovyClassLoader loader = new GroovyClassLoader(GroovyAnalyzer.class.getClassLoader(), config, false);
    ASTNodeVisitor ast = new ASTNodeVisitor();
    Map<String, List<Symbol>> result = new LinkedHashMap<>();
    for (String path : paths) {
      File file = new File(path);
      SourceUnit source = new SourceUnit(file, config, loader, new ErrorCollector(config));
      try {
        source.parse();
        source.completePhase();
        source.convert();
        ast.visitSourceUnit(source);
        result.put(path, Navigation.symbols(ast, source.getSource().getURI()));
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        result.put(path, List.of()); // does not parse, or no file
      }
    }
    return result;
  }

  /** The top-level classes of the request's lookup, for completion; the compile's own classes come first anyway. */
  private static ClasspathTypes sourceTypes(SourceLookup lookup) {
    List<ClasspathTypes.Type> types = new ArrayList<>();
    Set<String> packages = new TreeSet<>();
    for (Symbol symbol : lookup.types()) {
      int dot = symbol.name.lastIndexOf('.');
      String packageName = dot < 0 ? "" : symbol.name.substring(0, dot);
      Completion.Kind kind = symbol.kind == Symbol.Kind.INTERFACE ? Completion.Kind.INTERFACE
          : symbol.kind == Symbol.Kind.ENUM ? Completion.Kind.ENUM : Completion.Kind.CLASS;
      types.add(new ClasspathTypes.Type(symbol.name, symbol.name.substring(dot + 1), packageName, kind));
      if (!packageName.isEmpty()) {
        packages.add(packageName);
      }
    }
    List<String> packageList = new ArrayList<>(packages);
    return new ClasspathTypes() {
      @Override
      public List<Type> types() {
        return types;
      }

      @Override
      public List<String> packages() {
        return packageList;
      }
    };
  }

  @Override
  public void forget(String documentPath) {
    units.remove(documentPath);
    completions.remove(documentPath);
  }

  @Override
  public void close() {
    units.clear();
    completions.clear();
  }

  /**
   * A source of in-memory text, named by its file path (Grails decides artefacts by it) and identified by the file's
   * URI, as a source read from disk is.
   */
  static SourceUnit source(String path, String text, CompilerConfiguration config, GroovyClassLoader loader, ErrorCollector errors) {
    return new SourceUnit(path, new StringReaderSourceWithURI(text, new File(path).toURI(), config), config, loader, errors);
  }

  /** Groovy reports some errors once per generated node (a property's field and accessors): keep one of each. */
  @SuppressWarnings("unchecked") // Groovy 2.5 returns raw lists
  private static void collect(ErrorCollector collector, String documentPath, List<Diagnostic> out) {
    Set<String> seen = new HashSet<>();
    for (Diagnostic diagnostic : out) {
      seen.add(diagnostic.toString());
    }
    List<Object> messages = new ArrayList<>();
    if (collector.getErrors() != null) {
      messages.addAll(collector.getErrors());
    }
    int warningsFrom = messages.size();
    if (collector.getWarnings() != null) {
      messages.addAll(collector.getWarnings());
    }
    boolean syntaxError = messages.stream().anyMatch(m -> m instanceof SyntaxErrorMessage);
    for (int i = 0; i < messages.size(); i++) {
      if (syntaxError && isParserException(messages.get(i))) {
        continue;
      }
      Diagnostic diagnostic = toDiagnostic((Message) messages.get(i), documentPath, i < warningsFrom ? Diagnostic.ERROR : Diagnostic.WARNING);
      if (seen.add(diagnostic.toString())) {
        out.add(diagnostic);
      }
    }
  }

  /**
   * Groovy 4's parser, not stopped by the first error, also reports its internal ANTLR exception (line 1, no
   * position): a repeat of the syntax error already reported.
   */
  private static boolean isParserException(Object message) {
    return message instanceof ExceptionMessage && ((ExceptionMessage) message).getCause() != null
        && ((ExceptionMessage) message).getCause().getClass().getName().startsWith("groovyjarjarantlr");
  }

  private static Diagnostic toDiagnostic(Message message, String documentPath, int severity) {
    if (message instanceof SyntaxErrorMessage) {
      SyntaxException cause = ((SyntaxErrorMessage) message).getCause();
      String path = cause.getSourceLocator() != null ? cause.getSourceLocator() : documentPath;
      int line = zeroBased(cause.getStartLine());
      int column = zeroBased(cause.getStartColumn());
      int endLine = Math.max(line, zeroBased(cause.getEndLine()));
      int endColumn = zeroBased(cause.getEndColumn());
      if (endLine == line && endColumn <= column) {
        endColumn = column + 1;
      }
      return new Diagnostic(path, line, column, endLine, endColumn, cause.isFatal() ? Diagnostic.ERROR : severity, cause.getOriginalMessage().trim());
    }
    String text = message instanceof ExceptionMessage ? String.valueOf(((ExceptionMessage) message).getCause())
        : message instanceof SimpleMessage ? ((SimpleMessage) message).getMessage() : message.toString();
    text = text.trim();
    return new Diagnostic(documentPath, 0, 0, 0, 1, severity, text);
  }

  /** Groovy counts from 1 and uses -1 for unknown. */
  private static int zeroBased(int groovy) {
    return Math.max(groovy - 1, 0);
  }

  private static long millisSince(long nanos) {
    return (System.nanoTime() - nanos) / 1_000_000;
  }

  /** A compile of a document, and its node lookup (of the document only), built on the first query. */
  private static final class Kept {
    final CompilationUnit unit;
    final String text;
    final URI uri;
    /** Sources the compile read: the document and those pulled in. */
    final int sources;
    final boolean cancelled;
    private ASTNodeVisitor ast;

    Kept(CompilationUnit unit, String text, URI uri, int sources, boolean cancelled) {
      this.unit = unit;
      this.text = text;
      this.uri = uri;
      this.sources = sources;
      this.cancelled = cancelled;
    }

    synchronized ASTNodeVisitor ast() {
      if (ast == null) {
        ast = new ASTNodeVisitor();
        ast.visitDocument(unit, uri);
      }
      return ast;
    }
  }

  private static final class LastCompletion {
    final Completions completions;
    final Kept compile;

    LastCompletion(Completions completions, Kept compile) {
      this.completions = completions;
      this.compile = compile;
    }
  }

  /** A unit whose errors do not stop it: the following phases still run, so one unresolved name hides nothing else. */
  private static final class DocumentUnit extends CompilationUnit {
    DocumentUnit(CompilerConfiguration config, GroovyClassLoader loader) {
      super(config, null, loader);
      this.errorCollector = new ErrorCollector(config) {
        private static final long serialVersionUID = 1L;

        @Override
        protected void failIfErrors() {}
      };
    }
  }
}
