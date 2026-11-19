package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.AnnotatedNode;
import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.FieldNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.PropertyNode;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MapEntryExpression;
import org.codehaus.groovy.ast.expr.MapExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.ast.stmt.BlockStatement;
import org.codehaus.groovy.ast.stmt.ExpressionStatement;
import org.codehaus.groovy.ast.stmt.ReturnStatement;
import org.codehaus.groovy.ast.stmt.Statement;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SourceLookup;
import net.prominic.groovyls.compiler.ast.ASTNodeVisitor;
import net.prominic.groovyls.compiler.util.GroovyASTUtils;

/**
 * Strings Grails resolves by convention (plan B3's table, B4 2.5): views, templates and layouts under
 * {@code grails-app/views}, controllers by logical name and their actions. Definition, value completion and hints for
 * views and templates that do not exist. Files are looked up in every module's {@code grails-app}, the document's own
 * module first ({@link SourceLookup#grailsAppDirs}).
 */
public final class Conventions {
  enum Kind {
    VIEW, TEMPLATE, LAYOUT, CONTROLLER, ACTION, ASSET
  }

  static final List<String> RENDER_KEYS = List.of("view", "template", "model", "text", "status", "contentType", "encoding", "layout", "plugin", "collection", "bean", "var");
  static final List<String> REDIRECT_KEYS = List.of("controller", "action", "id", "params", "uri", "url", "fragment", "permanent", "mapping", "namespace");
  private static final Set<String> LINK_CALLS = Set.of("redirect", "chain", "forward", "createLink", "link");
  private static final List<String> VIEW_EXTENSIONS = List.of(".gsp", ".jsp", ".gson");
  /** What asset-pipeline compiles to a stylesheet or a script: a name with one of them may be written with the first. */
  private static final List<String> STYLESHEETS = List.of(".css", ".scss", ".sass", ".less");
  private static final List<String> SCRIPTS = List.of(".js", ".es6", ".ts", ".coffee");
  private static final String CONTROLLER = "Controller";

  /** A string that names something by convention. */
  static final class Reference {
    final Kind kind;
    final String value;
    /** The string in a Groovy document; null in a GSP page. */
    final ConstantExpression node;
    /**
     * For a view or template, the directory under {@code views/} that relative names start from, or null when only
     * absolute names resolve; for an action, the logical name of its controller, or null when it is not known; for an
     * asset, the {@code asset:} tag naming it.
     */
    final String base;

    Reference(Kind kind, String value, ConstantExpression node, String base) {
      this.kind = kind;
      this.value = value;
      this.node = node;
      this.base = base;
    }
  }

  private final SourceLookup lookup;
  private final String documentPath;
  /** The classes a file declares, from a parse only. */
  private final Function<String, List<ClassNode>> parse;
  /** A file's text: the open text, else the file's; null when it cannot be read. Null: no models are read ({@link #models}). */
  private final Function<String, String> texts;
  /** The classes a snippet of Groovy declares, from a parse only (a script's for an expression); null: none read. */
  private final Function<String, List<ClassNode>> snippets;

  Conventions(SourceLookup lookup, String documentPath, Function<String, List<ClassNode>> parse) {
    this(lookup, documentPath, parse, null, null);
  }

  Conventions(SourceLookup lookup, String documentPath, Function<String, List<ClassNode>> parse, Function<String, String> texts, Function<String, List<ClassNode>> snippets) {
    this.lookup = lookup;
    this.documentPath = documentPath;
    this.parse = parse;
    this.texts = texts;
    this.snippets = snippets;
  }

  // what a string names

  /** The reference a node is, or null. */
  Reference reference(ASTNode node, ASTNodeVisitor ast) {
    if (!(node instanceof ConstantExpression) || !(((ConstantExpression) node).getValue() instanceof String)) {
      return null;
    }
    ConstantExpression string = (ConstantExpression) node;
    String value = (String) string.getValue();
    ClassNode owner = (ClassNode) GroovyASTUtils.getEnclosingNodeOfType(node, ClassNode.class, ast);
    String controller = controllerName(owner);
    ASTNode parent = ast.getParent(node);
    if (parent instanceof MapEntryExpression) {
      MapEntryExpression entry = (MapEntryExpression) parent;
      if (entry.getKeyExpression() == node) {
        // static allowedMethods = [save: 'POST']
        ASTNode map = ast.getParent(entry);
        FieldNode field = map == null ? null : field(ast.getParent(map));
        return controller != null && field != null && field.isStatic() && field.getName().equals("allowedMethods") ? new Reference(Kind.ACTION, value, string, controller) : null;
      }
      MethodCallExpression call = callOf(entry, ast);
      return call == null ? null : argument(call, entry.getKeyExpression().getText(), value, string, controller, urlMappings(owner));
    }
    FieldNode field = field(parent);
    if (controller != null && field != null && field.isStatic() && field.getName().equals("layout")) {
      return new Reference(Kind.LAYOUT, value, string, null);
    }
    return null;
  }

  private Reference argument(MethodCallExpression call, String key, String value, ConstantExpression node, String controller, boolean urlMappings) {
    String method = call.getMethodAsString();
    if (urlMappings || method != null && LINK_CALLS.contains(method)) {
      switch (key) {
        case "controller":
          return new Reference(Kind.CONTROLLER, value, node, null);
        case "action":
          String named = argumentValue(call, "controller");
          return new Reference(Kind.ACTION, value, node, named == null ? (urlMappings ? null : controller) : named.isEmpty() ? null : named);
        case "view":
          // "/x"(controller: 'book', view: 'index'): the controller's views
          String viewsOf = urlMappings ? argumentValue(call, "controller") : null;
          return urlMappings ? new Reference(Kind.VIEW, value, node, viewsOf == null || viewsOf.isEmpty() ? null : lowerFirst(viewsOf)) : null;
        default:
          return null;
      }
    }
    if (("render".equals(method) || "respond".equals(method)) && call.isImplicitThis()) {
      if (key.equals("view")) {
        return new Reference(Kind.VIEW, value, node, controller);
      } else if (key.equals("template") && method.equals("render")) {
        return new Reference(Kind.TEMPLATE, value, node, controller);
      }
    }
    return null;
  }

  /** The call a named argument belongs to: {@code render(view: 'x')}, {@code render([view: 'x'])}. */
  private static MethodCallExpression callOf(MapEntryExpression entry, ASTNodeVisitor ast) {
    ASTNode map = ast.getParent(entry);
    ASTNode arguments = map instanceof MapExpression ? ast.getParent(map) : null;
    ASTNode call = arguments instanceof TupleExpression ? ast.getParent(arguments) : arguments;
    return call instanceof MethodCallExpression && ((MethodCallExpression) call).getArguments() == arguments ? (MethodCallExpression) call : null;
  }

  /** A named argument's string value; empty when the argument is not a string, null when there is none. */
  private static String argumentValue(MethodCallExpression call, String key) {
    for (MapEntryExpression entry : entries(call)) {
      if (entry.getKeyExpression().getText().equals(key)) {
        Expression value = entry.getValueExpression();
        return value instanceof ConstantExpression && ((ConstantExpression) value).getValue() instanceof String ? (String) ((ConstantExpression) value).getValue() : "";
      }
    }
    return null;
  }

  private static List<MapEntryExpression> entries(MethodCallExpression call) {
    List<MapEntryExpression> entries = new ArrayList<>();
    Expression arguments = call.getArguments();
    List<Expression> expressions = arguments instanceof TupleExpression ? ((TupleExpression) arguments).getExpressions() : List.of(arguments);
    for (Expression argument : expressions) {
      if (argument instanceof MapExpression) {
        entries.addAll(((MapExpression) argument).getMapEntryExpressions());
      }
    }
    return entries;
  }

  private static FieldNode field(ASTNode node) {
    return node instanceof FieldNode ? (FieldNode) node : node instanceof PropertyNode ? ((PropertyNode) node).getField() : null;
  }

  /** Keys of the named arguments of {@code render} or {@code redirect} not given yet, when {@code typed} is one of their arguments. */
  static List<String> argumentKeys(VariableExpression typed, ASTNodeVisitor ast) {
    ASTNode arguments = ast.getParent(typed);
    ASTNode parent = arguments == null ? null : ast.getParent(arguments);
    if (!(arguments instanceof TupleExpression) || !(parent instanceof MethodCallExpression) || ((MethodCallExpression) parent).getArguments() != arguments
        || !((MethodCallExpression) parent).isImplicitThis()) {
      return List.of();
    }
    String method = ((MethodCallExpression) parent).getMethodAsString();
    List<String> keys = "render".equals(method) ? RENDER_KEYS : "redirect".equals(method) ? REDIRECT_KEYS : List.of();
    return Gorm.unusedKeys(keys, (TupleExpression) arguments);
  }

  /** The logical name of the controller a class is ({@code BookController} → {@code book}), or null. */
  private String controllerName(ClassNode type) {
    ClassNode top = topLevel(type);
    if (top == null || !inGrailsApp("controllers")) {
      return null;
    }
    String name = top.getNameWithoutPackage();
    return name.endsWith(CONTROLLER) && name.length() > CONTROLLER.length() ? logicalName(name.substring(0, name.length() - CONTROLLER.length())) : null;
  }

  private boolean urlMappings(ClassNode type) {
    ClassNode top = topLevel(type);
    return top != null && top.getNameWithoutPackage().endsWith("UrlMappings") && inGrailsApp("controllers");
  }

  private static ClassNode topLevel(ClassNode type) {
    while (type != null && type.getOuterClass() != null) {
      type = type.getOuterClass();
    }
    return type;
  }

  private boolean inGrailsApp(String directory) {
    return documentPath != null && documentPath.replace('\\', '/').contains("/grails-app/" + directory + "/");
  }

  private static String lowerFirst(String name) {
    return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  /** Grails's logical property name: {@code Book} → {@code book}, {@code URL} stays {@code URL}. */
  static String logicalName(String name) {
    if (name.length() > 1 && Character.isUpperCase(name.charAt(0)) && Character.isUpperCase(name.charAt(1))) {
      return name;
    }
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  // definition

  /** Where a reference points, or null when nothing on disk matches. */
  Location definition(Reference reference, ASTNodeVisitor ast) {
    switch (reference.kind) {
      case CONTROLLER:
        Path file = controllerFile(reference.value);
        ClassNode type = file == null ? null : declared(file);
        Range range = type == null ? null : NodeRanges.of(type);
        return file == null ? null : new Location(file.toString(), range == null ? new Range(0, 0, 0, 0) : range);
      case ACTION:
        Owner owner = controller(reference, ast);
        Owner action = owner == null ? null : actionsOf(owner).get(reference.value);
        Range at = action == null ? null : NodeRanges.of(action.node);
        return at == null ? null : new Location(action.path, at);
      default:
        Path view = reference.kind == Kind.ASSET ? assetFile(reference) : viewFile(reference);
        return view == null ? null : new Location(view.toString(), new Range(0, 0, 0, 0));
    }
  }

  /**
   * Why a view, template, layout or asset reference resolves to no file, or null when it does or cannot be checked
   * (a relative name with no base, no {@code grails-app} known).
   */
  String missing(Reference reference) {
    if (reference.value.isEmpty() || grailsApps().isEmpty()) {
      return null;
    }
    if (reference.kind == Kind.ASSET) {
      return assetFile(reference) != null || assetDirs().isEmpty() ? null : "No asset '" + reference.value + "' under any grails-app/assets/*/";
    }
    List<String> paths = viewPaths(reference);
    if (paths.isEmpty() || viewFile(reference) != null) {
      return null;
    }
    String what = reference.kind == Kind.TEMPLATE ? "template" : reference.kind == Kind.LAYOUT ? "layout" : "view";
    return "No " + what + " '" + reference.value + "': grails-app/" + paths.get(0) + " does not exist";
  }

  // assets

  /** Every module's asset directories, {@code grails-app/assets/<any>/}, the document's own module first. */
  private List<Path> assetDirs() {
    List<Path> dirs = new ArrayList<>();
    for (Path app : grailsApps()) {
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
  private Path assetFile(Reference reference) {
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
  private static List<String> assetPaths(Reference reference) {
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
  private List<String> assetNames(Reference reference) {
    Set<String> names = new java.util.TreeSet<>();
    for (Path dir : assetDirs()) {
      for (Path file : files(dir)) {
        String name = dir.relativize(file).toString().replace('\\', '/');
        List<String> group = "stylesheet".equals(reference.base) ? STYLESHEETS : "javascript".equals(reference.base) ? SCRIPTS : null;
        if (group == null || group.stream().anyMatch(name::endsWith)) {
          names.add(name);
        }
      }
    }
    return new ArrayList<>(names);
  }

  /** The first file a view, template or layout reference names, or null. */
  private Path viewFile(Reference reference) {
    for (String relative : viewPaths(reference)) {
      for (Path app : grailsApps()) {
        Path file = app.resolve(relative);
        if (Files.isRegularFile(file)) {
          return file;
        }
      }
    }
    return null;
  }

  /** The paths under {@code grails-app} a view, template or layout reference may name, in order; empty when relative with no base. */
  private static List<String> viewPaths(Reference reference) {
    if (reference.kind == Kind.LAYOUT) {
      return List.of("views/layouts/" + reference.value + ".gsp");
    }
    String value = reference.value;
    int slash = value.lastIndexOf('/');
    String dir = slash < 0 ? "" : value.substring(0, slash);
    String name = value.substring(slash + 1);
    if (name.isEmpty()) {
      return List.of();
    }
    if (value.startsWith("/")) {
      dir = dir.isEmpty() ? "" : dir.substring(1);
    } else if (reference.base == null) {
      return List.of();
    } else {
      dir = dir.isEmpty() ? reference.base : reference.base + "/" + dir;
    }
    String prefix = dir.isEmpty() ? "views/" : "views/" + dir + "/";
    if (reference.kind == Kind.TEMPLATE) {
      // JSON views (grails-views) keep templates as _name.gson
      return List.of(prefix + "_" + name + ".gsp", prefix + "_" + name + ".gson");
    }
    List<String> paths = VIEW_EXTENSIONS.stream().map(extension -> prefix + name + extension).collect(Collectors.toList());
    if (VIEW_EXTENSIONS.stream().anyMatch(name::endsWith)) {
      // written with its extension: g:include view="/a/b.gsp"
      paths.add(0, prefix + name);
    }
    return paths;
  }

  /** A class or member and the file declaring it. */
  private static final class Owner {
    final ClassNode type;
    final AnnotatedNode node;
    final String path;

    Owner(ClassNode type, String path) {
      this(type, type, path);
    }

    Owner(ClassNode type, AnnotatedNode node, String path) {
      this.type = type;
      this.node = node;
      this.path = path;
    }
  }

  /**
   * A controller's actions by name, its own first, then those of its superclasses declared in the project's sources
   * (each read from a parse of its file). Superclasses from jars are not read.
   */
  private Map<String, Owner> actionsOf(Owner controller) {
    Map<String, Owner> all = new LinkedHashMap<>();
    Set<String> seen = new HashSet<>();
    Owner current = controller;
    for (int depth = 0; current != null && depth < 10; depth++) {
      Owner declaring = current;
      actions(declaring.type).forEach((name, node) -> all.putIfAbsent(name, new Owner(declaring.type, node, declaring.path)));
      Owner next = null;
      for (String name : superclassNames(declaring.type)) {
        String file = lookup == null || !seen.add(name) ? null : lookup.fileOf(name);
        if (file != null) {
          for (ClassNode type : parse.apply(file)) {
            if (type.getName().equals(name)) {
              next = new Owner(type, file);
            }
          }
          break;
        }
      }
      current = next;
    }
    return all;
  }

  /**
   * Binary names the superclass may have, in Groovy's order: as resolved or written qualified, else by the file's
   * imports, its package, its star imports. Empty for {@code Object}.
   */
  private static List<String> superclassNames(ClassNode type) {
    ClassNode superclass = type.getUnresolvedSuperClass(false);
    String name = superclass == null ? null : superclass.getName();
    if (name == null || name.equals("java.lang.Object") || name.equals("Object")) {
      return List.of();
    }
    ModuleNode module = type.getModule();
    if (name.contains(".") || module == null) {
      return List.of(name);
    }
    List<String> names = new ArrayList<>();
    if (module.getImport(name) != null) {
      names.add(module.getImport(name).getType().getName());
    }
    String packageName = module.getPackageName();
    names.add(packageName == null ? name : (packageName.endsWith(".") ? packageName : packageName + ".") + name);
    module.getStarImports().forEach(star -> names.add(star.getPackageName() + name));
    return names;
  }

  /** The controller an action reference belongs to: the compiled document's own class, or another controller's parsed file. */
  private Owner controller(Reference reference, ASTNodeVisitor ast) {
    if (reference.base == null) {
      return null;
    }
    ClassNode own = reference.node == null ? null : topLevel((ClassNode) GroovyASTUtils.getEnclosingNodeOfType(reference.node, ClassNode.class, ast));
    if (own != null && reference.base.equals(controllerName(own))) {
      return new Owner(own, documentPath);
    }
    Path file = controllerFile(reference.base);
    ClassNode type = file == null ? null : declared(file);
    return type == null ? null : new Owner(type, file.toString());
  }

  /**
   * The file of the controller a name refers to. Grails's default URL converter lowers the first letter of a name in a
   * URL mapping, so {@code 'Book'} names {@code BookController} as {@code 'book'} does.
   */
  private Path controllerFile(String name) {
    if (name.isEmpty()) {
      return null;
    }
    Map<String, Path> controllers = controllers();
    Path file = controllers.get(name);
    if (file != null) {
      return file;
    }
    String lowered = lowerFirst(name);
    for (Map.Entry<String, Path> entry : controllers.entrySet()) {
      if (lowerFirst(entry.getKey()).equals(lowered)) {
        return entry.getValue();
      }
    }
    return null;
  }

  /** The class a controller file declares under its own name. */
  private ClassNode declared(Path file) {
    String name = file.getFileName().toString().replaceFirst("\\.groovy$", "");
    for (ClassNode type : parse.apply(file.toString())) {
      if (type.getNameWithoutPackage().equals(name)) {
        return type;
      }
    }
    return null;
  }

  /** A controller's actions by name: methods that are public and not static, and properties assigned a closure. */
  static Map<String, AnnotatedNode> actions(ClassNode type) {
    Map<String, AnnotatedNode> actions = new LinkedHashMap<>();
    for (MethodNode method : type.getMethods()) {
      if (method.getLineNumber() > 0 && method.isPublic() && !method.isStatic() && !method.isSynthetic() && method.getName().indexOf('$') < 0) {
        actions.putIfAbsent(method.getName(), method);
      }
    }
    for (PropertyNode property : type.getProperties()) {
      if (property.getLineNumber() > 0 && !property.isStatic() && property.getInitialExpression() instanceof ClosureExpression) {
        actions.putIfAbsent(property.getName(), property);
      }
    }
    return actions;
  }

  /** Controller files of every module by logical name, the document's own module first. */
  private Map<String, Path> controllers() {
    Map<String, Path> controllers = new LinkedHashMap<>();
    for (Path app : grailsApps()) {
      for (Path file : files(app.resolve("controllers"))) {
        String name = file.getFileName().toString();
        if (name.endsWith(CONTROLLER + ".groovy") && name.length() > (CONTROLLER + ".groovy").length()) {
          controllers.putIfAbsent(logicalName(name.substring(0, name.length() - (CONTROLLER + ".groovy").length())), file);
        }
      }
    }
    return controllers;
  }

  private List<Path> grailsApps() {
    return lookup == null ? List.of() : lookup.grailsAppDirs().stream().map(Path::of).collect(Collectors.toList());
  }

  private static List<Path> files(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
    } catch (IOException | UncheckedIOException e) {
      return List.of();
    }
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
    /** The rendering page's text. */
    final String pageText;
    /** The {@code template} attribute's value, and which of the page's {@code g:render} tags with that value this is (from 0). */
    final String template;
    final int index;
    /** The {@code model} attribute's value, parsed as Groovy from this offset in the page. */
    final int modelStart;
    final String model;

    PageRender(String pageText, String template, int index, int modelStart, String model) {
      this.pageText = pageText;
      this.template = template;
      this.index = index;
      this.modelStart = modelStart;
      this.model = model;
    }

    /** The page's 0-based line and column of a 1-based position in the parsed model. */
    int[] position(int line, int column) {
      int offset = 0;
      for (int i = 1; i < line; i++) {
        offset = model.indexOf('\n', offset) + 1;
      }
      int at = modelStart + offset + column - 1;
      int lineStart = pageText.lastIndexOf('\n', at - 1) + 1;
      return new int[] { (int) pageText.substring(0, lineStart).chars().filter(c -> c == '\n').count(), at - lineStart };
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
    Kind kind = template ? Kind.TEMPLATE : Kind.VIEW;
    // only the controller of the page's directory renders it unnamed or by a relative name; another names it by its
    // absolute name ('/book/show', '/book/row' for _row.gsp), so files that do not hold that name are not parsed
    String absolute = "/" + String.join("/", segments);
    if (template) {
      absolute = absolute.substring(0, absolute.lastIndexOf('/') + 1) + segments[segments.length - 1].substring(1);
    }
    Pattern named = Pattern.compile("[\"']" + Pattern.quote(absolute) + "(\\.gsp)?[\"']");
    controllers().forEach((name, file) -> {
      if (segments.length == 1 || !name.equals(segments[0])) {
        String text = texts.apply(file.toString());
        if (text == null || !named.matcher(text).find()) {
          return;
        }
      }
      ClassNode type = declared(file);
      if (type == null) {
        return;
      }
      actions(type).forEach((action, node) -> {
        Statement code = node instanceof MethodNode ? ((MethodNode) node).getCode() : ((ClosureExpression) ((PropertyNode) node).getInitialExpression()).getCode();
        for (Object[] model : modelsOf(code, action)) {
          if (model[0] == kind && viewPaths(new Reference(kind, (String) model[1], null, name)).contains(relative)) {
            for (MapEntryExpression entry : ((MapExpression) model[2]).getMapEntryExpressions()) {
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
    for (Path app : grailsApps()) {
      Path views = app.resolve("views");
      for (Path file : files(views)) {
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
        for (GspScanner.Tag tag : GspScanner.scan(text)) {
          GspScanner.Attribute name = tag.closing || !"g".equals(tag.namespace) || !"render".equals(tag.name) ? null : tag.attribute("template");
          if (name == null || name.value == null) {
            continue;
          }
          int index = seen.merge(name.value, 1, Integer::sum) - 1;
          GspScanner.Attribute model = tag.attribute("model");
          if (model == null || model.valueStart < 0 || !viewPaths(new Reference(Kind.TEMPLATE, name.value, null, controller)).contains(relative)) {
            continue;
          }
          MapExpression map = modelMap(model.value);
          if (map != null) {
            PageRender render = new PageRender(text, name.value, index, model.valueStart, expressionOf(model.value));
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

  /** {@code {kind, view or template, map}} of each literal model in an action's code. */
  private static List<Object[]> modelsOf(Statement code, String action) {
    List<Object[]> models = new ArrayList<>();
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
          String view = argumentValue(call, "view");
          String template = "render".equals(method) ? argumentValue(call, "template") : null;
          Expression model = null;
          for (MapEntryExpression entry : entries(call)) {
            if (entry.getKeyExpression().getText().equals("model")) {
              model = entry.getValueExpression();
            }
          }
          if (view == null && "respond".equals(method)) {
            view = action;
          }
          if (view != null && !view.isEmpty() && model instanceof MapExpression) {
            models.add(new Object[] { Kind.VIEW, view, model });
          } else if (template != null && !template.isEmpty() && model instanceof MapExpression) {
            models.add(new Object[] { Kind.TEMPLATE, template, model });
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
          models.add(new Object[] { Kind.VIEW, action, statement.getExpression() });
        }
        super.visitReturnStatement(statement);
      }
    });
    // the action's last statement, returned without "return"
    List<Statement> statements = code instanceof BlockStatement ? ((BlockStatement) code).getStatements() : List.of(code);
    Statement last = statements.isEmpty() ? null : statements.get(statements.size() - 1);
    if (last instanceof ExpressionStatement && ((ExpressionStatement) last).getExpression() instanceof MapExpression) {
      models.add(new Object[] { Kind.VIEW, action, ((ExpressionStatement) last).getExpression() });
    }
    return models;
  }

  // completion

  /**
   * Values a reference may take that start with what is typed before the position, each replacing that typed text.
   * Null when the string spans lines.
   */
  List<Completion> candidates(Reference reference, ASTNodeVisitor ast, int line, int column) {
    Range string = NodeRanges.of(reference.node);
    if (string == null || string.startLine != string.endLine || string.startLine != line) {
      return null;
    }
    // the typed part of the string: the compile may be of a copy with a placeholder at the position
    Range replace = new Range(line, string.startColumn + 1, line, Math.max(string.startColumn + 1, column));
    String prefix = reference.value.substring(0, Math.max(0, Math.min(reference.value.length(), column - replace.startColumn)));
    return candidates(reference, ast, prefix, replace);
  }

  /** Values a reference may take that start with {@code prefix}, each replacing {@code replace}. */
  List<Completion> candidates(Reference reference, ASTNodeVisitor ast, String prefix, Range replace) {
    Map<String, Completion.Kind> labels = new LinkedHashMap<>();
    switch (reference.kind) {
      case CONTROLLER:
        controllers().keySet().forEach(name -> labels.put(name, Completion.Kind.CLASS));
        break;
      case ACTION:
        Owner owner = controller(reference, ast);
        if (owner != null) {
          actionsOf(owner).keySet().forEach(name -> labels.put(name, Completion.Kind.METHOD));
        }
        break;
      case ASSET:
        assetNames(reference).forEach(name -> labels.put(name, Completion.Kind.FILE));
        break;
      default:
        viewNames(reference).forEach(name -> labels.put(name, Completion.Kind.FILE));
    }
    List<Completion> items = new ArrayList<>();
    labels.forEach((label, kind) -> {
      if (label.startsWith(prefix)) {
        // names relative to the controller's views before absolute ones
        items.add(new Completion(label, kind, null, (label.startsWith("/") ? "1" : "0") + label, replace, List.of(), -1));
      }
    });
    return items;
  }

  /** The names a view, template or layout reference may take: relative to its base, then absolute. */
  private List<String> viewNames(Reference reference) {
    Set<String> relative = new java.util.TreeSet<>();
    Set<String> absolute = new java.util.TreeSet<>();
    for (Path app : grailsApps()) {
      Path views = app.resolve("views");
      for (Path file : files(views)) {
        String path = views.relativize(file).toString().replace('\\', '/');
        int slash = path.lastIndexOf('/');
        String dir = slash < 0 ? "" : path.substring(0, slash);
        String name = path.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot);
        String stem = dot < 0 ? name : name.substring(0, dot);
        if (reference.kind == Kind.LAYOUT) {
          if (extension.equals(".gsp") && (dir.equals("layouts") || dir.startsWith("layouts/"))) {
            relative.add(path.substring("layouts/".length(), path.length() - extension.length()));
          }
          continue;
        }
        boolean template = stem.startsWith("_");
        if (template != (reference.kind == Kind.TEMPLATE) || !(template ? extension.equals(".gsp") || extension.equals(".gson") : VIEW_EXTENSIONS.contains(extension))) {
          continue;
        }
        String shown = template ? stem.substring(1) : stem;
        absolute.add("/" + (dir.isEmpty() ? "" : dir + "/") + shown);
        if (reference.base != null && (dir.equals(reference.base) || dir.startsWith(reference.base + "/"))) {
          String below = dir.substring(reference.base.length());
          relative.add((below.isEmpty() ? "" : below.substring(1) + "/") + shown);
        }
      }
    }
    List<String> names = new ArrayList<>(relative);
    names.addAll(absolute);
    return names;
  }

  // hints

  /** A hint for each {@code render(view:)} and {@code render(template:)} whose file does not exist. */
  List<Diagnostic> problems(ModuleNode module) {
    List<Diagnostic> problems = new ArrayList<>();
    if (grailsApps().isEmpty()) {
      return problems;
    }
    Set<String> seen = new HashSet<>();
    for (ClassNode type : module.getClasses()) {
      String controller = controllerName(type);
      new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
          super.visitMethodCallExpression(call);
          if (!"render".equals(call.getMethodAsString()) || !call.isImplicitThis()) {
            return;
          }
          for (MapEntryExpression entry : entries(call)) {
            Expression value = entry.getValueExpression();
            if (!(value instanceof ConstantExpression) || !(((ConstantExpression) value).getValue() instanceof String)) {
              continue;
            }
            Reference reference = argument(call, entry.getKeyExpression().getText(), (String) ((ConstantExpression) value).getValue(), (ConstantExpression) value, controller, false);
            Range range = reference == null ? null : NodeRanges.of(value);
            if (range == null || !seen.add(range.startLine + ":" + range.startColumn)) {
              continue;
            }
            String missing = missing(reference);
            if (missing != null) {
              problems.add(new Diagnostic(documentPath, range.startLine, range.startColumn, range.endLine, range.endColumn, Diagnostic.HINT, missing));
            }
          }
        }
      }.visitClass(type);
    }
    return problems;
  }
}
