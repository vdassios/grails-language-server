package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * Convention references (M4): views, templates, layouts, controllers and actions named by strings in a controller and
 * in {@code UrlMappings}; definition, value completion, named-argument keys and hints for missing views. The same
 * checks run on each Grails fixture, whose controller has views {@code index}, {@code _row} and the layout {@code main}.
 */
@Timeout(900)
abstract class ConventionsTest {
  @TempDir
  Path tmp;

  static final class App {
    final String fixture;
    final String jdkVariable;
    final String packageName;
    /** Logical name of the fixture's controller, whose views exist. */
    final String controller;
    /** The controller's views other than {@code index}, by name, in name order. */
    final List<String> otherViews;
    /** Logical names of the fixture's other controllers. */
    final List<String> otherControllers;

    App(String fixture, String jdkVariable, String packageName, String controller) {
      this(fixture, jdkVariable, packageName, controller, List.of(), List.of());
    }

    App(String fixture, String jdkVariable, String packageName, String controller, List<String> otherViews, List<String> otherControllers) {
      this.fixture = fixture;
      this.jdkVariable = jdkVariable;
      this.packageName = packageName;
      this.controller = controller;
      this.otherViews = otherViews;
      this.otherControllers = otherControllers;
    }
  }

  abstract App app();

  private ProjectModel model;

  private Workspace workspace() throws Exception {
    model = Fixtures.built(app().fixture, app().jdkVariable);
    Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp));
    workspace.index();
    return workspace;
  }

  private String cap() {
    return Character.toUpperCase(app().controller.charAt(0)) + app().controller.substring(1);
  }

  private Path controllers() {
    return Path.of(model.module(":app").projectDir, "grails-app/controllers", app().packageName.replace('.', '/'));
  }

  /** The fixture's controller, on disk; compiled with other text. */
  private Path controller() {
    return controllers().resolve(cap() + "Controller.groovy");
  }

  /** {@code Ctrl} is the controller's class prefix, {@code ctrl} its logical name. */
  private String fill(String text) {
    return text.replace("PKG", app().packageName).replace("Ctrl", cap()).replace("ctrl", app().controller);
  }

  private String controllerWith(String... lines) {
    StringBuilder text = new StringBuilder(fill("package PKG\n\nclass CtrlController {\n"));
    for (String line : lines) {
      text.append("    ").append(fill(line)).append('\n');
    }
    return text.append("}\n").toString();
  }

  private static final String[] ACTIONS = {
    "static layout = 'main'",
    "static allowedMethods = [save: 'POST']",
    "def index() { render(view: 'index', model: [:]) }",
    "def all() { render(view: '/ctrl/index') }",
    "def row() { render(template: 'row') }",
    "def show(Long id) { [id: id] }",
    "def save() { redirect(action: 'show', id: 1) }",
  };

  private String mappings() {
    return fill("package PKG\n\nclass UrlMappings {\n    static mappings = {\n        group('/api') {\n            get \"/shelf\"(controller: 'ctrl', action: 'show')\n        }\n        \"/\"(view: '/ctrl/index')\n    }\n}\n");
  }

  private CompileResult compile(Workspace workspace, Path path, String text) throws Exception {
    CompileContext context = workspace.contextOf(path);
    assertNotNull(context, "no source set for " + path);
    return context.compile(new CompileRequest(path.toString(), text, workspace.lookup(context, p -> null), () -> false)).get(120, TimeUnit.SECONDS);
  }

  private static List<String> messages(CompileResult result, int severity) {
    return result.diagnostics.stream().filter(d -> d.severity == severity).map(d -> (d.startLine + 1) + ":" + (d.startColumn + 1) + " " + d.message).collect(Collectors.toList());
  }

  private static int[] at(String text, String needle, int offset) {
    int index = text.indexOf(needle);
    assertTrue(index >= 0 && text.indexOf(needle, index + 1) < 0, needle + " occurs once in\n" + text);
    int line = (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    return new int[] { line, index - (text.lastIndexOf('\n', index) + 1) + offset };
  }

  /** {@code <path below grails-app>:line} (1-based) of the definition one column into {@code needle}. */
  private String definition(Workspace workspace, Path path, String text, String needle) throws Exception {
    assertEquals(List.of(), messages(compile(workspace, path, text), Diagnostic.ERROR));
    int[] p = at(text, fill(needle), 1);
    Location location = workspace.contextOf(path).query(a -> a.definition(path.toString(), p[0], p[1])).get(60, TimeUnit.SECONDS);
    if (location == null) {
      return null;
    }
    String shown = location.path.replace('\\', '/');
    String below = shown.contains("/grails-app/") ? shown.substring(shown.indexOf("/grails-app/") + "/grails-app/".length()) : "../" + shown.substring(shown.indexOf("/src/") + 1);
    return below + ":" + (location.range.startLine + 1);
  }

  /** Completion items right after {@code needle}, in text the kept compile may not be of. */
  private List<Completion> completion(Workspace workspace, Path path, String compiled, String text, String needle) throws Exception {
    CompileContext context = workspace.contextOf(path);
    compile(workspace, path, compiled);
    int[] p = at(text, fill(needle), fill(needle).length());
    CompileRequest request = new CompileRequest(path.toString(), text, workspace.lookup(context, x -> null), () -> false);
    return context.query(a -> a.completion(request, p[0], p[1])).get(60, TimeUnit.SECONDS).items;
  }

  private List<String> labels(Workspace workspace, Path path, String text, String needle) throws Exception {
    return completion(workspace, path, text, text, needle).stream().map(c -> c.label).collect(Collectors.toList());
  }

  /** {@code controllers/.../File.groovy:line} of the first line of the fixture's controller file containing {@code needle}. */
  private String declared(String needle) throws Exception {
    List<String> lines = Files.readAllLines(controller());
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(needle)) {
        return "controllers/" + app().packageName.replace('.', '/') + "/" + cap() + "Controller.groovy:" + (i + 1);
      }
    }
    throw new AssertionError(needle);
  }

  @Test
  void stringsGoToViewsTemplatesLayoutsControllersAndActions() throws Exception {
    try (Workspace workspace = workspace()) {
      String text = controllerWith(ACTIONS);
      String own = "controllers/" + app().packageName.replace('.', '/') + "/" + cap() + "Controller.groovy:";
      Map<String, String> found = new TreeMap<>();
      found.put("relative view", definition(workspace, controller(), text, "'index'"));
      found.put("absolute view", definition(workspace, controller(), text, "'/ctrl/index'"));
      found.put("template", definition(workspace, controller(), text, "'row'"));
      found.put("layout", definition(workspace, controller(), text, "'main'"));
      found.put("own action", definition(workspace, controller(), text, "'show'"));
      found.put("allowedMethods key", definition(workspace, controller(), text, "save: 'POST'"));
      Path mappings = controllers().resolve("UrlMappings.groovy");
      found.put("mapped controller", definition(workspace, mappings, mappings(), "'ctrl'"));
      found.put("mapped action", definition(workspace, mappings, mappings(), "'show'"));
      found.put("mapped view", definition(workspace, mappings, mappings(), "'/ctrl/index'"));
      // Grails's URL converter lowers the first letter: 'Book' names BookController
      String capitalized = mappings().replace(fill("controller: 'ctrl'"), fill("controller: 'Ctrl'"));
      found.put("capitalized controller", definition(workspace, mappings, capitalized, "'" + cap() + "'"));
      found.put("its action", definition(workspace, mappings, capitalized, "'show'"));
      // an action the controller inherits from a class of the project's sources
      String extending = text.replace(cap() + "Controller {", cap() + "Controller extends Paging {").replace("action: 'show'", "action: 'page'");
      found.put("inherited action", definition(workspace, controller(), extending, "'page'"));
      String respond = text.replace("render(view: 'index', model: [:])", "respond([:], view: 'index')");
      found.put("respond view", definition(workspace, controller(), respond, "'index'"));
      String mappedViews = mappings().replace(fill("view: '/ctrl/index'"), fill("controller: 'ctrl', view: 'index'"));
      found.put("mapped view of a controller", definition(workspace, mappings, mappedViews, "'index'"));
      // REST mappings name the controller by resources: or resource:, and actions by HTTP method
      for (String key : List.of("resources", "resource")) {
        String rest = mappings().replace(fill("controller: 'ctrl', action: 'show'"), fill(key + ": 'ctrl'"));
        found.put(key + " controller", definition(workspace, mappings, rest, "'" + app().controller + "'"));
      }
      String byMethod = mappings().replace("action: 'show'", "action: [GET: 'show']");
      found.put("action by method", definition(workspace, mappings, byMethod, "'show'"));

      Map<String, String> expected = new TreeMap<>();
      String views = "views/" + app().controller + "/";
      expected.put("relative view", views + "index.gsp:1");
      expected.put("absolute view", views + "index.gsp:1");
      expected.put("template", views + "_row.gsp:1");
      expected.put("layout", "views/layouts/main.gsp:1");
      expected.put("own action", own + "9");
      expected.put("allowedMethods key", own + "10");
      expected.put("mapped controller", declared("class " + cap() + "Controller"));
      expected.put("mapped action", declared("def show("));
      expected.put("mapped view", views + "index.gsp:1");
      expected.put("capitalized controller", declared("class " + cap() + "Controller"));
      expected.put("its action", declared("def show("));
      expected.put("inherited action", "../src/main/groovy/" + app().packageName.replace('.', '/') + "/Paging.groovy:5");
      expected.put("respond view", views + "index.gsp:1");
      expected.put("mapped view of a controller", views + "index.gsp:1");
      expected.put("resources controller", declared("class " + cap() + "Controller"));
      expected.put("resource controller", declared("class " + cap() + "Controller"));
      expected.put("action by method", declared("def show("));
      assertEquals(expected, found);
    }
  }

  @Test
  void stringsCompleteFromTheFilesAndActions() throws Exception {
    try (Workspace workspace = workspace()) {
      String text = controllerWith(ACTIONS);
      Map<String, List<String>> found = new TreeMap<>();
      found.put("view", labels(workspace, controller(), text.replace("view: 'index'", "view: ''"), "index() { render(view: '"));
      found.put("template", labels(workspace, controller(), text.replace("template: 'row'", "template: 'r'"), "template: 'r"));
      found.put("layout", labels(workspace, controller(), text.replace("layout = 'main'", "layout = ''"), "layout = '"));
      found.put("own action", labels(workspace, controller(), text.replace("action: 'show'", "action: 'sh'"), "action: 'sh"));
      // a string not closed yet, typed after the last compile
      String unclosed = text.replace("render(view: 'index', model: [:])", "render(view: 'in");
      found.put("unclosed", completion(workspace, controller(), text, unclosed, "view: 'in").stream().map(c -> c.label).collect(Collectors.toList()));
      Path mappings = controllers().resolve("UrlMappings.groovy");
      found.put("mapped controller", labels(workspace, mappings, mappings().replace(fill("controller: 'ctrl'"), "controller: ''"), "controller: '"));
      found.put("mapped action", labels(workspace, mappings, mappings().replace("action: 'show'", "action: ''"), "action: '"));
      found.put("action by method", labels(workspace, mappings, mappings().replace("action: 'show'", "action: [GET: '']"), "GET: '"));

      Map<String, List<String>> expected = new TreeMap<>();
      // relative names, then absolute ones
      List<String> views = new ArrayList<>(List.of("index"));
      views.addAll(app().otherViews);
      views.add("/" + app().controller + "/index");
      app().otherViews.forEach(view -> views.add("/" + app().controller + "/" + view));
      views.add("/layouts/main");
      expected.put("view", views);
      expected.put("template", List.of("row"));
      expected.put("layout", List.of("main"));
      expected.put("own action", List.of("show"));
      expected.put("unclosed", List.of("index"));
      List<String> controllers = new ArrayList<>(app().otherControllers);
      controllers.add(app().controller);
      controllers.sort(null);
      expected.put("mapped controller", controllers);
      List<String> actions = Files.readAllLines(controller()).stream().map(String::trim).filter(l -> l.startsWith("def ") && l.contains("("))
          .map(l -> l.substring(4, l.indexOf('('))).collect(Collectors.toList());
      expected.put("mapped action", actions);
      expected.put("action by method", actions);
      assertEquals(expected, found);

      Completion view = completion(workspace, controller(), text, text.replace("view: 'index'", "view: 'in'"), "view: 'in").get(0);
      int[] p = at(text, "'index'", 1);
      assertEquals(p[0] + ":" + p[1] + "-" + (p[1] + 2), view.replace.startLine + ":" + view.replace.startColumn + "-" + view.replace.endColumn, "replaces the typed text");
      assertEquals(Completion.Kind.FILE, view.kind);
    }
  }

  @Test
  void namedArgumentKeysOfRenderAndRedirectComplete() throws Exception {
    try (Workspace workspace = workspace()) {
      String text = controllerWith(ACTIONS);
      List<String> render = labels(workspace, controller(), text.replace("render(template: 'row')", "render(te)"), "render(te");
      assertTrue(render.containsAll(List.of("template", "text")), render.toString());
      List<String> redirect = labels(workspace, controller(), text.replace("redirect(action: 'show', id: 1)", "redirect(action: 'show', ac)"), "'show', ac");
      assertTrue(!redirect.contains("action"), "a key given already is not offered again: " + redirect);
      List<String> redirectKeys = labels(workspace, controller(), text.replace("redirect(action: 'show', id: 1)", "redirect(action: 'show', co)"), "'show', co");
      assertTrue(redirectKeys.contains("controller"), redirectKeys.toString());
      List<String> params = labels(workspace, controller(), text.replace("[id: id]", "params.in"), "params.in");
      assertTrue(params.contains("int"), "params.int from the parameter map: " + params);
    }
  }

  @Test
  void aViewOrTemplateThatDoesNotExistIsAHint() throws Exception {
    try (Workspace workspace = workspace()) {
      String text = controllerWith(ACTIONS).replace("render(template: 'row')", "render(template: 'gone')").replace(fill("render(view: '/ctrl/index')"), "render(view: '/nowhere/index')");
      CompileResult result = compile(workspace, controller(), text);
      assertEquals(List.of(), messages(result, Diagnostic.ERROR));
      int[] gone = at(text, "'gone'", 0);
      int[] nowhere = at(text, "'/nowhere/index'", 0);
      assertEquals(List.of((nowhere[0] + 1) + ":" + (nowhere[1] + 1) + " No view '/nowhere/index': grails-app/views/nowhere/index.gsp does not exist",
          (gone[0] + 1) + ":" + (gone[1] + 1) + " No template 'gone': grails-app/views/" + app().controller + "/_gone.gsp does not exist"), messages(result, Diagnostic.HINT));
    }
  }

  /**
   * No hint where the file may come from somewhere the server does not model: a relative name in a namespaced or
   * abstract controller (whose views depend on the namespace or the subclass), a {@code plugin} or {@code contextPath}
   * argument, a view a plugin jar supplies. An absolute name in such a controller is still checked.
   */
  @Test
  void noHintWhereTheFileMayComeFromElsewhere() throws Exception {
    try (Workspace workspace = workspace()) {
      String relative = "def a() { render(view: 'gone') }";
      String absolute = "def b() { render(view: '/nowhere/index') }";
      String nowhere = "No view '/nowhere/index': grails-app/views/nowhere/index.gsp does not exist";
      Map<String, List<String>> found = new TreeMap<>();
      Map<String, List<String>> expected = new TreeMap<>();
      found.put("namespaced", hints(workspace, controllerWith("static namespace = 'admin'", relative, absolute), Set.of()));
      expected.put("namespaced", List.of(nowhere));
      found.put("abstract", hints(workspace, controllerWith(relative, absolute).replace("class " + cap(), "abstract class " + cap()), Set.of()));
      expected.put("abstract", List.of(nowhere));
      found.put("plugin and contextPath",
          hints(workspace, controllerWith("def a() { render(template: 'gone', plugin: 'x') }", "def b() { render(view: '/nowhere/index', contextPath: '/p') }"), Set.of()));
      expected.put("plugin and contextPath", List.of());
      String plugged = controllerWith("def a() { render(view: '/login/auth') }", "def b() { render(template: 'part') }", "def c() { render(view: '/login/gone') }");
      found.put("plugin's views", hints(workspace, plugged, Set.of("views/login/auth.gsp", fill("views/ctrl/_part.gsp"))));
      expected.put("plugin's views", List.of("No view '/login/gone': grails-app/views/login/gone.gsp does not exist"));
      assertEquals(expected, found);
    }
  }

  /** The hints of the controller with {@code text}, compiled as if the class path's jars supplied {@code pluginFiles}. */
  private List<String> hints(Workspace workspace, String text, Set<String> pluginFiles) throws Exception {
    CompileContext context = workspace.contextOf(controller());
    CompileRequest request = new CompileRequest(controller().toString(), text, new WithPluginFiles(workspace.lookup(context, p -> null), pluginFiles), () -> false);
    CompileResult result = context.compile(request).get(120, TimeUnit.SECONDS);
    assertEquals(List.of(), messages(result, Diagnostic.ERROR));
    return result.diagnostics.stream().filter(d -> d.severity == Diagnostic.HINT).map(d -> d.message).collect(Collectors.toList());
  }
}
