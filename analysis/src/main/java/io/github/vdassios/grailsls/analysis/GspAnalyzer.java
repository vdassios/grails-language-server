package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.analysis.GroovyAnalyzer.Kept;
import io.github.vdassios.grailsls.analysis.GroovyAnalyzer.LastCompletion;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.CompletionDetail;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SignatureHelp;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * The GSP pages of a {@link GroovyAnalyzer}: a page's tags ({@link GspViews}), and its translation to Groovy
 * ({@link GspTranslation}) with its page variables declared ({@link PageVariables}), compiled and kept by the analyzer
 * like a document of the page's module. Queries inside page code run on that translation.
 */
final class GspAnalyzer {
  /** The analyzer that compiles the pages' translations and keeps their compiles and completions. */
  private final GroovyAnalyzer groovy;
  /** GSP pages: tags and taglibs. */
  private final GspViews views;
  /** {@link GspTranslation#implicitObjects} of this loader, once read. */
  private volatile String implicitObjects;
  /** {@link GspTranslation#escapes} of this loader, once read. */
  private volatile Boolean escapes;
  /** The translation of each GSP page whose generated Groovy the analyzer keeps. */
  private final Map<String, GspTranslation> translations = new ConcurrentHashMap<>();
  /** Texts of the files pages read (controllers, templates), shared with the taglibs. */
  private final FileTexts texts;

  GspAnalyzer(GroovyAnalyzer groovy, Taglibs taglibs, FileTexts texts) {
    this.groovy = groovy;
    this.texts = texts;
    this.views = new GspViews(taglibs, (lookup, path) -> new GspConventions(groovy.conventions(lookup, path)));
  }

  /**
   * A GSP page: its tag hints ({@link GspViews}), then its translation to Groovy ({@link GspTranslation}) compiled like
   * a document of the page's module, diagnostics mapped back onto the page. A class path without Grails's page parser
   * gives the tag hints only.
   */
  CompileResult compile(CompileRequest request) {
    long start = System.nanoTime();
    groovy.units.remove(request.path);
    translations.remove(request.path);
    groovy.completions.remove(request.path);
    GspScanner.Scan scan = GspScanner.scan(request.text, escapes());
    CompileResult tags = views.compile(request, scan);
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
      return new CompileResult(diagnostics, false, 0, GroovyAnalyzer.millisSince(start));
    }
    List<Diagnostic> found = new ArrayList<>();
    Kept kept = groovy.run(new CompileRequest(request.path, translation.groovy, request.lookup, request.cancelled), found);
    if (kept.cancelled) {
      return new CompileResult(List.of(), true, kept.sources, GroovyAnalyzer.millisSince(start));
    }
    linkPage(kept, request.path);
    compiledModels.put(request.path, new CompiledModels(request.text, renderModelTypes(kept, request.path, request.lookup)));
    groovy.units.put(request.path, kept);
    translations.put(request.path, translation);
    for (Diagnostic diagnostic : found) {
      diagnostics.add(diagnostic.path.equals(request.path) ? translation.toPage(diagnostic) : diagnostic);
    }
    return new CompileResult(diagnostics, false, kept.sources, GroovyAnalyzer.millisSince(start));
  }

  /** Whether this loader's Grails takes {@code \${} for text ({@link GspTranslation#escapes}), as pages are scanned. */
  private boolean escapes() {
    Boolean read = escapes;
    if (read == null) {
      read = GspTranslation.escapes(groovy.parent);
      escapes = read;
    }
    return read;
  }

  /** A page's translation, with the implicit objects typed and its variables declared; null without Grails's page parser. */
  private GspTranslation translate(String path, GspScanner.Scan page, SourceLookup lookup, BooleanSupplier cancelled) {
    String declarations = implicitObjects;
    if (declarations == null) {
      declarations = GspTranslation.implicitObjects(groovy.parent);
      implicitObjects = declarations;
    }
    Set<String> taken = new HashSet<>(Set.of("out"));
    for (String declaration : declarations.split(";")) {
      taken.add(declaration.trim().substring(declaration.trim().lastIndexOf(' ') + 1));
    }
    PageVariables variables = PageVariables.of(path, page, modelConventions(lookup, path), taken, name -> resolves(name, lookup), model -> compiledType(model, lookup, cancelled));
    return GspTranslation.of(groovy.parent, path, page, declarations + variables.declarations, variables.definitions, groovy.reports);
  }

  /** Whether a qualified class name resolves in this module: a project source, or a class on its class path. */
  private boolean resolves(String name, SourceLookup lookup) {
    if (lookup != null && lookup.fileOf(name) != null) {
      return true;
    }
    try {
      Class.forName(name, false, groovy.parent);
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

  /** {@link GspConventions} whose parses are kept while a file's text stays the same (open text, else the file). */
  private GspConventions modelConventions(SourceLookup lookup, String documentPath) {
    return new GspConventions(new Conventions(lookup, documentPath, file -> {
      String text = texts.of(file, lookup);
      if (text == null) {
        return List.of();
      }
      Parsed parsed = controllerParses.get(file);
      if (parsed == null || !parsed.text.equals(text)) {
        parsed = new Parsed(text, GroovyAnalyzer.parsedClasses(file, text));
        controllerParses.put(file, parsed);
      }
      return parsed.classes;
    }), file -> texts.of(file, lookup), snippet -> GroovyAnalyzer.parsedClasses("model.groovy", snippet), escapes());
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
  private static String modelKey(GspConventions.ModelEntry model) {
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
  private String compiledType(GspConventions.ModelEntry model, SourceLookup lookup, BooleanSupplier cancelled) {
    String text = model.render != null ? model.render.pageText : texts.of(model.path, lookup);
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
  private Map<String, String> controllerModelTypes(GspConventions.ModelEntry model, String text, SourceLookup lookup, BooleanSupplier cancelled) {
    if (!resolves(model.controller.getName(), lookup)) {
      // another module's controller, whose classes this module may not see
      return Map.of();
    }
    Kept kept = groovy.units.get(model.path);
    if (kept == null || !text.equals(kept.text)) {
      kept = groovy.run(new CompileRequest(model.path, text, lookup, cancelled), new ArrayList<>());
      if (kept.cancelled) {
        return null;
      }
    }
    Map<String, String> types = new LinkedHashMap<>();
    ASTNodeVisitor ast = kept.ast();
    for (ClassNode type : classesOf(kept, model.path)) {
      new ClassCodeVisitorSupport() {
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
      new ClassCodeVisitorSupport() {
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

  /** A query at a page position inside page code, on the page's kept translation; null elsewhere. */
  private <T> T pageQuery(String documentPath, int line, int column, BiFunction<Kept, int[], T> query) {
    Kept kept = groovy.units.get(documentPath);
    GspTranslation translation = translations.get(documentPath);
    int[] at = kept == null || translation == null ? null : translation.toGroovy(line, column);
    return at == null ? null : query.apply(kept, at);
  }

  /**
   * In a page: tag, attribute and attribute value completion ({@link GspViews}); else, inside page code, completion on
   * the page's translation, at the generated position. The page is compiled again unless the kept compile is of its
   * text; when that finds nothing, with a name typed at the position ({@code ${book.}}), then with the expression closed
   * too ({@code ${book.} without its brace}).
   */
  CompletionList completion(CompileRequest request, int line, int column) {
    groovy.completions.remove(request.path);
    GspScanner.Scan scan = GspScanner.scan(request.text, escapes());
    List<CompletionDetail> details = new ArrayList<>();
    CompletionList tags = views.completion(request, scan, line, column, details);
    if (!tags.items.isEmpty()) {
      if (!details.isEmpty()) {
        groovy.completions.put(request.path, new LastCompletion(details));
      }
      return tags;
    }
    Function<String, Map<String, Taglibs.Tag>> namespaces = groovy.namespaces(request.lookup);
    String placeholder = Completions.placeholder(GroovyAnalyzer.lineBefore(request.text, line, column));
    for (String insert : new String[] { "", placeholder, placeholder + "}" }) {
      PageCompile compiled = pageCompile(request, scan, line, column, insert);
      if (compiled == null) {
        continue;
      }
      int[] at = compiled.at;
      Completions found = Completions.at(compiled.kept.ast(), compiled.kept.uri, request.lookup, null, namespaces, true, at[0], at[1], GroovyAnalyzer.lineBefore(compiled.translation.groovy, at[0], at[1]));
      if (found != null) {
        groovy.completions.put(request.path, new LastCompletion(found, compiled.kept));
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
    Kept kept = groovy.units.get(request.path);
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
      kept = groovy.run(new CompileRequest(request.path, translation.groovy, request.lookup, request.cancelled), new ArrayList<>());
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

  /** On a page: a tag or a convention value ({@link GspViews}), else, inside page code, on the page's translation. */
  Location definition(String documentPath, int line, int column) {
    Location tag = views.definition(documentPath, line, column);
    return tag != null ? tag : pageQuery(documentPath, line, column, (kept, at) -> {
      Taglibs.Tag called = groovy.tagAt(kept, true, at[0], at[1]);
      if (called != null) {
        return called.docs().location;
      }
      Location found = Navigation.definition(kept.ast(), kept.uri, kept.lookup, groovy.conventions(kept.lookup, documentPath), kept.text, at[0], at[1]);
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

  /** On a page: a tag or an attribute ({@link GspViews}), else, inside page code, on the page's translation. */
  String hover(String documentPath, int line, int column) {
    String tag = views.hover(documentPath, line, column);
    return tag != null ? tag : pageQuery(documentPath, line, column, (kept, at) -> {
      Taglibs.Tag called = groovy.tagAt(kept, true, at[0], at[1]);
      return called != null ? GspViews.markdown(called) : Navigation.hover(kept.ast(), kept.uri, kept.lookup, kept.text, at[0], at[1]);
    });
  }

  /** Inside page code, on the page's translation; null elsewhere. */
  SignatureHelp signatureHelp(CompileRequest request, int line, int column) {
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

  /** Drops what is kept for a file: a page's tags and translation, and the text, parse and model types pages read of it. */
  void forget(String documentPath) {
    views.forget(documentPath);
    translations.remove(documentPath);
    controllerParses.remove(documentPath);
    texts.forget(documentPath);
    compiledModels.remove(documentPath);
  }

  /** Whether a file's text, parse or model types are kept. For tests. */
  boolean keeps(String path) {
    return controllerParses.containsKey(path) || texts.keeps(path) || compiledModels.containsKey(path);
  }

  /** Drops everything kept. */
  void clear() {
    controllerParses.clear();
    texts.clear();
    compiledModels.clear();
    translations.clear();
    views.clear();
  }
}
