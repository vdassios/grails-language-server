package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;

/**
 * What only GSP pages resolve by convention, on top of {@link Conventions}: the files {@code asset:} tags name, below
 * {@code grails-app/assets/<any>/}, and the literal model maps given to a view or a template, which make its page
 * variables ({@link PageVariables}).
 */
final class GspConventions {
  /** What asset-pipeline compiles to a stylesheet or a script: a name with one of them may be written with the first. */
  private static final List<String> STYLESHEETS = List.of(".css", ".scss", ".sass", ".less");
  private static final List<String> SCRIPTS = List.of(".js", ".es6", ".ts", ".coffee");

  private final Conventions conventions;
  /** A file's text: the open text, else the file's; null when it cannot be read. Null: no models are read ({@link #models}). */
  private final Function<String, String> texts;
  /** The classes a snippet of Groovy declares, from a parse only (a script's for an expression); null: none read. */
  private final Function<String, List<ClassNode>> snippets;
  /** Whether pages are scanned with {@code \${} as text ({@link GspScanner.Scan}). */
  private final boolean escapes;

  /** Without models: for a page's tags ({@link GspViews}). */
  GspConventions(Conventions conventions) {
    this(conventions, null, null, false);
  }

  GspConventions(Conventions conventions, Function<String, String> texts, Function<String, List<ClassNode>> snippets, boolean escapes) {
    this.conventions = conventions;
    this.texts = texts;
    this.snippets = snippets;
    this.escapes = escapes;
  }

  /** Where a reference points, or null when nothing on disk matches ({@link Conventions#definition}). */
  Location definition(Conventions.Reference reference) {
    if (reference.kind != Conventions.Kind.ASSET) {
      return conventions.definition(reference, null);
    }
    Path file = assetFile(reference);
    return file == null ? null : new Location(file.toString(), new Range(0, 0, 0, 0));
  }

  /** Why a reference resolves to no file, or null when it does or cannot be checked ({@link Conventions#missing}). */
  String missing(Conventions.Reference reference) {
    if (reference.kind != Conventions.Kind.ASSET) {
      return conventions.missing(reference);
    }
    if (reference.value.isEmpty() || conventions.grailsApps().isEmpty()) {
      return null;
    }
    return assetFile(reference) != null || assetDirs().isEmpty() || conventions.fromPlugin("assets/", assetPaths(reference)) ? null
        : "No asset '" + reference.value + "' under any grails-app/assets/*/";
  }

  /** Values a reference may take that start with {@code prefix}, each replacing {@code replace} ({@link Conventions#candidates}). */
  List<Completion> candidates(Conventions.Reference reference, String prefix, Range replace) {
    if (reference.kind != Conventions.Kind.ASSET) {
      return conventions.candidates(reference, null, prefix, replace);
    }
    Map<String, Completion.Kind> labels = new LinkedHashMap<>();
    assetNames(reference).forEach(name -> labels.put(name, Completion.Kind.FILE));
    return Conventions.items(labels, prefix, replace);
  }

  // assets

  /** Every module's asset directories, {@code grails-app/assets/<any>/}, the document's own module first. */
  private List<Path> assetDirs() {
    List<Path> dirs = new ArrayList<>();
    for (Path app : conventions.grailsApps()) {
      Path assets = app.resolve("assets");
      if (Files.isDirectory(assets)) {
        try (Stream<Path> children = Files.list(assets)) {
          children.filter(Files::isDirectory).sorted().forEach(dirs::add);
        } catch (IOException | UncheckedIOException e) {
          // unreadable: no assets from it
        }
      }
    }
    return dirs;
  }

  /** The first file an asset reference names, or null. */
  private Path assetFile(Conventions.Reference reference) {
    List<String> names = assetPaths(reference);
    for (Path dir : assetDirs()) {
      for (String name : names) {
        Path file = dir.resolve(name);
        if (Files.isRegularFile(file)) {
          return file;
        }
      }
    }
    return null;
  }

  /**
   * The paths below an asset directory an asset reference may name: as written, then, for a stylesheet or a script
   * (by its tag or extension), the same name with each extension asset-pipeline compiles to it.
   */
  private static List<String> assetPaths(Conventions.Reference reference) {
    String value = reference.value.startsWith("/") ? reference.value.substring(1) : reference.value;
    List<String> names = new ArrayList<>(List.of(value));
    List<String> group = extensionGroup(reference.base, value);
    if (group != null) {
      String stem = value;
      for (String extension : group) {
        if (value.endsWith(extension)) {
          stem = value.substring(0, value.length() - extension.length());
        }
      }
      for (String extension : group) {
        if (!names.contains(stem + extension)) {
          names.add(stem + extension);
        }
      }
    }
    return names;
  }

  private static List<String> extensionGroup(String tag, String name) {
    if ("stylesheet".equals(tag) || STYLESHEETS.stream().anyMatch(name::endsWith)) {
      return STYLESHEETS;
    }
    return "javascript".equals(tag) || SCRIPTS.stream().anyMatch(name::endsWith) ? SCRIPTS : null;
  }

  /** The names an asset reference may take: every file below an asset directory, of the tag's kind for stylesheets and scripts. */
  private List<String> assetNames(Conventions.Reference reference) {
    Set<String> names = new java.util.TreeSet<>();
    for (Path dir : assetDirs()) {
      for (Path file : Conventions.files(dir)) {
        String name = dir.relativize(file).toString().replace('\\', '/');
        List<String> group = "stylesheet".equals(reference.base) ? STYLESHEETS : "javascript".equals(reference.base) ? SCRIPTS : null;
        if (group == null || group.stream().anyMatch(name::endsWith)) {
          names.add(name);
        }
      }
    }
    return new ArrayList<>(names);
  }

  // models

  /** A key of a literal model map an action or a page gives a view or a template, and where it is. */
  static final class ModelEntry {
    final MapEntryExpression entry;
    /** The action: a method, or a property assigned a closure; null in a page. */
    final AnnotatedNode action;
    /** The controller; null in a page. */
    final ClassNode controller;
    /** The file holding the map: a controller, or the page rendering the template. */
    final String path;
    /** In a page: the {@code g:render} tag giving the template its model; else null. */
    final PageRender render;

    ModelEntry(MapEntryExpression entry, AnnotatedNode action, ClassNode controller, String path, PageRender render) {
      this.entry = entry;
      this.action = action;
      this.controller = controller;
      this.path = path;
      this.render = render;
    }

    /** The key's range in its file. */
    Range keyRange() {
      Expression key = entry.getKeyExpression();
      if (render == null) {
        return new Range(key.getLineNumber() - 1, key.getColumnNumber() - 1, key.getLastLineNumber() - 1, key.getLastColumnNumber() - 1);
      }
      int[] start = render.position(key.getLineNumber(), key.getColumnNumber());
      int[] end = render.position(key.getLastLineNumber(), key.getLastColumnNumber());
      return new Range(start[0], start[1], end[0], end[1]);
    }
  }

  /** A {@code <g:render template="..." model="[...]"/>} in a page. */
  static final class PageRender {
    /** The rendering page's text, and its lines. */
    final String pageText;
    private final Lines pageLines;
    /** The {@code template} attribute's value, and which of the page's {@code g:render} tags with that value this is (from 0). */
    final String template;
    final int index;
    /** The {@code model} attribute's value, parsed as Groovy from this offset in the page. */
    final int modelStart;
    final String model;

    PageRender(String pageText, Lines pageLines, String template, int index, int modelStart, String model) {
      this.pageText = pageText;
      this.pageLines = pageLines;
      this.template = template;
      this.index = index;
      this.modelStart = modelStart;
      this.model = model;
    }

    /** The page's 0-based line and column of a 1-based position in the parsed model. */
    int[] position(int line, int column) {
      return pageLines.position(modelStart + new Lines(model).offset(line - 1, column - 1));
    }
  }

  /** A literal model map in an action's code, and the view or template it is given to. */
  private static final class ActionModel {
    final Conventions.Kind kind;
    /** The view or template, as named. */
    final String name;
    final MapExpression map;

    ActionModel(Conventions.Kind kind, String name, MapExpression map) {
      this.kind = kind;
      this.name = name;
      this.map = map;
    }
  }

  /**
   * The literal model maps given to a view or a template: for a view, the actions that render it: {@code render(view:
   * 'show', model: [book: b])}, {@code respond x, model: [...]} (its view the action's name unless named), and a map the
   * action returns (its view the action's name); for a template, {@code render(template: 'row', model: [...])} in actions
   * and {@code <g:render template="row" model="[...]"/>} in pages. Empty for a layout. Actions inherited from a
   * superclass are not read.
   */
  List<ModelEntry> models(String viewPath) {
    String page = viewPath.replace('\\', '/');
    int app = page.lastIndexOf("/grails-app/views/");
    String relative = app < 0 ? "" : page.substring(app + "/grails-app/".length());
    if (texts == null || !relative.endsWith(".gsp") || relative.startsWith("views/layouts/")) {
      return List.of();
    }
    List<ModelEntry> found = new ArrayList<>();
    String[] segments = relative.substring("views/".length(), relative.length() - ".gsp".length()).split("/");
    boolean template = segments[segments.length - 1].startsWith("_");
    Conventions.Kind kind = template ? Conventions.Kind.TEMPLATE : Conventions.Kind.VIEW;
    // only the controller of the page's directory renders it unnamed or by a relative name; another names it by its
    // absolute name ('/book/show', '/book/row' for _row.gsp), so files that do not hold that name are not parsed
    String absolute = "/" + String.join("/", segments);
    if (template) {
      absolute = absolute.substring(0, absolute.lastIndexOf('/') + 1) + segments[segments.length - 1].substring(1);
    }
    Pattern named = Pattern.compile("[\"']" + Pattern.quote(absolute) + "(\\.gsp)?[\"']");
    conventions.controllers().forEach((name, file) -> {
      if (segments.length == 1 || !name.equals(segments[0])) {
        String text = texts.apply(file.toString());
        if (text == null || !named.matcher(text).find()) {
          return;
        }
      }
      ClassNode type = conventions.declared(file);
      if (type == null) {
        return;
      }
      Conventions.actions(type).forEach((action, node) -> {
        Statement code = node instanceof MethodNode ? ((MethodNode) node).getCode() : ((ClosureExpression) ((PropertyNode) node).getInitialExpression()).getCode();
        for (ActionModel model : modelsOf(code, action)) {
          if (model.kind == kind && Conventions.viewPaths(new Conventions.Reference(kind, model.name, null, name)).contains(relative)) {
            for (MapEntryExpression entry : model.map.getMapEntryExpressions()) {
              found.add(new ModelEntry(entry, node, type, file.toString(), null));
            }
          }
        }
      });
    });
    if (template && snippets != null) {
      pageModels(relative, segments, named, found);
    }
    return found;
  }

  /** The literal {@code model} maps of the {@code g:render} tags of every module's pages rendering a template. */
  private void pageModels(String relative, String[] segments, Pattern named, List<ModelEntry> found) {
    for (Path app : conventions.grailsApps()) {
      Path views = app.resolve("views");
      for (Path file : Conventions.files(views)) {
        String path = views.relativize(file).toString().replace('\\', '/');
        if (!path.endsWith(".gsp")) {
          continue;
        }
        // a relative template name starts from the page's controller: the first directory below views/
        String controller = path.contains("/") ? path.substring(0, path.indexOf('/')) : null;
        String text = texts.apply(file.toString());
        if (text == null || !text.contains("template") || !(segments.length > 1 && segments[0].equals(controller) || named.matcher(text).find())) {
          continue;
        }
        Map<String, Integer> seen = new LinkedHashMap<>();
        Lines lines = null;
        for (GspScanner.Tag tag : GspScanner.scan(text, escapes).tags) {
          GspScanner.Attribute name = tag.closing || !"g".equals(tag.namespace) || !"render".equals(tag.name) ? null : tag.attribute("template");
          if (name == null || name.value == null) {
            continue;
          }
          int index = seen.merge(name.value, 1, Integer::sum) - 1;
          GspScanner.Attribute model = tag.attribute("model");
          if (model == null || model.valueStart < 0 || !Conventions.viewPaths(new Conventions.Reference(Conventions.Kind.TEMPLATE, name.value, null, controller)).contains(relative)) {
            continue;
          }
          MapExpression map = modelMap(model.value);
          if (map != null) {
            lines = lines == null ? new Lines(text) : lines;
            PageRender render = new PageRender(text, lines, name.value, index, model.valueStart, expressionOf(model.value));
            for (MapEntryExpression entry : map.getMapEntryExpressions()) {
              found.add(new ModelEntry(entry, null, null, file.toString(), render));
            }
          }
        }
      }
    }
  }

  /** A {@code model} attribute's value as Groovy, at the same offsets: {@code [...]}, or {@code ${[...]}} with its braces blanked. */
  private static String expressionOf(String value) {
    String trimmed = value.trim();
    if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
      int open = value.indexOf("${");
      int close = value.lastIndexOf('}');
      return value.substring(0, open) + "  " + value.substring(open + 2, close) + " " + value.substring(close + 1);
    }
    return value;
  }

  /** The map literal a {@code model} attribute holds, from a parse; null when it holds none. */
  private MapExpression modelMap(String value) {
    String groovy = expressionOf(value);
    String trimmed = groovy.trim();
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
      return null;
    }
    for (ClassNode type : snippets.apply(groovy)) {
      MethodNode run = type.isScript() ? type.getMethod("run", org.codehaus.groovy.ast.Parameter.EMPTY_ARRAY) : null;
      Statement code = run == null ? null : run.getCode();
      List<Statement> statements = code instanceof BlockStatement ? ((BlockStatement) code).getStatements() : List.of();
      Statement only = statements.size() == 1 ? statements.get(0) : null;
      Expression expression = only instanceof ExpressionStatement ? ((ExpressionStatement) only).getExpression() : only instanceof ReturnStatement ? ((ReturnStatement) only).getExpression() : null;
      if (expression instanceof MapExpression) {
        return (MapExpression) expression;
      }
    }
    return null;
  }

  /** Each literal model in an action's code. */
  private static List<ActionModel> modelsOf(Statement code, String action) {
    List<ActionModel> models = new ArrayList<>();
    if (code == null) {
      return models;
    }
    code.visit(new ClassCodeVisitorSupport() {
      private int closures;

      @Override
      protected SourceUnit getSourceUnit() {
        return null;
      }

      @Override
      public void visitMethodCallExpression(MethodCallExpression call) {
        String method = call.getMethodAsString();
        if (call.isImplicitThis() && ("render".equals(method) || "respond".equals(method))) {
          String view = Conventions.argumentValue(call, "view");
          String template = "render".equals(method) ? Conventions.argumentValue(call, "template") : null;
          Expression model = null;
          for (MapEntryExpression entry : Conventions.entries(call)) {
            if (entry.getKeyExpression().getText().equals("model")) {
              model = entry.getValueExpression();
            }
          }
          if (view == null && "respond".equals(method)) {
            view = action;
          }
          if (view != null && !view.isEmpty() && model instanceof MapExpression) {
            models.add(new ActionModel(Conventions.Kind.VIEW, view, (MapExpression) model));
          } else if (template != null && !template.isEmpty() && model instanceof MapExpression) {
            models.add(new ActionModel(Conventions.Kind.TEMPLATE, template, (MapExpression) model));
          }
        }
        super.visitMethodCallExpression(call);
      }

      @Override
      public void visitClosureExpression(ClosureExpression closure) {
        closures++;
        super.visitClosureExpression(closure);
        closures--;
      }

      @Override
      public void visitReturnStatement(ReturnStatement statement) {
        if (closures == 0 && statement.getExpression() instanceof MapExpression) {
          models.add(new ActionModel(Conventions.Kind.VIEW, action, (MapExpression) statement.getExpression()));
        }
        super.visitReturnStatement(statement);
      }
    });
    // the action's last statement, returned without "return"
    List<Statement> statements = code instanceof BlockStatement ? ((BlockStatement) code).getStatements() : List.of(code);
    Statement last = statements.isEmpty() ? null : statements.get(statements.size() - 1);
    if (last instanceof ExpressionStatement && ((ExpressionStatement) last).getExpression() instanceof MapExpression) {
      models.add(new ActionModel(Conventions.Kind.VIEW, action, (MapExpression) ((ExpressionStatement) last).getExpression()));
    }
    return models;
  }
}
