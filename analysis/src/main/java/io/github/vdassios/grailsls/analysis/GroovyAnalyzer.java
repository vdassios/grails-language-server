package io.github.vdassios.grailsls.analysis;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
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
import java.util.function.BiConsumer;
import java.util.function.Function;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ImportNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.decompiled.AsmDecompiler;
import org.codehaus.groovy.ast.expr.Expression;
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
import io.github.vdassios.grailsls.api.CompletionDetail;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.SignatureHelp;
import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;

/**
 * Compiles one document per {@link CompilationUnit}, in the module loader that loaded this class: the project's own
 * Groovy, its AST transformations included. Classes the document uses come from source through {@link PathResolver},
 * or from the loader (jars, class directories, compiled Java). GSP pages go through {@link GspAnalyzer}.
 */
public final class GroovyAnalyzer implements Analyzer {
  /** The kept compile of each document; a page's is of its translation ({@link GspAnalyzer}). */
  final Map<String, Kept> units = new ConcurrentHashMap<>();
  /**
   * The last compile of each Groovy document whose text parsed, kept while later ones do not: completion after a dot
   * just typed, which does not parse, is answered from it when that is provably right ({@link TypedDot}).
   */
  private final Map<String, Kept> lastParsed = new ConcurrentHashMap<>();
  /** The last completion per document (a Groovy one, or a page's tags), for resolve; dropped by the next compile of the document. */
  final Map<String, LastCompletion> completions = new ConcurrentHashMap<>();
  /** Failures, to the server: shown to the user or logged, each once. */
  final Reports reports;
  private final TransformCaches caches;
  final ParserCache parserCache = new ParserCache(GroovyAnalyzer.class.getClassLoader());
  /** Parent of each compile's class loader: the module loader that loaded this class. */
  final ClassLoader parent;
  private final LateTransforms lateTransforms;
  /** The source set's compiler configuration script, or null. */
  private final ConfigurationScript configurationScript;
  /**
   * The compiles' target directory, emptied before each compile. The server writes no class files, but Grails's
   * global transformation writes {@code META-INF/grails-plugin.xml} and {@code grails.factories} there, and without
   * one into {@code build/classes/main} below the working directory, i.e. into the user's project, where the plugin
   * file grew with every artefact compiled and was read and written again by each compile.
   */
  private final Path output;
  /** The taglib index, for GSP tags and tags called as methods. */
  private final Taglibs taglibs;
  /** GSP pages. */
  private final GspAnalyzer pages;

  /**
   * @param reporter            takes {@code true} and a message to show to the user (a feature is off), {@code false}
   *                            and one only to log (a workaround is off); called from the compile thread
   * @param configurationScript the source set's compiler configuration script, or null
   */
  public GroovyAnalyzer(BiConsumer<Boolean, String> reporter, String configurationScript) {
    this(GroovyAnalyzer.class.getClassLoader(), reporter, configurationScript);
  }

  /** Reporting to stderr, for tests. */
  GroovyAnalyzer() {
    this(GroovyAnalyzer.class.getClassLoader());
  }

  /** With another parent loader, reporting to stderr, for tests. */
  GroovyAnalyzer(ClassLoader parent) {
    this(parent, (show, message) -> System.err.println(message));
  }

  /** With another parent loader, for tests. */
  GroovyAnalyzer(ClassLoader parent, BiConsumer<Boolean, String> reporter) {
    this(parent, reporter, null);
  }

  /** With another parent loader and a configuration script, for tests. */
  GroovyAnalyzer(ClassLoader parent, BiConsumer<Boolean, String> reporter, String configurationScript) {
    this.parent = parent;
    this.reports = new Reports(reporter);
    this.configurationScript = configurationScript == null ? null : new ConfigurationScript(configurationScript, parent, reports);
    this.caches = new TransformCaches(GroovyAnalyzer.class.getClassLoader(), reports);
    this.lateTransforms = new LateTransforms(parent);
    FileTexts texts = new FileTexts();
    this.taglibs = new Taglibs((path, text) -> parserCache.parsing(() -> parsedClasses(path, text)), texts, reports, parent);
    this.pages = new GspAnalyzer(this, taglibs, texts);
    try {
      this.output = Files.createTempDirectory("grails-ls-output");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    // @Grab would download and load code at compile time
    System.setProperty("groovy.grape.enable", "false");
    if (DocumentUnit.DECLARATION_CHECK == null) {
      reports.log("declaration check", "Groovy " + GroovySystem.getVersion() + " has no ResolveVisitor.checkingVariableTypeInDeclaration: after a class is "
          + "pulled in from source, a property typed by a superclass's type parameter may be reported as an unresolved class");
    }
  }

  @Override
  public String groovyVersion() {
    return GroovySystem.getVersion();
  }

  @Override
  public String jdkClassFileError() {
    URL object = parent.getResource("java/lang/Object.class");
    if (object == null) {
      return "the JDK's java/lang/Object.class is not readable";
    }
    try {
      AsmDecompiler.parseClass(object);
      return null;
    } catch (IOException | RuntimeException e) {
      // ASM: IllegalArgumentException("Unsupported class file major version 61")
      return e.getMessage() != null ? e.getMessage() : e.toString();
    }
  }

  @Override
  public CompileResult compile(CompileRequest request) {
    if (GspViews.isGsp(request.path)) {
      return pages.compile(request);
    }
    long start = System.nanoTime();
    Kept donor = donor(request);
    units.remove(request.path);
    completions.remove(request.path);
    List<Diagnostic> diagnostics = new ArrayList<>();
    Kept kept = run(request, diagnostics, donor);
    if (kept.cancelled) {
      return new CompileResult(List.of(), true, kept.sources, millisSince(start));
    }
    units.put(request.path, kept);
    if (kept.parsed) {
      lastParsed.put(request.path, kept);
    }
    return new CompileResult(diagnostics, false, kept.sources, millisSince(start));
  }

  /** Compiles a document to its last phase ({@link #lastPhase}), each phase only if the request is not cancelled. */
  Kept run(CompileRequest request, List<Diagnostic> diagnostics) {
    return run(request, diagnostics, null);
  }

  /** Compiles a Groovy document, starting with the sources {@code donor} pulled in ({@link #donor}), or null. */
  private Kept run(CompileRequest request, List<Diagnostic> diagnostics, Kept donor) {
    // sources a class file named before they were converted (PathResolver): compiled again with them there from the
    // start. Each further compile has at least one more of them, so this ends
    Set<String> early = new LinkedHashSet<>();
    Kept from = donor;
    while (true) {
      List<Diagnostic> found = new ArrayList<>();
      int known = early.size();
      Kept reused = from;
      Kept kept;
      try {
        kept = parserCache.parsing(() -> runUnit(request, found, early, reused));
      } finally {
        caches.clear();
        parserCache.trim();
      }
      if (kept == null) {
        from = null; // the donor's sources cannot be reused: compiled again
        continue;
      }
      if (kept.cancelled || early.size() == known) {
        diagnostics.addAll(found);
        return kept;
      }
    }
  }

  /**
   * One compile of a document; null when it started with {@code donor}'s sources and finds after conversion that they
   * cannot be reused ({@link Pulled#fits}).
   */
  private Kept runUnit(CompileRequest request, List<Diagnostic> diagnostics, Set<String> early, Kept donor) {
    empty(output, false);
    CompilerConfiguration config = configuration();
    // the configuration script runs after the server's own settings, as groovyc runs it after its options
    int scriptPhase = configurationScript == null ? -1 : configurationScript.apply(config, request.path);
    if (configurationScript != null && scriptPhase < 0) {
      config = configuration();
    }
    // its imports apply to the pulled-in sources: a donor's compiled with another script are compiled again
    Object script = scriptPhase < 0 ? null : configurationScript.script();
    if (donor != null && donor.pulled.script != script) {
      donor = null;
    }
    GroovyClassLoader loader = new GroovyClassLoader(parent, config, false);
    // source lookup goes through the resolver only, never through the loader (no URL-named sources)
    loader.setResourceLoader(name -> null);
    DocumentUnit unit = new DocumentUnit(config, loader);
    lateTransforms.add(unit);
    SourceUnit document = unit.addSource(source(request.path, request.text, config, loader, unit.getErrorCollector()));
    Inputs inputs = new Inputs(request.lookup);
    PathResolver resolver = new PathResolver(inputs, document);
    unit.setClassNodeResolver(resolver);
    if (donor != null) {
      // past this compile's phases, so Groovy skips them: not parsed, resolved or transformed again
      inputs.add(donor.inputs);
      for (SourceUnit source : donor.pulled.sources) {
        resolver.reuse(unit.addSource(source));
        unit.getAST().addModule(source.getAST());
      }
    }
    early.forEach(path -> resolver.add(path, unit));

    // the last phase depends on the document's own annotations, known once it is converted: decided then and kept,
    // because transformations add statically compiled members to classes that are not (Grails's controller traits)
    int last = Phases.CONVERSION;
    int phase = Phases.INITIALIZATION;
    Injections injections = null;
    boolean failed = false;
    for (; phase <= last; phase++) {
      if (request.cancelled.getAsBoolean()) {
        return new Kept(unit, request.text, document.getSource().getURI(), request.lookup, inputs, false, resolver.sources(), phase - 1, true, null);
      }
      try {
        unit.compile(phase);
      } catch (CompilationFailedException e) {
        // its messages are in the error collector
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        diagnostics.add(new Diagnostic(request.path, 0, 0, 0, 0, Diagnostic.ERROR, "internal compiler error in phase " + phase + ": " + e));
        failed = true;
        break;
      }
      if (phase == Phases.CONVERSION) {
        last = Math.max(lastPhase(document), Math.min(scriptPhase, Phases.INSTRUCTION_SELECTION));
        if (donor != null && !donor.pulled.fits(document, last, donor.phase)) {
          return null;
        }
        injections = Injections.queue(document, inputs, resolver, unit);
      }
    }
    if (early.addAll(resolver.early())) {
      return new Kept(unit, request.text, document.getSource().getURI(), request.lookup, inputs, false, resolver.sources(), Math.min(phase, last), false, null);
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
        // a page's references are its tags' (GspViews), and Conventions reads a document as a Groovy artefact
        diagnostics.addAll(GspViews.isGsp(request.path) ? List.of() : conventions(request.lookup, request.path).problems(document.getAST()));
      } catch (GroovyBugError | RuntimeException | LinkageError e) {
        // no hints rather than a failed compile
      }
    }
    if (donor != null) {
      diagnostics.addAll(donor.pulled.diagnostics); // the reused sources' own, from the compile that converted them
    }
    boolean parsed = document.getAST() != null && !syntaxError(unit.getErrorCollector(), request.path);
    Pulled pulled = failed ? null : Pulled.of(unit, document, request.path, diagnostics, script);
    return new Kept(unit, request.text, document.getSource().getURI(), request.lookup, inputs, parsed, resolver.sources(), Math.min(phase, last), false, pulled);
  }

  /**
   * The compile of the document whose pulled-in sources a new compile may reuse ({@link Pulled#reusable}): the kept
   * one, else the last that parsed (a text that does not parse may pull in nothing); or null.
   */
  private Kept donor(CompileRequest request) {
    Kept kept = units.get(request.path);
    if (Pulled.reusable(kept, request.lookup)) {
      return kept;
    }
    Kept parsed = lastParsed.get(request.path);
    return parsed != kept && Pulled.reusable(parsed, request.lookup) ? parsed : null;
  }

  /** The configuration of a compile, before the configuration script. */
  private CompilerConfiguration configuration() {
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    config.setTargetDirectory(output.toFile());
    config.addCompilationCustomizers(new AstTestRemover(), new ParseTreeRemover());
    // attaches Groovydoc comments to nodes for hover (CompilerConfiguration.GROOVYDOC, Groovy 3+; 2.5 has none)
    config.getOptimizationOptions().put("groovydoc", true);
    // a name without a class file is not loaded as a class next: of the thousands of names the resolver tries per
    // compile, almost all exist nowhere, and each such load searched every jar again and threw through four loaders
    config.getOptimizationOptions().put("classLoaderResolving", false);
    lateTransforms.disable(config);
    return config;
  }

  /** Whether the document has a syntax error. */
  @SuppressWarnings("unchecked") // Groovy 2.5 returns raw lists
  private static boolean syntaxError(ErrorCollector collector, String documentPath) {
    for (Object error : collector.getErrors() == null ? List.of() : (List<Object>) collector.getErrors()) {
      if (error instanceof SyntaxErrorMessage) {
        String path = ((SyntaxErrorMessage) error).getCause().getSourceLocator();
        if (path == null || path.equals(documentPath)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Canonicalization, where Grails's transformations have run; instruction selection when the document has statically
   * compiled or type-checked code (C2): the static type checker runs there and records the types it infers on the nodes.
   */
  private static int lastPhase(SourceUnit document) {
    ModuleNode module = document.getAST();
    if (module != null) {
      for (ClassNode type : module.getClasses()) {
        if (isChecked(module, type)
            || type.getMethods().stream().anyMatch(method -> isChecked(module, method))
            || type.getDeclaredConstructors().stream().anyMatch(constructor -> isChecked(module, constructor))) {
          return Phases.INSTRUCTION_SELECTION;
        }
      }
    }
    return Phases.CANONICALIZATION;
  }

  /** The annotations that run the static type checker, by simple name. */
  private static final Set<String> CHECKED =
      Set.of("CompileStatic", "GrailsCompileStatic", "TypeChecked", "GrailsTypeChecked");

  /**
   * Marked with one of {@link #CHECKED}, by simple name (annotations are not resolved yet), after following an aliased
   * import ({@code import groovy.transform.CompileStatic as Checked}).
   */
  private static boolean isChecked(ModuleNode module, AnnotatedNode node) {
    for (AnnotationNode annotation : node.getAnnotations()) {
      String name = annotation.getClassNode().getNameWithoutPackage();
      ImportNode imported = module.getImport(name);
      if (CHECKED.contains(imported != null ? imported.getType().getNameWithoutPackage() : name)) {
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

  /** Whether the GSP pages keep a file's text, parse or model types ({@link GspAnalyzer#keeps}). For tests. */
  boolean pagesKeep(String path) {
    return pages.keeps(path);
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
    if (GspViews.isGsp(documentPath)) {
      return pages.definition(documentPath, line, column);
    }
    Kept kept = units.get(documentPath);
    if (kept == null) {
      return null;
    }
    Taglibs.Tag called = tagAt(kept, false, line, column);
    return called != null ? called.docs().location : Navigation.definition(kept.ast(), kept.uri, kept.lookup, conventions(kept.lookup, documentPath), kept.text, line, column);
  }

  /** The tag a call at a position of a kept compile names ({@link TagCalls}), or null. */
  Taglibs.Tag tagAt(Kept kept, boolean page, int line, int column) {
    ASTNode node = kept.ast().getNodeAtLineAndColumn(kept.uri, line, column);
    return node == null ? null : TagCalls.at(node, kept.ast(), namespaces(kept.lookup), page);
  }

  /**
   * The tags of a namespace by name, null when no taglib declares it: for {@link TagCalls}. For one request: the tags
   * are read on the first call ({@link Taglibs#all} reads every taglib) and kept for the next.
   */
  Function<String, Map<String, Taglibs.Tag>> namespaces(SourceLookup lookup) {
    List<Map<String, Map<String, Taglibs.Tag>>> read = new ArrayList<>(1);
    return namespace -> {
      if (read.isEmpty()) {
        read.add(taglibs.all(lookup));
      }
      return read.get(0).get(namespace);
    };
  }

  @Override
  public String hover(String documentPath, int line, int column) {
    if (GspViews.isGsp(documentPath)) {
      return pages.hover(documentPath, line, column);
    }
    Kept kept = units.get(documentPath);
    if (kept == null) {
      return null;
    }
    Taglibs.Tag called = tagAt(kept, false, line, column);
    return called != null ? GspViews.markdown(called) : Navigation.hover(kept.ast(), kept.uri, kept.lookup, kept.text, line, column);
  }

  @Override
  public List<Symbol> symbols(String documentPath) {
    if (GspViews.isGsp(documentPath)) {
      return List.of();
    }
    Kept kept = units.get(documentPath);
    return kept == null ? List.of() : Navigation.symbols(kept.ast(), kept.uri);
  }

  @Override
  public CompletionList completion(CompileRequest request, int line, int column) {
    if (GspViews.isGsp(request.path)) {
      return pages.completion(request, line, column);
    }
    String typed = lineBefore(request.text, line, column);
    Imports imported = Imports.of(typed);
    if (imported != null) {
      return importCompletion(request, imported, line, column);
    }
    Kept kept = current(request);
    Conventions conventions = conventions(request.lookup, request.path);
    Function<String, Map<String, Taglibs.Tag>> tags = namespaces(request.lookup);
    Completions found = kept == null ? null : Completions.at(kept.ast(), kept.uri, request.lookup, conventions, tags, false, line, column, request.text);
    // the text does not parse at the position: a copy with a name typed there, a string closed that the line leaves
    // open (render(view: 'sh), and blocks closed that the text leaves open (a method before its closing brace)
    String repaired = Completions.repaired(request.text, new Lines(request.text).offset(line, column), Completions.repair(typed, lineAfter(request.text, line, column)));
    if (found == null) {
      // after a dot just typed: the receiver's members, from the last compile that parsed, when it has the receiver
      Kept earlier = lastParsed.get(request.path);
      Expression receiver = earlier == null ? null : TypedDot.receiver(earlier, request, line, column, repaired, copy -> parserCache.parsing(() -> parsed(request.path, copy)));
      if (receiver != null) {
        kept = earlier;
        found = Completions.afterDot(earlier.ast(), tags, receiver);
      }
    }
    if (found == null) {
      kept = compileCopy(request, repaired);
      found = kept == null ? null : Completions.at(kept.ast(), kept.uri, request.lookup, conventions, tags, false, line, column, request.text);
    }
    if (found == null) {
      completions.remove(request.path);
      // a copy that does not parse either: what is typed next may parse, so the client asks again
      return kept != null && !kept.parsed ? new CompletionList(List.of(), true) : CompletionList.EMPTY;
    }
    completions.put(request.path, new LastCompletion(found, kept));
    return found.list();
  }

  /**
   * On an import line: a static import's members once its class resolves in a compile of the text (with a name typed
   * after a trailing dot), else, as when it has none, packages and classes ({@link Imports}).
   */
  private CompletionList importCompletion(CompileRequest request, Imports imported, int line, int column) {
    completions.remove(request.path);
    if (imported.isStatic && !imported.qualifier().isEmpty()) {
      // after the dot the text does not parse: a copy with a name typed
      Kept kept = imported.last().isEmpty() ? null : current(request);
      if (kept == null) {
        kept = compileCopy(request, line, column, imported.last().isEmpty() ? Completions.placeholder("") : "");
      }
      ClassNode type = kept == null ? null : Imports.staticallyImported(kept.unit, imported.qualifier());
      Completions found = type == null ? null : Completions.staticMembers(kept.ast(), type, imported.last());
      // none when the qualifier is no class: import static java.util.Coll names the class java.util
      if (found != null && !found.list().items.isEmpty()) {
        completions.put(request.path, new LastCompletion(found, kept));
        return found.list();
      }
    }
    return imported.names(request.lookup, line, column);
  }

  @Override
  public CompletionDetail resolveCompletion(String documentPath, int id) {
    LastCompletion last = completions.get(documentPath);
    if (last != null && last.details != null) {
      return id < 0 || id >= last.details.size() ? null : last.details.get(id);
    }
    Taglibs.Tag tag = last == null ? null : last.completions.tag(id);
    if (tag != null) {
      return new CompletionDetail(tag.owner, GspViews.markdown(tag));
    }
    ASTNode declaration = last == null ? null : last.completions.declaration(id);
    return declaration == null ? null : Completions.detail(declaration, last.compile.ast(), last.compile.lookup);
  }

  @Override
  public SignatureHelp signatureHelp(CompileRequest request, int line, int column) {
    if (GspViews.isGsp(request.path)) {
      return pages.signatureHelp(request, line, column);
    }
    Kept kept = current(request);
    if (kept != null && kept.parsed && kept.ast().getNodeAtLineAndColumn(kept.uri, line, column) != null) {
      return Signatures.at(kept.ast(), kept.uri, kept.lookup, line, column);
    }
    // an unfinished call, "render(", does not parse (Groovy 2.5 keeps a partial AST of it, without the call)
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
    int offset = new Lines(request.text).offset(line, column);
    return compileCopy(request, request.text.substring(0, offset) + insert + request.text.substring(offset));
  }

  /** Compiles another text of the document, for one query; null when cancelled. Not kept. */
  private Kept compileCopy(CompileRequest request, String text) {
    Kept copy = run(new CompileRequest(request.path, text, request.lookup, request.cancelled), new ArrayList<>(), donor(request));
    return copy.cancelled ? null : copy;
  }

  static String lineBefore(String text, int line, int column) {
    return new Lines(text).before(line, column);
  }

  /** The text of a line after a position. */
  private static String lineAfter(String text, int line, int column) {
    int offset = new Lines(text).offset(line, column);
    int end = text.indexOf('\n', offset);
    return text.substring(offset, end < 0 ? text.length() : end);
  }

  @Override
  public Map<String, List<Symbol>> declarations(List<String> paths) {
    try {
      return parserCache.parsing(() -> parse(paths));
    } finally {
      parserCache.trim(); // the index may parse many files before any compile trims the cache
    }
  }

  /** Conventions for a document: other files are read from their open text, else from disk, and parsed only. */
  Conventions conventions(SourceLookup lookup, String documentPath) {
    return new Conventions(lookup, documentPath, file -> parserCache.parsing(() -> parsedClasses(file, lookup == null ? null : lookup.openText(file))));
  }

  /** The classes a file declares, from a parse only (as {@link #declarations}); empty when it does not parse. */
  static List<ClassNode> parsedClasses(String path, String text) {
    SourceUnit source = parsed(path, text);
    return source == null ? List.of() : source.getAST().getClasses();
  }

  private static Map<String, List<Symbol>> parse(List<String> paths) {
    ASTNodeVisitor ast = new ASTNodeVisitor();
    Map<String, List<Symbol>> result = new LinkedHashMap<>();
    for (String path : paths) {
      SourceUnit source = parsed(path, null);
      List<Symbol> symbols = List.of(); // does not parse, or no file
      if (source != null) {
        try {
          ast.visitSourceUnit(source);
          symbols = Navigation.symbols(ast, source.getSource().getURI());
        } catch (GroovyBugError | RuntimeException | LinkageError e) {
          // as if it did not parse
        }
      }
      result.put(path, symbols);
    }
    return result;
  }

  /**
   * A file parsed only, outside any compilation unit (no class is resolved, no transformation runs), from {@code text}
   * or, when that is null, from disk; null when it does not parse.
   */
  static SourceUnit parsed(String path, String text) {
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    GroovyClassLoader loader = new GroovyClassLoader(GroovyAnalyzer.class.getClassLoader(), config, false);
    SourceUnit source = source(path, text, config, loader, new ErrorCollector(config));
    try {
      source.parse();
      source.completePhase();
      source.convert();
      return source;
    } catch (GroovyBugError | RuntimeException | LinkageError e) {
      return null;
    }
  }

  @Override
  public void forget(String documentPath) {
    pages.forget(documentPath);
    units.remove(documentPath);
    lastParsed.remove(documentPath);
    completions.remove(documentPath);
  }

  @Override
  public void close() {
    units.clear();
    lastParsed.clear();
    completions.clear();
    pages.clear();
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
   * A source of {@code text}, or of the file's text on disk when that is null, named by its file path (Grails decides
   * artefacts by it) and identified by the file's URI. A file that cannot be read as UTF-8 is left to Groovy to read.
   */
  static SourceUnit source(String path, String text, CompilerConfiguration config, GroovyClassLoader loader, ErrorCollector errors) {
    if (text == null) {
      try {
        text = Files.readString(Path.of(path));
      } catch (IOException | RuntimeException e) {
        return new SourceUnit(new File(path), config, loader, errors);
      }
    }
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

  static long millisSince(long nanos) {
    return (System.nanoTime() - nanos) / 1_000_000;
  }

  /** A compile of a document, and its node lookup (of the document only), built on the first query. */
  static final class Kept {
    final CompilationUnit unit;
    final String text;
    final URI uri;
    /** Finds the source of classes the compile read from class files, for definition and hover. */
    final SourceLookup lookup;
    /** What the compile read through the lookup. */
    final Inputs inputs;
    /** Whether the document parsed. */
    final boolean parsed;
    /** Sources the compile read: the document and those pulled in. */
    final int sources;
    /** The last phase compiled. */
    final int phase;
    final boolean cancelled;
    /** The sources pulled in, for a later compile of the document to reuse; null when they may not be. */
    final Pulled pulled;
    private ASTNodeVisitor ast;

    Kept(CompilationUnit unit, String text, URI uri, SourceLookup lookup, Inputs inputs, boolean parsed, int sources, int phase, boolean cancelled, Pulled pulled) {
      this.unit = unit;
      this.text = text;
      this.uri = uri;
      this.lookup = lookup;
      this.inputs = inputs;
      this.parsed = parsed;
      this.sources = sources;
      this.phase = phase;
      this.cancelled = cancelled;
      this.pulled = pulled;
    }

    synchronized ASTNodeVisitor ast() {
      if (ast == null) {
        ast = new ASTNodeVisitor();
        ast.visitDocument(unit, uri);
      }
      return ast;
    }
  }

  /** A completion's items' declarations, with the compile they are of; or, for a page's tags, their documentation by id. */
  static final class LastCompletion {
    final Completions completions;
    final Kept compile;
    final List<CompletionDetail> details;

    LastCompletion(Completions completions, Kept compile) {
      this.completions = completions;
      this.compile = compile;
      this.details = null;
    }

    LastCompletion(List<CompletionDetail> details) {
      this.completions = null;
      this.compile = null;
      this.details = details;
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
