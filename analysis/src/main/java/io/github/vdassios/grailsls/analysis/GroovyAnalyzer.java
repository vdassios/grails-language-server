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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

import org.codehaus.groovy.GroovyBugError;
import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.AnnotationNode;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.decompiled.AsmDecompiler;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
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
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SignatureHelp;
import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;

/**
 * Compiles one document per {@link CompilationUnit}, in the module loader that loaded this class: the project's own
 * Groovy, its AST transformations included. Classes the document uses come from source through {@link PathResolver},
 * or from the loader (jars, class directories, compiled Java).
 */
public final class GroovyAnalyzer implements Analyzer {
  private final Map<String, Kept> units = new ConcurrentHashMap<>();
  /** The last completion per document, for resolve; dropped by the next compile of the document. */
  private final Map<String, LastCompletion> completions = new ConcurrentHashMap<>();
  /** Failures, to the server: shown to the user or logged, each once. */
  private final Reports reports;
  private final TransformCaches caches;
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
  /** The taglib index, for GSP tags and tags called as methods. */
  private final Taglibs taglibs;
  /** GSP pages: tags and taglibs. */
  private final GspViews gsp;
  /** {@link GspTranslation#implicitObjects} of this loader, once read. */
  private volatile String implicitObjects;
  /** {@link GspTranslation#escapes} of this loader, once read. */
  private volatile Boolean escapes;
  /** The translation of each GSP page whose generated Groovy is kept in {@link #units}. */
  private final Map<String, GspTranslation> translations = new ConcurrentHashMap<>();

  /**
   * @param reporter takes {@code true} and a message to show to the user (a feature is off), {@code false} and one only
   *                 to log (a workaround is off); called from the compile thread
   */
  public GroovyAnalyzer(BiConsumer<Boolean, String> reporter) {
    this(GroovyAnalyzer.class.getClassLoader(), reporter);
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
    this.parent = parent;
    this.reports = new Reports(reporter);
    this.caches = new TransformCaches(GroovyAnalyzer.class.getClassLoader(), reports);
    this.lateTransforms = new LateTransforms(parent);
    this.taglibs = new Taglibs((path, text) -> parserCache.parsing(() -> parsedClasses(path, text)), reports, parent);
    this.gsp = new GspViews(taglibs, this::conventions);
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
      return compilePage(request);
    }
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

  /**
   * A GSP page: its tag hints ({@link GspViews}), then its translation to Groovy ({@link GspTranslation}) compiled like
   * a document of the page's module, diagnostics mapped back onto the page. A class path without Grails's page parser
   * gives the tag hints only.
   */
  private CompileResult compilePage(CompileRequest request) {
    long start = System.nanoTime();
    units.remove(request.path);
    translations.remove(request.path);
    completions.remove(request.path);
    GspScanner.Scan scan = GspScanner.scan(request.text, escapes());
    CompileResult tags = gsp.compile(request, scan);
    GspTranslation translation = translate(request.path, scan, request.lookup, request.cancelled);
    if (translation == null) {
      return tags;
    }
    List<Diagnostic> diagnostics = new ArrayList<>(tags.diagnostics);
    if (translation.groovy == null) {
      // the parser rejects a page, e.g. a g:each without "in"; a tag hint on that line already says why
      Diagnostic error = translation.errorDiagnostic(request.path);
      if (tags.diagnostics.stream().noneMatch(hint -> hint.startLine == error.startLine)) {
        diagnostics.add(error);
      }
      return new CompileResult(diagnostics, false, 0, millisSince(start));
    }
    List<Diagnostic> found = new ArrayList<>();
    Kept kept = run(new CompileRequest(request.path, translation.groovy, request.lookup, request.cancelled), found);
    if (kept.cancelled) {
      return new CompileResult(List.of(), true, kept.sources, millisSince(start));
    }
    linkPage(kept, request.path);
    compiledModels.put(request.path, new CompiledModels(request.text, renderModelTypes(kept, request.path, request.lookup)));
    units.put(request.path, kept);
    translations.put(request.path, translation);
    for (Diagnostic diagnostic : found) {
      diagnostics.add(diagnostic.path.equals(request.path) ? translation.toPage(diagnostic) : diagnostic);
    }
    return new CompileResult(diagnostics, false, kept.sources, millisSince(start));
  }

  /** Whether this loader's Grails takes {@code \${} for text ({@link GspTranslation#escapes}), as pages are scanned. */
  private boolean escapes() {
    Boolean read = escapes;
    if (read == null) {
      read = GspTranslation.escapes(parent);
      escapes = read;
    }
    return read;
  }

  /** A page's translation, with the implicit objects typed and its variables declared; null without Grails's page parser. */
  private GspTranslation translate(String path, GspScanner.Scan page, SourceLookup lookup, java.util.function.BooleanSupplier cancelled) {
    String declarations = implicitObjects;
    if (declarations == null) {
      declarations = GspTranslation.implicitObjects(parent);
      implicitObjects = declarations;
    }
    Set<String> taken = new HashSet<>(Set.of("out"));
    for (String declaration : declarations.split(";")) {
      taken.add(declaration.trim().substring(declaration.trim().lastIndexOf(' ') + 1));
    }
    PageVariables variables = PageVariables.of(path, page, modelConventions(lookup, path), taken, name -> resolves(name, lookup), model -> compiledType(model, lookup, cancelled));
    return GspTranslation.of(parent, path, page, declarations + variables.declarations, variables.definitions, reports);
  }

  /** Whether a qualified class name resolves in this module: a project source, or a class on its class path. */
  private boolean resolves(String name, SourceLookup lookup) {
    if (lookup != null && lookup.fileOf(name) != null) {
      return true;
    }
    try {
      Class.forName(name, false, parent);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /** Controller parses by path, with the text parsed: model variables read every controller for each page compile. */
  private final Map<String, Parsed> controllerParses = new ConcurrentHashMap<>();

  private static final class Parsed {
    final String text;
    final List<ClassNode> classes;

    Parsed(String text, List<ClassNode> classes) {
      this.text = text;
      this.classes = classes;
    }
  }

  /** {@link Conventions} whose parses are kept while a file's text stays the same (open text, else the file). */
  private Conventions modelConventions(SourceLookup lookup, String documentPath) {
    return new Conventions(lookup, documentPath, file -> {
      String text = textOf(file, lookup);
      if (text == null) {
        return List.of();
      }
      Parsed parsed = controllerParses.get(file);
      if (parsed == null || !parsed.text.equals(text)) {
        parsed = new Parsed(text, parserCache.parsing(() -> parsedClasses(file, text)));
        controllerParses.put(file, parsed);
      }
      return parsed.classes;
    }, file -> textOf(file, lookup), snippet -> parserCache.parsing(() -> parsedClasses("model.groovy", snippet)), escapes());
  }

  /** Texts of files read from disk, with the modification time and size they were read at: each page compile reads every controller. */
  private final Map<String, FileText> fileTexts = new ConcurrentHashMap<>();

  private static final class FileText {
    final long modified;
    final long size;
    final String text;

    FileText(long modified, long size, String text) {
      this.modified = modified;
      this.size = size;
      this.text = text;
    }
  }

  /** A file's open text, else its text on disk (read again only when its modification time or size changed); null when unreadable. */
  private String textOf(String file, SourceLookup lookup) {
    String open = lookup == null ? null : lookup.openText(file);
    if (open != null) {
      return open;
    }
    try {
      java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(Path.of(file), java.nio.file.attribute.BasicFileAttributes.class);
      long modified = attributes.lastModifiedTime().toMillis();
      FileText known = fileTexts.get(file);
      if (known != null && known.modified == modified && known.size == attributes.size()) {
        return known.text;
      }
      String text = Files.readString(Path.of(file));
      fileTexts.put(file, new FileText(modified, attributes.size(), text));
      return text;
    } catch (IOException | RuntimeException e) {
      fileTexts.remove(file);
      return null;
    }
  }

  /**
   * Types of the model values in a file, from a compile of it, with the text compiled; by path. A controller's are
   * computed when a page needs them; a page's (the models it gives templates) are recorded by each compile of the page.
   */
  private final Map<String, CompiledModels> compiledModels = new ConcurrentHashMap<>();

  private static final class CompiledModels {
    final String text;
    /** Type by {@link #modelKey}. */
    final Map<String, String> types;

    CompiledModels(String text, Map<String, String> types) {
      this.text = text;
      this.types = types;
    }
  }

  /** A model value's key in {@link CompiledModels#types}: its position in a controller; template, tag and key in a page. */
  private static String modelKey(Conventions.ModelEntry model) {
    Expression value = model.entry.getValueExpression();
    return model.render == null ? value.getLineNumber() + ":" + value.getColumnNumber()
        : model.render.template + "#" + model.render.index + ":" + model.entry.getKeyExpression().getText();
  }

  /**
   * A model value's type from a compile, in this module, of the file holding the map: the controller (compiled now
   * unless its types are known for its text), or the page rendering the template (only when that page was compiled
   * with its current text: compiling the pages that render a template took seconds per page on the corpus). Null when
   * none is known or the compile is cancelled.
   */
  private String compiledType(Conventions.ModelEntry model, SourceLookup lookup, java.util.function.BooleanSupplier cancelled) {
    String text = model.render != null ? model.render.pageText : textOf(model.path, lookup);
    CompiledModels known = text == null ? null : compiledModels.get(model.path);
    if (text != null && model.render == null && (known == null || !known.text.equals(text))) {
      Map<String, String> types = controllerModelTypes(model, text, lookup, cancelled);
      if (types == null) {
        return null;
      }
      known = new CompiledModels(text, types);
      compiledModels.put(model.path, known);
    }
    return known == null || !known.text.equals(text) ? null : known.types.get(modelKey(model));
  }

  /** The types of a controller's map values, from a compile of it (the kept one if it is of this text); null when cancelled. */
  private Map<String, String> controllerModelTypes(Conventions.ModelEntry model, String text, SourceLookup lookup, java.util.function.BooleanSupplier cancelled) {
    if (!resolves(model.controller.getName(), lookup)) {
      // another module's controller, whose classes this module may not see
      return Map.of();
    }
    Kept kept = units.get(model.path);
    if (kept == null || !text.equals(kept.text)) {
      kept = run(new CompileRequest(model.path, text, lookup, cancelled), new ArrayList<>());
      if (kept.cancelled) {
        return null;
      }
    }
    Map<String, String> types = new LinkedHashMap<>();
    ASTNodeVisitor ast = kept.ast();
    for (ClassNode type : classesOf(kept, model.path)) {
      new org.codehaus.groovy.ast.ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitMapEntryExpression(MapEntryExpression entry) {
          Expression value = entry.getValueExpression();
          if (value.getLineNumber() > 0) {
            String name = PageVariables.declared(GroovyASTUtils.getTypeOfNode(value, ast), qualified -> resolves(qualified, lookup));
            if (name != null) {
              types.putIfAbsent(value.getLineNumber() + ":" + value.getColumnNumber(), name);
            }
          }
          super.visitMapEntryExpression(entry);
        }
      }.visitClass(type);
    }
    return types;
  }

  /** The types of the model values a page's compile gives templates ({@code <g:render template model="[...]"/>}). */
  private Map<String, String> renderModelTypes(Kept kept, String path, SourceLookup lookup) {
    Map<String, String> types = new LinkedHashMap<>();
    Map<String, Integer> seen = new HashMap<>();
    ASTNodeVisitor ast = kept.ast();
    for (ClassNode type : classesOf(kept, path)) {
      new org.codehaus.groovy.ast.ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
          // invokeTag('render', 'g', line, ['template': 'row', 'model': [book: book]], -1)
          List<Expression> arguments = call.getArguments() instanceof TupleExpression ? ((TupleExpression) call.getArguments()).getExpressions() : List.of();
          if (call.isImplicitThis() && "invokeTag".equals(call.getMethodAsString()) && arguments.size() >= 4 && "render".equals(arguments.get(0).getText())
              && "g".equals(arguments.get(1).getText()) && arguments.get(3) instanceof MapExpression) {
            String template = null;
            MapExpression map = null;
            for (MapEntryExpression entry : ((MapExpression) arguments.get(3)).getMapEntryExpressions()) {
              Expression value = entry.getValueExpression();
              if ("template".equals(entry.getKeyExpression().getText()) && value instanceof ConstantExpression && ((ConstantExpression) value).getValue() instanceof String) {
                template = (String) ((ConstantExpression) value).getValue();
              } else if ("model".equals(entry.getKeyExpression().getText()) && value instanceof MapExpression) {
                map = (MapExpression) value;
              }
            }
            if (template != null) {
              int index = seen.merge(template, 1, Integer::sum) - 1;
              for (MapEntryExpression entry : map == null ? List.<MapEntryExpression>of() : map.getMapEntryExpressions()) {
                String name = PageVariables.declared(GroovyASTUtils.getTypeOfNode(entry.getValueExpression(), ast), qualified -> resolves(qualified, lookup));
                if (name != null) {
                  types.putIfAbsent(template + "#" + index + ":" + entry.getKeyExpression().getText(), name);
                }
              }
            }
          }
          super.visitMethodCallExpression(call);
        }
      }.visitClass(type);
    }
    return types;
  }

  /** The classes a compile made of one of its sources. */
  private static List<ClassNode> classesOf(Kept kept, String path) {
    List<ClassNode> classes = new ArrayList<>();
    for (ModuleNode module : kept.unit.getAST().getModules()) {
      if (module.getContext() != null && path.equals(module.getContext().getName())) {
        classes.addAll(module.getClasses());
      }
    }
    return classes;
  }

  /** Links each page variable of a page's compile to what types it ({@link PageVariables#link}). */
  private static void linkPage(Kept kept, String path) {
    for (ModuleNode module : kept.unit.getAST().getModules()) {
      if (module.getContext() != null && path.equals(module.getContext().getName())) {
        module.getClasses().forEach(PageVariables::link);
      }
    }
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
        // a page's references are its tags' (GspViews), and Conventions reads a document as a Groovy artefact
        diagnostics.addAll(GspViews.isGsp(request.path) ? List.of() : conventions(request.lookup, request.path).problems(document.getAST()));
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
    if (GspViews.isGsp(documentPath)) {
      Location tag = gsp.definition(documentPath, line, column);
      return tag != null ? tag : pageQuery(documentPath, line, column, (kept, at) -> {
        Taglibs.Tag called = tagAt(kept, true, at[0], at[1]);
        if (called != null) {
          return called.docs().location;
        }
        Location found = Navigation.definition(kept.ast(), kept.uri, kept.lookup, conventions(kept.lookup, documentPath), kept.text, at[0], at[1]);
        if (found == null || !found.path.equals(documentPath)) {
          return found;
        }
        GspTranslation translation = translations.get(documentPath);
        if (found.range.startLine == translation.declarationLine) {
          // a page variable: its model entry or g:set; an implicit object has none
          ASTNode node = kept.ast().getNodeAtLineAndColumn(kept.uri, at[0], at[1]);
          return node instanceof VariableExpression ? translation.definitions.get(((VariableExpression) node).getName()) : null;
        }
        Range range = translation.toPage(found.range);
        return range == null ? null : new Location(documentPath, range);
      });
    }
    Kept kept = units.get(documentPath);
    if (kept == null) {
      return null;
    }
    Taglibs.Tag called = tagAt(kept, false, line, column);
    return called != null ? called.docs().location : Navigation.definition(kept.ast(), kept.uri, kept.lookup, conventions(kept.lookup, documentPath), kept.text, line, column);
  }

  /** The tag a call at a position of a kept compile names ({@link TagCalls}), or null. */
  private Taglibs.Tag tagAt(Kept kept, boolean page, int line, int column) {
    ASTNode node = kept.ast().getNodeAtLineAndColumn(kept.uri, line, column);
    return node == null ? null : tagCalls(kept.lookup, page).at(node, kept.ast());
  }

  private TagCalls tagCalls(SourceLookup lookup, boolean page) {
    return new TagCalls(() -> taglibs.all(lookup), page);
  }

  @Override
  public String hover(String documentPath, int line, int column) {
    if (GspViews.isGsp(documentPath)) {
      String tag = gsp.hover(documentPath, line, column);
      return tag != null ? tag : pageQuery(documentPath, line, column, (kept, at) -> {
        Taglibs.Tag called = tagAt(kept, true, at[0], at[1]);
        return called != null ? GspViews.markdown(called) : Navigation.hover(kept.ast(), kept.uri, kept.lookup, kept.text, at[0], at[1]);
      });
    }
    Kept kept = units.get(documentPath);
    if (kept == null) {
      return null;
    }
    Taglibs.Tag called = tagAt(kept, false, line, column);
    return called != null ? GspViews.markdown(called) : Navigation.hover(kept.ast(), kept.uri, kept.lookup, kept.text, line, column);
  }

  /** A query at a page position inside page code, on the page's kept translation; null elsewhere. */
  private <T> T pageQuery(String documentPath, int line, int column, BiFunction<Kept, int[], T> query) {
    Kept kept = units.get(documentPath);
    GspTranslation translation = translations.get(documentPath);
    int[] at = kept == null || translation == null ? null : translation.toGroovy(line, column);
    return at == null ? null : query.apply(kept, at);
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
      return pageCompletion(request, line, column);
    }
    String typed = lineBefore(request.text, line, column);
    Imports imported = Imports.of(typed);
    if (imported != null) {
      return importCompletion(request, imported, line, column);
    }
    ClasspathTypes types = sourceTypes(request.lookup);
    Kept kept = current(request);
    Conventions conventions = conventions(request.lookup, request.path);
    TagCalls tags = tagCalls(request.lookup, false);
    Completions found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, conventions, tags, line, column, typed);
    if (found == null) {
      kept = compileCopy(request, line, column, Completions.placeholder(typed));
      found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, conventions, tags, line, column, typed);
    }
    String close = Completions.closeString(typed);
    if (found == null && close != null) {
      // inside a string not closed yet: render(view: 'sh
      kept = compileCopy(request, line, column, Completions.placeholder(typed) + close);
      found = kept == null ? null : Completions.at(kept.ast(), kept.uri, types, conventions, tags, line, column, typed);
    }
    if (found == null) {
      completions.remove(request.path);
      return CompletionList.EMPTY;
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

  /**
   * In a page: tag, attribute and attribute value completion ({@link GspViews}); else, inside page code, completion on
   * the page's translation, at the generated position. The page is compiled again unless the kept compile is of its
   * text; when that finds nothing, with a name typed at the position ({@code ${book.}}), then with the expression closed
   * too ({@code ${book.} without its brace}).
   */
  private CompletionList pageCompletion(CompileRequest request, int line, int column) {
    completions.remove(request.path);
    GspScanner.Scan scan = GspScanner.scan(request.text, escapes());
    CompletionList tags = gsp.completion(request, scan, line, column);
    if (!tags.items.isEmpty()) {
      return tags;
    }
    ClasspathTypes types = sourceTypes(request.lookup);
    TagCalls tagCalls = tagCalls(request.lookup, true);
    String placeholder = Completions.placeholder(lineBefore(request.text, line, column));
    for (String insert : new String[] { "", placeholder, placeholder + "}" }) {
      PageCompile compiled = pageCompile(request, scan, line, column, insert);
      if (compiled == null) {
        continue;
      }
      int[] at = compiled.at;
      Completions found = Completions.at(compiled.kept.ast(), compiled.kept.uri, types, null, tagCalls, at[0], at[1], lineBefore(compiled.translation.groovy, at[0], at[1]));
      if (found != null) {
        completions.put(request.path, new LastCompletion(found, compiled.kept));
        return onPage(found.list(), compiled.translation);
      }
    }
    return CompletionList.EMPTY;
  }

  /** Completion items of a translation on its page: replaced ranges mapped back, import edits left out. */
  private static CompletionList onPage(CompletionList list, GspTranslation translation) {
    List<Completion> items = new ArrayList<>();
    for (Completion item : list.items) {
      Range replace = null;
      if (item.replace != null) {
        int[] start = translation.toPage(item.replace.startLine, item.replace.startColumn);
        int[] end = translation.toPage(item.replace.endLine, item.replace.endColumn);
        replace = start == null || end == null ? null : new Range(start[0], start[1], end[0], end[1]);
      }
      items.add(new Completion(item.label, item.kind, item.detail, item.sortText, replace, List.of(), item.id));
    }
    return new CompletionList(items, list.incomplete);
  }

  /**
   * The page with text inserted at a position, translated and compiled, and the position in the generated text; the
   * kept compile when nothing is inserted and it is of the page's text. Null when the position is outside page code,
   * the page does not translate, or the compile is cancelled. {@code scan} is of the request's text, or null when it
   * has not been scanned.
   */
  private PageCompile pageCompile(CompileRequest request, GspScanner.Scan scan, int line, int column, String insert) {
    Kept kept = units.get(request.path);
    GspTranslation translation = translations.get(request.path);
    if (!insert.isEmpty() || kept == null || translation == null || !translation.text.equals(request.text)) {
      GspScanner.Scan page = scan;
      if (!insert.isEmpty() || page == null) {
        int offset = new Lines(request.text).offset(line, column);
        page = GspScanner.scan(request.text.substring(0, offset) + insert + request.text.substring(offset), escapes());
      }
      translation = translate(request.path, page, request.lookup, request.cancelled);
      if (translation == null || translation.groovy == null || translation.toGroovy(line, column) == null) {
        return null;
      }
      kept = run(new CompileRequest(request.path, translation.groovy, request.lookup, request.cancelled), new ArrayList<>());
      if (kept.cancelled) {
        return null;
      }
      linkPage(kept, request.path);
    }
    int[] at = translation.toGroovy(line, column);
    return at == null ? null : new PageCompile(kept, translation, at);
  }

  private static final class PageCompile {
    final Kept kept;
    final GspTranslation translation;
    final int[] at;

    PageCompile(Kept kept, GspTranslation translation, int[] at) {
      this.kept = kept;
      this.translation = translation;
      this.at = at;
    }
  }

  @Override
  public CompletionDetail resolveCompletion(String documentPath, int id) {
    LastCompletion last = completions.get(documentPath);
    if (GspViews.isGsp(documentPath) && last == null) {
      return gsp.resolveCompletion(documentPath, id);
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
      // an unfinished call, "${link(", does not translate to code that parses
      for (String insert : new String[] { "", ")", ")}" }) {
        PageCompile compiled = pageCompile(request, null, line, column, insert);
        SignatureHelp help = compiled == null ? null : Signatures.at(compiled.kept.ast(), compiled.kept.uri, compiled.kept.lookup, compiled.at[0], compiled.at[1]);
        if (help != null) {
          return help;
        }
      }
      return null;
    }
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
    int offset = new Lines(request.text).offset(line, column);
    String text = request.text.substring(0, offset) + insert + request.text.substring(offset);
    Kept copy = run(new CompileRequest(request.path, text, request.lookup, request.cancelled), new ArrayList<>());
    return copy.cancelled ? null : copy;
  }

  private static String lineBefore(String text, int line, int column) {
    return new Lines(text).before(line, column);
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
  private static SourceUnit parsed(String path, String text) {
    CompilerConfiguration config = new CompilerConfiguration();
    config.setSourceEncoding("UTF-8");
    GroovyClassLoader loader = new GroovyClassLoader(GroovyAnalyzer.class.getClassLoader(), config, false);
    SourceUnit source = text == null ? new SourceUnit(new File(path), config, loader, new ErrorCollector(config)) : new SourceUnit(path, text, config, loader, new ErrorCollector(config));
    try {
      source.parse();
      source.completePhase();
      source.convert();
      return source;
    } catch (GroovyBugError | RuntimeException | LinkageError e) {
      return null;
    }
  }

  /** The top-level classes of the request's lookup, for completion; the compile's own classes come first anyway. */
  private static ClasspathTypes sourceTypes(SourceLookup lookup) {
    List<ClasspathTypes.Type> types = new ArrayList<>();
    for (Symbol symbol : lookup.types()) {
      int dot = symbol.name.lastIndexOf('.');
      String packageName = dot < 0 ? "" : symbol.name.substring(0, dot);
      Completion.Kind kind = symbol.kind == Symbol.Kind.INTERFACE ? Completion.Kind.INTERFACE
          : symbol.kind == Symbol.Kind.ENUM ? Completion.Kind.ENUM : Completion.Kind.CLASS;
      types.add(new ClasspathTypes.Type(symbol.name, symbol.name.substring(dot + 1), packageName, kind));
    }
    return () -> types;
  }

  @Override
  public void forget(String documentPath) {
    gsp.forget(documentPath);
    translations.remove(documentPath);
    units.remove(documentPath);
    completions.remove(documentPath);
  }

  @Override
  public void close() {
    controllerParses.clear();
    fileTexts.clear();
    compiledModels.clear();
    units.clear();
    translations.clear();
    completions.clear();
    gsp.clear();
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
