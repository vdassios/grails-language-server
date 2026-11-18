package io.github.vdassios.grailsls.analysis;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.ErrorCollector;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.ResolveVisitor;
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
  private final ParserCache parserCache = new ParserCache(GroovyAnalyzer.class.getClassLoader());
  /** Parent of each compile's class loader: the module loader that loaded this class. */
  private final ClassLoader parent;
  private final LateTransforms lateTransforms;
  /**
   * The compiles' target directory, emptied before each compile. The server writes no class files, but Grails's
   * global transformation writes {@code META-INF/grails-plugin.xml} and {@code grails.factories} there, and without
   * one into {@code build/classes/main} below the working directory, i.e. into the user's project, where the plugin
   * file grew with every artefact compiled and was read and written again by each compile.
   */
  private final Path output;

  public GroovyAnalyzer() {
    this(GroovyAnalyzer.class.getClassLoader());
  }

  /** With another parent loader, for tests. */
  GroovyAnalyzer(ClassLoader parent) {
    this.parent = parent;
    this.lateTransforms = new LateTransforms(parent);
    try {
      this.output = Files.createTempDirectory("grails-ls-output");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
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

  /** Compiles a document to its last phase ({@link #lastPhase}), each phase only if the request is not cancelled. */
  private Kept run(CompileRequest request, List<Diagnostic> diagnostics) {
    // sources a class file named before they were converted (PathResolver): compiled again with them there from the
    // start. Each further compile has at least one more of them, so this ends
    Set<String> early = new LinkedHashSet<>();
    while (true) {
      List<Diagnostic> found = new ArrayList<>();
      int known = early.size();
      Kept kept;
      try {
        kept = parserCache.parsing(() -> runUnit(request, found, early));
      } finally {
        caches.clear();
        parserCache.trim();
      }
      if (kept.cancelled || early.size() == known) {
        diagnostics.addAll(found);
        return kept;
      }
    }
  }

  private Kept runUnit(CompileRequest request, List<Diagnostic> diagnostics, Set<String> early) {
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    empty(output, false);
    config.setTargetDirectory(output.toFile());
    config.addCompilationCustomizers(new AstTestRemover(), new ParseTreeRemover());
    // attaches Groovydoc comments to nodes for hover (CompilerConfiguration.GROOVYDOC, Groovy 3+; 2.5 has none)
    config.getOptimizationOptions().put("groovydoc", true);
    // a name without a class file is not loaded as a class next: of the thousands of names the resolver tries per
    // compile, almost all exist nowhere, and each such load searched every jar again and threw through four loaders
    config.getOptimizationOptions().put("classLoaderResolving", false);
    lateTransforms.disable(config);
    GroovyClassLoader loader = new GroovyClassLoader(parent, config, false);
    // source lookup goes through the resolver only, never through the loader (no URL-named sources)
    loader.setResourceLoader(name -> null);
    DocumentUnit unit = new DocumentUnit(config, loader);
    lateTransforms.add(unit);
    SourceUnit document = unit.addSource(source(request.path, request.text, config, loader, unit.getErrorCollector()));
    PathResolver resolver = new PathResolver(request.lookup, document);
    unit.setClassNodeResolver(resolver);
    early.forEach(path -> resolver.add(path, unit));

    // the last phase depends on the document's own annotations, known once it is converted: decided then and kept,
    // because transformations add statically compiled members to classes that are not (Grails's controller traits)
    int last = Phases.CONVERSION;
    int phase = Phases.INITIALIZATION;
    Injections injections = null;
    for (; phase <= last; phase++) {
      if (request.cancelled.getAsBoolean()) {
        return new Kept(unit, request.text, document.getSource().getURI(), request.lookup, resolver.sources(), phase - 1, true);
      }
      try {
        unit.compile(phase);
      } catch (CompilationFailedException e) {
        // its messages are in the error collector
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        diagnostics.add(new Diagnostic(request.path, 0, 0, 0, 0, Diagnostic.ERROR, "internal compiler error in phase " + phase + ": " + e));
        break;
      }
      if (phase == Phases.CONVERSION) {
        last = lastPhase(document);
        injections = Injections.queue(document, request.lookup, resolver, unit);
      }
    }
    if (early.addAll(resolver.early())) {
      return new Kept(unit, request.text, document.getSource().getURI(), request.lookup, resolver.sources(), Math.min(phase, last), false);
    }
    if (injections != null) {
      injections.mark(unit);
    }
    collect(unit.getErrorCollector(), TypeBounds.stale(document, unit.getErrorCollector(), config, loader), request.path, diagnostics);
    if (document.getAST() != null) {
      try {
        diagnostics.addAll(Gorm.finderProblems(document.getAST(), request.path));
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        // a class the check reads cannot be loaded: no warnings rather than a failed compile
      }
      try {
        diagnostics.addAll(conventions(request.lookup, request.path).problems(document.getAST()));
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        // no hints rather than a failed compile
      }
    }
    return new Kept(unit, request.text, document.getSource().getURI(), request.lookup, resolver.sources(), Math.min(phase, last), false);
  }

  /**
   * Canonicalization, where Grails's transformations have run; instruction selection when the document has statically
   * compiled code (C2): the static type checker runs there and records the types it infers on the nodes.
   */
  private static int lastPhase(SourceUnit document) {
    ModuleNode module = document.getAST();
    if (module != null) {
      for (ClassNode type : module.getClasses()) {
        if (isStatic(type) || type.getMethods().stream().anyMatch(GroovyAnalyzer::isStatic)) {
          return Phases.INSTRUCTION_SELECTION;
        }
      }
    }
    return Phases.CANONICALIZATION;
  }

  /** Marked {@code @CompileStatic} or {@code @GrailsCompileStatic}, by simple name: annotations are not resolved yet. */
  private static boolean isStatic(AnnotatedNode node) {
    for (AnnotationNode annotation : node.getAnnotations()) {
      String name = annotation.getClassNode().getNameWithoutPackage();
      if (name.equals("CompileStatic") || name.equals("GrailsCompileStatic")) {
        return true;
      }
    }
    return false;
  }

  /** The last phase the document's kept compile ran, or -1 when nothing is kept. For tests. */
  int phase(String documentPath) {
    Kept kept = units.get(documentPath);
    return kept == null ? -1 : kept.phase;
  }

  /** The kept compile of a document, or null. For tests. */
  CompilationUnit unit(String documentPath) {
    Kept kept = units.get(documentPath);
    return kept == null ? null : kept.unit;
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
    return kept == null ? null : Navigation.definition(kept.ast(), kept.uri, kept.lookup, conventions(kept.lookup, documentPath), kept.text, line, column);
  }

  @Override
  public String hover(String documentPath, int line, int column) {
    Kept kept = units.get(documentPath);
    return kept == null ? null : Navigation.hover(kept.ast(), kept.uri, kept.lookup, kept.text, line, column);
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
    String typed = lineBefore(request.text, line, column);
    Conventions conventions = conventions(request.lookup, request.path);
    Completions found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, conventions, line, column, typed);
    if (found == null) {
      kept = compileCopy(request, line, column, Completions.placeholder(typed));
      found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, conventions, line, column, typed);
    }
    String close = Completions.closeString(typed);
    if (found == null && close != null) {
      // inside a string not closed yet: render(view: 'sh
      kept = compileCopy(request, line, column, Completions.placeholder(typed) + close);
      found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, conventions, line, column, typed);
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
    return declaration == null ? null : Completions.detail(declaration, last.compile.ast(), last.compile.lookup);
  }

  @Override
  public SignatureHelp signatureHelp(CompileRequest request, int line, int column) {
    Kept kept = current(request);
    if (kept != null && kept.ast().getNodeAtLineAndColumn(kept.uri, line, column) != null) {
      return Signatures.at(kept.ast(), kept.uri, kept.lookup, line, column);
    }
    // an unfinished call, "render(", does not parse
    Kept copy = compileCopy(request, line, column, ")");
    return copy == null ? null : Signatures.at(copy.ast(), copy.uri, copy.lookup, line, column);
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
    return parserCache.parsing(() -> parse(paths));
  }

  /** Conventions for a document: other files are read from their open text, else from disk, and parsed only. */
  private Conventions conventions(SourceLookup lookup, String documentPath) {
    return new Conventions(lookup, documentPath, file -> parserCache.parsing(() -> parsedClasses(file, lookup == null ? null : lookup.openText(file))));
  }

  /** The classes a file declares, from a parse only (as {@link #declarations}); empty when it does not parse. */
  private static List<ClassNode> parsedClasses(String path, String text) {
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    GroovyClassLoader loader = new GroovyClassLoader(GroovyAnalyzer.class.getClassLoader(), config, false);
    SourceUnit source = text == null ? new SourceUnit(new File(path), config, loader, new ErrorCollector(config)) : new SourceUnit(path, text, config, loader, new ErrorCollector(config));
    try {
      source.parse();
      source.completePhase();
      source.convert();
      return source.getAST().getClasses();
    } catch (GroovyBugError | RuntimeException | LinkageError e) {
      return List.of();
    }
  }

  private static Map<String, List<Symbol>> parse(List<String> paths) {
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
    empty(output, true);
  }

  /** Deletes what is below the directory, and the directory itself if asked; what cannot be deleted is left. */
  private static void empty(Path directory, boolean itself) {
    try (var paths = Files.walk(directory)) {
      paths.sorted(Comparator.reverseOrder()).filter(path -> itself || !path.equals(directory)).forEach(path -> path.toFile().delete());
    } catch (IOException | UncheckedIOException e) {
      // already gone, or not readable: the next compile writes over it
    }
  }

  /**
   * A source of in-memory text, named by its file path (Grails decides artefacts by it) and identified by the file's
   * URI, as a source read from disk is.
   */
  static SourceUnit source(String path, String text, CompilerConfiguration config, GroovyClassLoader loader, ErrorCollector errors) {
    return new SourceUnit(path, new StringReaderSourceWithURI(text, new File(path).toURI(), config), config, loader, errors);
  }

  /**
   * Groovy reports some errors once per generated node (a property's field and accessors): keep one of each. Errors
   * in {@code stale} no longer hold ({@link TypeBounds}).
   */
  @SuppressWarnings("unchecked") // Groovy 2.5 returns raw lists
  private static void collect(ErrorCollector collector, Set<Object> stale, String documentPath, List<Diagnostic> out) {
    Set<String> seen = new HashSet<>();
    for (Diagnostic diagnostic : out) {
      seen.add(diagnostic.toString());
    }
    List<Object> messages = new ArrayList<>();
    if (collector.getErrors() != null) {
      for (Object error : (List<Object>) collector.getErrors()) {
        if (!stale.contains(error)) {
          messages.add(error);
        }
      }
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
    /** Finds the source of classes the compile read from class files, for definition and hover. */
    final SourceLookup lookup;
    /** Sources the compile read: the document and those pulled in. */
    final int sources;
    /** The last phase compiled. */
    final int phase;
    final boolean cancelled;
    private ASTNodeVisitor ast;

    Kept(CompilationUnit unit, String text, URI uri, SourceLookup lookup, int sources, int phase, boolean cancelled) {
      this.unit = unit;
      this.text = text;
      this.uri = uri;
      this.lookup = lookup;
      this.sources = sources;
      this.phase = phase;
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
    /**
     * Groovy 4 and 5 restart resolving when a type names a source not yet in the unit, as each one {@link PathResolver}
     * adds does, and a restart from inside a declaration's type leaves this flag of the resolver set: the next class
     * resolved then resolves the type of every variable it reads, and a property typed by a superclass's type
     * parameter fails ("unable to resolve class ID"). Plain groovyc fails the same way with sources on its source path.
     * Null when the field is missing.
     */
    private static final Field DECLARATION_CHECK = declarationCheck();

    DocumentUnit(CompilerConfiguration config, GroovyClassLoader loader) {
      super(config, null, loader);
      this.errorCollector = new ErrorCollector(config) {
        private static final long serialVersionUID = 1L;

        @Override
        protected void failIfErrors() {}
      };
    }

    /** True when queued sources were just added, as after each restart: no declaration is being resolved then. */
    @Override
    protected boolean dequeued() throws CompilationFailedException {
      boolean dequeued = super.dequeued();
      if (dequeued && DECLARATION_CHECK != null) {
        try {
          DECLARATION_CHECK.setBoolean(resolveVisitor, false);
        } catch (IllegalAccessException e) {
          // cannot happen: made accessible
        }
      }
      return dequeued;
    }

    private static Field declarationCheck() {
      try {
        Field field = ResolveVisitor.class.getDeclaredField("checkingVariableTypeInDeclaration");
        field.setAccessible(true);
        return field;
      } catch (ReflectiveOperationException | RuntimeException e) {
        return null;
      }
    }
  }
}
