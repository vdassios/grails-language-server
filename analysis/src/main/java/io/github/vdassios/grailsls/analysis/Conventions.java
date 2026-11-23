package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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
import org.codehaus.groovy.ast.expr.MethodCall;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.TupleExpression;
import org.codehaus.groovy.ast.expr.VariableExpression;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * Strings Grails resolves by convention (plan B3's table, B4 2.5): views, templates and layouts under
 * {@code grails-app/views}, controllers by logical name and their actions. Definition, value completion and hints for
 * views and templates that do not exist. Files are looked up in every module's {@code grails-app}, the document's own
 * module first ({@link SourceLookup#grailsAppDirs}).
 */
public final class Conventions {
  enum Kind {
    VIEW, TEMPLATE, LAYOUT, CONTROLLER, ACTION,
    /** An {@code asset:} tag's file: only pages name one ({@link GspConventions}). */
    ASSET
  }

  static final List<String> RENDER_KEYS = List.of("view", "template", "model", "text", "status", "contentType", "encoding", "layout", "plugin", "collection", "bean", "var");
  static final List<String> REDIRECT_KEYS = List.of("controller", "action", "id", "params", "uri", "url", "fragment", "permanent", "mapping", "namespace");
  private static final Set<String> LINK_CALLS = Set.of("redirect", "chain", "forward", "createLink", "link");
  private static final List<String> VIEW_EXTENSIONS = List.of(".gsp", ".jsp", ".gson");
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

  Conventions(SourceLookup lookup, String documentPath, Function<String, List<ClassNode>> parse) {
    this.lookup = lookup;
    this.documentPath = documentPath;
    this.parse = parse;
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
      if (call != null) {
        return argument(call, entry.getKeyExpression().getText(), value, string, controller, urlMappings(owner));
      }
      // "/book"(controller: 'book', action: [GET: 'show', POST: 'save']): actions by HTTP method
      ASTNode map = ast.getParent(entry);
      ASTNode outer = map == null ? null : ast.getParent(map);
      if (outer instanceof MapEntryExpression && ((MapEntryExpression) outer).getValueExpression() == map
          && "action".equals(((MapEntryExpression) outer).getKeyExpression().getText()) && urlMappings(owner)) {
        MethodCallExpression mapped = callOf((MapEntryExpression) outer, ast);
        return mapped == null ? null : argument(mapped, "action", value, string, controller, true);
      }
      return null;
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
        case "resources":
        case "resource":
          // "/books"(resources: 'book'): a REST controller
          return urlMappings ? new Reference(Kind.CONTROLLER, value, node, null) : null;
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
    MethodCall call = map instanceof MapExpression ? GroovyASTUtils.callWithArguments(ast.getParent(map), ast) : null;
    return call instanceof MethodCallExpression ? (MethodCallExpression) call : null;
  }

  /** A named argument's string value; empty when the argument is not a string, null when there is none. */
  static String argumentValue(MethodCallExpression call, String key) {
    for (MapEntryExpression entry : entries(call)) {
      if (entry.getKeyExpression().getText().equals(key)) {
        Expression value = entry.getValueExpression();
        return value instanceof ConstantExpression && ((ConstantExpression) value).getValue() instanceof String ? (String) ((ConstantExpression) value).getValue() : "";
      }
    }
    return null;
  }

  static List<MapEntryExpression> entries(MethodCallExpression call) {
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
    MethodCall call = GroovyASTUtils.callWithArguments(ast.getParent(typed), ast);
    if (!(call instanceof MethodCallExpression) || !((MethodCallExpression) call).isImplicitThis()) {
      return List.of();
    }
    String method = ((MethodCallExpression) call).getMethodAsString();
    List<String> keys = "render".equals(method) ? RENDER_KEYS : "redirect".equals(method) ? REDIRECT_KEYS : List.of();
    return Gorm.unusedKeys(keys, (TupleExpression) ((MethodCallExpression) call).getArguments());
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

  /**
   * Whether the views of a controller's relative names depend on more than its name: it declares {@code static
   * namespace} (Grails looks below {@code views/<namespace>/} first) or is abstract (its subclasses render them).
   */
  private static boolean viewsUnknown(ClassNode type) {
    ClassNode top = topLevel(type);
    FieldNode namespace = top.getField("namespace");
    return Modifier.isAbstract(top.getModifiers()) || namespace != null && namespace.isStatic();
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
        Path view = viewFile(reference);
        return view == null ? null : new Location(view.toString(), new Range(0, 0, 0, 0));
    }
  }

  /**
   * Why a view, template or layout reference resolves to no file, or null when it does or cannot be checked (a
   * relative name with no base, no {@code grails-app} known).
   */
  String missing(Reference reference) {
    if (reference.value.isEmpty() || grailsApps().isEmpty()) {
      return null;
    }
    List<String> paths = viewPaths(reference);
    if (paths.isEmpty() || viewFile(reference) != null || fromPlugin("", paths)) {
      return null;
    }
    String what = reference.kind == Kind.TEMPLATE ? "template" : reference.kind == Kind.LAYOUT ? "layout" : "view";
    return "No " + what + " '" + reference.value + "': grails-app/" + paths.get(0) + " does not exist";
  }

  /** Whether a jar of the class path supplies one of the paths, below {@code grails-app} after {@code prefix}. */
  boolean fromPlugin(String prefix, List<String> paths) {
    Set<String> files = lookup.pluginFiles();
    return !files.isEmpty() && paths.stream().anyMatch(path -> files.contains(prefix + path));
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
  static List<String> viewPaths(Reference reference) {
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
  ClassNode declared(Path file) {
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
  Map<String, Path> controllers() {
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

  List<Path> grailsApps() {
    return lookup == null ? List.of() : lookup.grailsAppDirs().stream().map(Path::of).collect(Collectors.toList());
  }

  static List<Path> files(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      return walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
    } catch (IOException | UncheckedIOException e) {
      return List.of();
    }
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
      default:
        viewNames(reference).forEach(name -> labels.put(name, Completion.Kind.FILE));
    }
    return items(labels, prefix, replace);
  }

  /** Completion items of the labels that start with {@code prefix}, each replacing {@code replace}. */
  static List<Completion> items(Map<String, Completion.Kind> labels, String prefix, Range replace) {
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

  /**
   * A hint for each {@code render(view:)} and {@code render(template:)} whose file does not exist. None where the file
   * may come from somewhere not modelled: a call with a {@code plugin} or {@code contextPath} argument, a relative
   * name in a controller whose views depend on more than its name ({@link #viewsUnknown}), a plugin's file.
   */
  List<Diagnostic> problems(ModuleNode module) {
    List<Diagnostic> problems = new ArrayList<>();
    if (grailsApps().isEmpty()) {
      return problems;
    }
    Set<String> seen = new HashSet<>();
    for (ClassNode type : module.getClasses()) {
      String controller = controllerName(type);
      boolean relativeUnknown = viewsUnknown(type);
      new ClassCodeVisitorSupport() {
        @Override
        protected SourceUnit getSourceUnit() {
          return null;
        }

        @Override
        public void visitMethodCallExpression(MethodCallExpression call) {
          super.visitMethodCallExpression(call);
          if (!"render".equals(call.getMethodAsString()) || !call.isImplicitThis() || argumentValue(call, "plugin") != null || argumentValue(call, "contextPath") != null) {
            return;
          }
          for (MapEntryExpression entry : entries(call)) {
            Expression value = entry.getValueExpression();
            if (!(value instanceof ConstantExpression) || !(((ConstantExpression) value).getValue() instanceof String)) {
              continue;
            }
            Reference reference = argument(call, entry.getKeyExpression().getText(), (String) ((ConstantExpression) value).getValue(), (ConstantExpression) value, controller, false);
            Range range = reference == null || relativeUnknown && !reference.value.startsWith("/") ? null : NodeRanges.of(value);
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
