package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.CompletionDetail;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * GSP tags (M5a): completion of tag and attribute names, hover, definition and hints, on each Grails fixture's views.
 * Tags come from the GSP compiler ({@code g:each}), Grails's taglib classes in the jars ({@code g:link}) and the
 * fixture's taglib, whose one tag documents a required attribute {@code wanted} and an optional {@code max}.
 */
@Timeout(900)
abstract class GspTest {
  @TempDir
  Path tmp;

  static final class App {
    final String fixture;
    final String jdkVariable;
    /** The fixture's controller, whose views exist; its taglib's namespace, tag and file below {@code grails-app}. */
    final String controller;
    final String namespace;
    final String tag;
    final String taglib;

    App(String fixture, String jdkVariable, String controller, String namespace, String tag, String taglib) {
      this.fixture = fixture;
      this.jdkVariable = jdkVariable;
      this.controller = controller;
      this.namespace = namespace;
      this.tag = tag;
      this.taglib = taglib;
    }
  }

  abstract App app();

  private Workspace workspace;
  private Path views;
  /** Unsaved texts by path, as the editor holds them. */
  private Map<Path, String> open = Map.of();

  @BeforeEach
  void setUp() throws Exception {
    ProjectModel model = Fixtures.built(app().fixture, app().jdkVariable);
    workspace = new Workspace(model, AnalysisJar.extract(tmp));
    workspace.index();
    views = Path.of(model.module(":app").projectDir, "grails-app/views");
  }

  /** {@code ns:}, {@code NS.} and {@code TAG} stand for the fixture taglib's namespace and tag. */
  private String fill(String text) {
    return text.replace("ns:", app().namespace + ":").replace("NS.", app().namespace + ".").replace("TAG", app().tag);
  }

  private Path page() {
    return views.resolve(app().controller).resolve("index.gsp");
  }

  private CompileResult compile(Path path, String text) throws Exception {
    CompileContext context = workspace.contextOf(path);
    assertNotNull(context, "no context for " + path);
    return context.compile(new CompileRequest(path.toString(), text, workspace.lookup(context, open::get), () -> false)).get(120, TimeUnit.SECONDS);
  }

  private static List<String> hints(CompileResult result) {
    return result.diagnostics.stream().map(d -> (d.startLine + 1) + ":" + (d.startColumn + 1) + " " + d.message).collect(Collectors.toList());
  }

  /** Line and column of {@code needle} in {@code text}, plus {@code offset}. */
  private static int[] at(String text, String needle, int offset) {
    int index = text.indexOf(needle);
    assertTrue(index >= 0 && text.indexOf(needle, index + 1) < 0, needle + " occurs once in\n" + text);
    int line = (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    return new int[] { line, index - (text.lastIndexOf('\n', index) + 1) + offset };
  }

  private List<Completion> completion(String text, String needle) throws Exception {
    return completion(page(), text, needle);
  }

  /** Completion right after {@code needle} in {@code text}, the unsaved text of the file at {@code path}. */
  private List<Completion> completion(Path path, String text, String needle) throws Exception {
    String filled = fill(text);
    int[] p = at(filled, fill(needle), fill(needle).length());
    CompileContext context = workspace.contextOf(path);
    CompileRequest request = new CompileRequest(path.toString(), filled, workspace.lookup(context, open::get), () -> false);
    return context.query(a -> a.completion(request, p[0], p[1])).get(60, TimeUnit.SECONDS).items;
  }

  private static List<String> labels(List<Completion> items) {
    return items.stream().map(item -> item.label).collect(Collectors.toList());
  }

  private <T> T query(String text, String needle, int offset, QueryAt<T> query) throws Exception {
    return query(page(), text, needle, offset, query);
  }

  /** A query at {@code needle} plus {@code offset}, after compiling {@code text} as the file at {@code path}. */
  private <T> T query(Path path, String text, String needle, int offset, QueryAt<T> query) throws Exception {
    String filled = fill(text);
    compile(path, filled);
    int[] p = at(filled, fill(needle), offset);
    return workspace.contextOf(path).query(a -> query.apply(a, path.toString(), p[0], p[1])).get(60, TimeUnit.SECONDS);
  }

  private interface QueryAt<T> {
    T apply(io.github.vdassios.grailsls.api.Analyzer analyzer, String path, int line, int column);
  }

  @Test
  void aPageBelongsToItsModulesArtefactContext() {
    CompileContext context = workspace.contextOf(page());
    assertNotNull(context);
    assertTrue(context.name.endsWith("main"), context.name);
    assertNull(workspace.contextOf(tmp.resolve("loose.gsp")));
  }

  @Test
  void theFixtureViewsHaveNoHints() throws Exception {
    Map<String, List<String>> found = new TreeMap<>();
    try (var files = Files.walk(views)) {
      for (Path view : files.filter(f -> f.toString().endsWith(".gsp")).collect(Collectors.toList())) {
        found.put(views.relativize(view).toString(), hints(compile(view, Files.readString(view))));
      }
    }
    assertEquals(3, found.size(), found.toString());
    found.values().forEach(list -> assertEquals(List.of(), list, found.toString()));
  }

  @Test
  void tagNamesCompleteFromTheCompilerTheJarsAndTheProject() throws Exception {
    List<String> g = labels(completion("<div><g:\n</div>", "<g:"));
    assertTrue(g.containsAll(List.of("each", "if", "else", "link", "render", "formatDate", "message", "layoutBody")), g.toString());
    List<String> partial = labels(completion("<g:ea", "<g:e"));
    assertTrue(partial.contains("each"), partial.toString());
    assertEquals(List.of(app().tag), labels(completion("<ns:", "<ns:")));
    List<String> namespaces = labels(completion("<g", "<"));
    assertEquals(List.of(), namespaces, "no colon yet: not a GSP tag");
    List<String> named = labels(completion("<x:", "<"));
    assertTrue(named.containsAll(List.of("g", app().namespace)), named.toString());
  }

  @Test
  void attributesCompleteRequiredFirstAndNotTwice() throws Exception {
    List<Completion> items = completion("<ns:TAG  max=\"1\" />", "<ns:TAG ");
    assertEquals(List.of("wanted"), labels(items));
    assertEquals("required", items.get(0).detail);
    List<Completion> each = completion("<g:each var=\"b\" >", "<g:each var=\"b\" ");
    assertEquals(List.of("in", "status"), labels(each));
    assertTrue(each.get(0).sortText.compareTo(each.get(1).sortText) < 0);
    CompletionDetail detail = workspace.contextOf(page()).query(a -> a.resolveCompletion(page().toString(), 0)).get(60, TimeUnit.SECONDS);
    assertTrue(detail.documentation.contains("`in` (required)"), detail.documentation);
    assertEquals(List.of(), labels(completion("<g:each in=\"${x}\">", "<g:each in=\"$")), "inside a value");
  }

  @Test
  void hoverShowsTheDocCommentAndAttributes() throws Exception {
    String page = "<ns:TAG wanted=\"a\"/>\n<g:each in=\"${[1]}\">x</g:each>\n";
    String tag = query(page, "ns:TAG", 5, (a, path, line, column) -> a.hover(path, line, column));
    assertNotNull(tag);
    assertTrue(tag.contains("whose name starts with a prefix"), tag);
    assertTrue(tag.contains("`wanted` (required): the prefix"), tag);
    assertTrue(tag.contains("`max`: how many at most"), tag);
    String attribute = query(page, "wanted", 2, (a, path, line, column) -> a.hover(path, line, column));
    assertTrue(attribute.endsWith("`wanted` (required): the prefix"), attribute);
    String each = query(page, "g:each in", 3, (a, path, line, column) -> a.hover(path, line, column));
    assertTrue(each.contains("for each element"), each);
    assertNull(query(page, "x</g", 0, (a, path, line, column) -> a.hover(path, line, column)));
  }

  @Test
  void definitionReachesTheProjectTaglib() throws Exception {
    String page = "<ns:TAG wanted=\"a\"/>\n";
    Location location = query(page, "ns:TAG", 5, (a, path, line, column) -> a.definition(path, line, column));
    assertNotNull(location);
    assertTrue(location.path.replace('\\', '/').endsWith("grails-app/" + app().taglib), location.path);
    String taglib = Files.readString(Path.of(location.path));
    int[] declared = at(taglib, "def " + app().tag + " =", 4);
    assertEquals(declared[0], location.range.startLine);
    assertEquals(declared[1], location.range.startColumn);
    assertNull(query(page, "wanted", 1, (a, path, line, column) -> a.definition(path, line, column)));
  }

  /** Where a definition from {@code needle} (plus {@code offset}) in a page leads: the path below {@code grails-app} and the line. */
  private String definitionOf(String page, String needle, int offset) throws Exception {
    Location location = query(page, needle, offset, (a, path, line, column) -> a.definition(path, line, column));
    if (location == null) {
      return null;
    }
    String path = location.path.replace('\\', '/');
    return path.substring(path.indexOf("grails-app/") + "grails-app/".length()) + ":" + location.range.startLine;
  }

  private String controllerFile() {
    return Character.toUpperCase(app().controller.charAt(0)) + app().controller.substring(1) + "Controller.groovy";
  }

  @Test
  void conventionReferencesLeadToTheirFiles() throws Exception {
    String c = app().controller;
    String page = "<meta name=\"layout\" content=\"main\"/>\n<g:render template=\"row\"/>\n<g:render template=\"/" + c + "/row\"/>\n"
        + "<g:include view=\"index\"/>\n<g:applyLayout name=\"main\">x</g:applyLayout>\n<asset:stylesheet src=\"main\"/>\n<asset:javascript src=\"/app.js\"/>\n"
        + "<g:link controller=\"" + c + "\" action=\"show\">a</g:link>\n<g:form action=\"index\"/>\n<other:link action=\"index\"/>\n<g:render template=\"${t}\"/>\n";
    assertEquals("views/layouts/main.gsp:0", definitionOf(page, "content=\"main", 10));
    assertEquals("views/" + c + "/_row.gsp:0", definitionOf(page, "template=\"row", 11));
    assertEquals("views/" + c + "/_row.gsp:0", definitionOf(page, "template=\"/", 11));
    assertEquals("views/" + c + "/index.gsp:0", definitionOf(page, "view=\"index", 7));
    assertEquals("views/layouts/main.gsp:0", definitionOf(page, "name=\"main", 7));
    assertEquals("assets/stylesheets/main.css:0", definitionOf(page, "src=\"main", 6));
    assertEquals("assets/javascripts/app.js:0", definitionOf(page, "src=\"/app", 6));
    String controller = definitionOf(page, "controller=\"" + c, 13);
    assertNotNull(controller);
    assertTrue(controller.startsWith("controllers/") && controller.contains(controllerFile() + ":"), controller);
    String show = definitionOf(page, "action=\"show", 9);
    String index = definitionOf(page, "g:form action=\"index", 16);
    assertNotNull(show);
    assertNotNull(index);
    String source = Files.readString(views.getParent().resolve(show.substring(0, show.lastIndexOf(':'))));
    assertEquals(at(source, "def show(", 0)[0] + "", show.substring(show.lastIndexOf(':') + 1));
    assertEquals(at(source, "def index(", 0)[0] + "", index.substring(index.lastIndexOf(':') + 1), "an action with no controller is the view's controller's");
    assertNull(definitionOf(page, "other:link action=\"index", 20), "not a g: tag");
    assertNull(definitionOf(page, "template=\"${", 12), "a name no code declares, in an expression");
  }

  @Test
  void conventionValuesComplete() throws Exception {
    String c = app().controller;
    assertEquals(List.of("row", "/" + c + "/row"), labels(completion("<g:render template=\"\"/>", "template=\"")));
    assertEquals(List.of("/" + c + "/row"), labels(completion("<g:render template=\"/\"/>", "template=\"/")));
    assertEquals(List.of("main"), labels(completion("<meta name=\"layout\" content=\"\">", "content=\"")));
    assertEquals(List.of("main.css"), labels(completion("<asset:stylesheet src=\"\"/>", "src=\"")));
    assertEquals(List.of("app.js", "main.css"), labels(completion("<asset:image src=\"\"/>", "src=\"")));
    assertEquals(List.of(c), labels(completion("<g:link controller=\"\">", "controller=\"")));
    List<String> actions = labels(completion("<g:link controller=\"" + c + "\" action=\"\">", "action=\""));
    assertTrue(actions.containsAll(List.of("index", "show")), actions.toString());
    assertEquals(actions, labels(completion("<g:form action=\"\">", "action=\"")), "the view's controller");
    assertEquals(List.of(), labels(completion("<g:render template=\"r\" model=\"\"/>", "model=\"")));
    Completion row = completion("<g:render template=\"ro\"/>", "template=\"ro").get(0);
    assertEquals("row", row.label);
    assertEquals(row.replace.startColumn + 2, row.replace.endColumn, "replaces what is typed");
  }

  @Test
  void hintsForMissingConventionFiles() throws Exception {
    String page = "<meta name=\"layout\" content=\"nope\"/>\n<g:render template=\"nope\"/>\n<g:include view=\"nope\"/>\n<asset:stylesheet src=\"nope.css\"/>\n"
        + "<g:applyLayout name=\"nope\">x</g:applyLayout>\n<g:render template=\"${t}\"/>\n<asset:javascript src=\"https://example.org/x.js\"/>\n"
        + "<g:link controller=\"nope\" action=\"nope\">x</g:link>\n<meta name=\"description\" content=\"nope\"/>\n";
    String c = app().controller;
    assertEquals(List.of(
        "1:30 No layout 'nope': grails-app/views/layouts/nope.gsp does not exist",
        "2:21 No template 'nope': grails-app/views/" + c + "/_nope.gsp does not exist",
        "3:18 No view 'nope': grails-app/views/" + c + "/nope.gsp does not exist",
        "4:24 No asset 'nope.css' under any grails-app/assets/*/",
        "5:22 No layout 'nope': grails-app/views/layouts/nope.gsp does not exist"), hints(compile(page(), page)));
  }

  @Test
  void relativeNamesStartFromTheViewsController() throws Exception {
    String c = app().controller;
    Path nested = views.resolve(c).resolve("part/nested.gsp");
    String page = "<g:render template=\"row\"/>\n<g:include view=\"/" + c + "/index.gsp\"/>\n<g:render template=\"part/nope\"/>\n";
    assertEquals(List.of("3:21 No template 'part/nope': grails-app/views/" + c + "/part/_nope.gsp does not exist"), hints(compile(nested, page)));
  }

  @Test
  void hintsForUnknownTagsAndMissingRequiredAttributes() throws Exception {
    String page = fill("<ns:nope/>\n<ns:TAG max=\"2\"/>\n<g:each var=\"b\">x</g:each>\n<svg:rect/>\n<ns:TAG wanted=\"${a}\"/>\n</ns:nope>\n");
    assertEquals(List.of(
        "1:2 Unknown tag <" + app().namespace + ":nope>: no taglib of namespace '" + app().namespace + "' declares it",
        "2:2 <" + app().namespace + ":" + app().tag + "> needs the attribute 'wanted'",
        "3:2 <g:each> needs the attribute 'in'"), hints(compile(page(), page)));
  }

  /** The fixture's domain class named like its controller, in the package named like its taglib's namespace. */
  private String domainType() {
    return Character.toUpperCase(app().controller.charAt(0)) + app().controller.substring(1);
  }

  private static List<String> severities(CompileResult result) {
    return result.diagnostics.stream().map(d -> (d.startLine + 1) + ":" + (d.startColumn + 1) + " " + d.severity).collect(Collectors.toList());
  }

  @Test
  void expressionsCompileAsGrailsTranslatesThem() throws Exception {
    String type = domainType();
    String page = "<%@ page import=\"fixture." + app().namespace + "." + type + "\" %>\n<ul>\n<g:each in=\"${" + type + ".list()}\" var=\"x\">\n"
        + "  <li title=\"${x}\">${x} <% out << " + type + ".count() %></li>\n</g:each>\n</ul>\n";
    assertEquals(List.of(), hints(compile(page(), page)));
    String domain = "domain/fixture/" + app().namespace + "/" + type + ".groovy";
    String source = Files.readString(views.getParent().resolve(domain));
    String classLine = at(source, "class " + type, 0)[0] + "";
    assertEquals(domain + ":" + classLine, definitionOf(page, "${" + type, 2), "a class in an attribute's expression");
    assertEquals(domain + ":" + classLine, definitionOf(page, "<< " + type, 4), "a class in a scriptlet");
    String hover = query(page, ".list()", 2, (a, path, line, column) -> a.hover(path, line, column));
    assertNotNull(hover);
    assertTrue(hover.contains("list"), hover);
    assertNull(query(page, "<ul>", 2, (a, path, line, column) -> a.hover(path, line, column)), "HTML is no code");
  }

  @Test
  void expressionErrorsLandOnTheirPageColumns() throws Exception {
    List<CompileResult> results = List.of(
        compile(page(), "<p>\n  <span>${new NoSuchType()}</span>\n</p>\n"),
        compile(page(), "<p>\n<g:if test=\"${a &&\n  new NoSuchType()}\">x</g:if>\n</p>\n"));
    assertEquals(List.of("2:11 " + io.github.vdassios.grailsls.api.Diagnostic.ERROR), severities(results.get(0)));
    assertTrue(results.get(0).diagnostics.get(0).message.contains("NoSuchType"), results.get(0).diagnostics.toString());
    assertEquals(List.of("3:3 " + io.github.vdassios.grailsls.api.Diagnostic.ERROR), severities(results.get(1)), "a second line of the expression");
    CompileResult syntax = compile(page(), "<p>${a.}</p>\n");
    assertEquals(1, syntax.diagnostics.size(), syntax.diagnostics.toString());
    // Groovy 4 reports it at the generated call's "(", outside the page's code: the page line is all that maps
    assertEquals(0, syntax.diagnostics.get(0).startLine, syntax.diagnostics.toString());
  }

  @Test
  void aPageTheGspCompilerRejectsHasOneError() throws Exception {
    CompileResult result = compile(page(), "<p>\n<g:if test=\"${true}\">\n<p>x</p>\n");
    assertEquals(1, result.diagnostics.size(), result.diagnostics.toString());
    assertEquals(io.github.vdassios.grailsls.api.Diagnostic.ERROR, result.diagnostics.get(0).severity);
    assertTrue(result.diagnostics.get(0).message.contains("if"), result.diagnostics.toString());
  }

  @Test
  void codeCompletesInsideExpressionsAndScriptlets() throws Exception {
    String type = domainType();
    String imports = "<%@ page import=\"fixture." + app().namespace + "." + type + "\" %>\n";
    List<String> statics = labels(completion(imports + "<p>${" + type + ".li}</p>\n", "${" + type + ".li"));
    assertTrue(statics.contains("list"), statics.toString());
    String declared = imports + "<% def b = new " + type + "() %>\n";
    List<String> dot = labels(completion(declared + "<p>${b.}</p>\n", "${b."));
    assertTrue(dot.containsAll(List.of("save", "validate")), "after a dot: " + dot);
    List<String> open = labels(completion(declared + "<p>${b.\n</p>\n", "${b."));
    assertTrue(open.containsAll(List.of("save", "validate")), "an expression not closed yet: " + open);
    List<String> attribute = labels(completion(declared + "<g:if test=\"${b.va}\">x</g:if>\n", "${b.va"));
    assertTrue(attribute.contains("validate"), "in an attribute's expression: " + attribute);
    List<String> scriptlet = labels(completion(declared + "<% b.sa %>\n", "<% b.sa"));
    assertTrue(scriptlet.contains("save"), "in a scriptlet: " + scriptlet);
    Completion save = completion(declared + "<p>${b.sa}</p>\n", "${b.sa").stream().filter(c -> c.label.equals("save")).findFirst().orElseThrow();
    assertTrue(save.additionalEdits.isEmpty());
    CompletionDetail detail = workspace.contextOf(page()).query(a -> a.resolveCompletion(page().toString(), save.id)).get(60, TimeUnit.SECONDS);
    assertNotNull(detail);
    assertTrue(detail.detail.contains("save"), detail.detail);
    assertEquals(List.of("in", "status"), labels(completion("<g:each var=\"b\" >", "<g:each var=\"b\" ")), "tag attributes as before");
  }

  @Test
  void signatureHelpInsidePageCode() throws Exception {
    String type = domainType();
    String page = "<%@ page import=\"fixture." + app().namespace + "." + type + "\" %>\n<p>${" + type + ".get(}</p>\n";
    int[] p = at(page, ".get(", 5);
    CompileContext context = workspace.contextOf(page());
    CompileRequest request = new CompileRequest(page().toString(), page, workspace.lookup(context, x -> null), () -> false);
    io.github.vdassios.grailsls.api.SignatureHelp help = context.query(a -> a.signatureHelp(request, p[0], p[1])).get(60, TimeUnit.SECONDS);
    assertNotNull(help);
    assertTrue(help.signatures.stream().anyMatch(s -> s.label.contains("get(")), help.signatures.toString());
  }

  @Test
  void implicitObjectsAreTypedAsGrailsBindsThem() throws Exception {
    String page = "<p>${params.int('x')} ${request.contextPath} ${session.id} ${flash.now} ${application.contextPath} ${webRequest.params}</p>\n"
        + "<% out << pageScope.variables; out << grailsApplication.config; out << controllerName.size() %>\n";
    assertEquals(List.of(), hints(compile(page(), page)));
    Map<String, String> types = new TreeMap<>(Map.of("params", "GrailsParameterMap", "request", "HttpServletRequest", "session", "HttpSession", "flash", "FlashScope",
        "application", "ServletContext", "webRequest", "GrailsWebRequest", "pageScope", "GroovyPageBinding", "grailsApplication", "GrailsApplication", "controllerName", "String"));
    for (Map.Entry<String, String> entry : types.entrySet()) {
      String hover = query(page, entry.getKey() + ".", 1, (a, path, line, column) -> a.hover(path, line, column));
      assertNotNull(hover, entry.getKey());
      assertTrue(hover.contains(entry.getValue()), entry.getKey() + ": " + hover);
    }
    assertNull(definitionOf(page, "params.", 1), "declared by the translation, not on the page");
    List<String> members = labels(completion("<p>${params.}</p>\n", "${params."));
    assertTrue(members.containsAll(List.of("int", "long", "list")), members.toString());
    List<String> names = labels(completion("<p>${para}</p>\n", "${para"));
    assertTrue(names.contains("params"), names.toString());
  }

  @Test
  void tagsCalledAsMethodsInPageCode() throws Exception {
    String page = fill("<p>${g.createLink(action: 'index')} ${NS.TAG(wanted: 'a')} ${message(code: 'x')}</p>\n");
    String link = query(page, "createLink", 2, (a, path, line, column) -> a.hover(path, line, column));
    assertNotNull(link);
    assertTrue(link.contains("<g:createLink>"), link);
    String bare = query(page, "message(", 2, (a, path, line, column) -> a.hover(path, line, column));
    assertNotNull(bare);
    assertTrue(bare.contains("<g:message>"), "a call with no receiver is a g: tag: " + bare);
    String own = query(page, fill("NS.TAG"), app().namespace.length() + 2, (a, path, line, column) -> a.hover(path, line, column));
    assertTrue(own != null && own.contains("`wanted` (required)"), own);
    Location location = query(page, fill("NS.TAG"), app().namespace.length() + 2, (a, path, line, column) -> a.definition(path, line, column));
    assertNotNull(location);
    assertTrue(location.path.replace('\\', '/').endsWith("grails-app/" + app().taglib), location.path);
    List<String> g = labels(completion("<p>${g.}</p>\n", "${g."));
    assertTrue(g.containsAll(List.of("createLink", "link", "message", "formatDate")), g.toString());
    assertTrue(!g.contains("each") && !g.contains("if"), "the GSP compiler's tags are no methods: " + g);
    assertEquals(List.of(app().tag), labels(completion("<p>${NS.}</p>\n", "${NS.")));
    List<String> bareNames = labels(completion("<p>${messa}</p>\n", "${messa"));
    assertTrue(bareNames.contains("message"), bareNames.toString());
    List<String> keys = labels(completion("<p>${g.link(contr)}</p>\n", "${g.link(contr"));
    assertTrue(keys.contains("controller"), keys.toString());
    List<Completion> items = completion("<p>${g.createL}</p>\n", "${g.createL");
    Completion createLink = items.stream().filter(c -> c.label.equals("createLink")).findFirst().orElseThrow();
    CompletionDetail detail = workspace.contextOf(page()).query(a -> a.resolveCompletion(page().toString(), createLink.id)).get(60, TimeUnit.SECONDS);
    assertTrue(detail.documentation.contains("<g:createLink>"), detail.documentation);
  }

  @Test
  void tagsCalledAsMethodsInGroovy() throws Exception {
    Path controller;
    try (var files = Files.walk(views.getParent().resolve("controllers"))) {
      controller = files.filter(f -> f.getFileName().toString().equals(controllerFile())).findFirst().orElseThrow();
    }
    String text = Files.readString(controller);
    String call = "\n    def linked() {\n        [url: g.createLink(action: 'index'), own: NS.TAG(wanted: 'a')]\n    }\n}";
    String edited = fill(text.substring(0, text.lastIndexOf('}')) + call);
    String hover = query(controller, edited, "createLink", 2, (a, path, line, column) -> a.hover(path, line, column));
    assertTrue(hover != null && hover.contains("<g:createLink>"), hover);
    Location location = query(controller, edited, fill("NS.TAG"), app().namespace.length() + 2, (a, path, line, column) -> a.definition(path, line, column));
    assertNotNull(location);
    assertTrue(location.path.replace('\\', '/').endsWith("grails-app/" + app().taglib), location.path);
    String typing = fill(text.substring(0, text.lastIndexOf('}')) + "\n    def linked() {\n        g.createL\n    }\n}");
    List<String> names = labels(completion(controller, typing, "g.createL"));
    assertTrue(names.contains("createLink"), names.toString());
  }

  @Test
  void modelVariablesComeFromTheActionsRenderingTheView() throws Exception {
    Path controller;
    try (var files = Files.walk(views.getParent().resolve("controllers"))) {
      controller = files.filter(f -> f.getFileName().toString().equals(controllerFile())).findFirst().orElseThrow();
    }
    String c = app().controller;
    String actions = "package fixture." + app().namespace + "\n\nclass " + controllerFile().replace(".groovy", "") + " {\n"
        + "    def index() {\n        render(view: 'index', model: [total: 3])\n    }\n\n"
        + "    def shown(Long id) {\n        String title = 't'\n        List<String> words = []\n        def other = id\n"
        + "        render view: '/" + c + "/shown', model: [title: title, words: words, other: other, owner: new " + domainType() + "(), found: id]\n    }\n\n"
        + "    def listed() {\n        [count: 1L]\n    }\n}\n";
    open = Map.of(controller, actions);
    String index = "<p>${total}</p>\n";
    assertEquals(List.of(), hints(compile(page(), index)));
    assertEquals("controllers/" + controller.toString().replace('\\', '/').split("grails-app/controllers/")[1] + ":4", definitionOf(index, "total", 2), "the model entry");
    assertTrue(labels(completion("<p>${tot}</p>\n", "${tot")).contains("total"));
    assertTrue(labels(completion("<p>${total.}</p>\n", "${total.")).contains("intValue"), "typed Integer");

    Path shown = views.resolve(c).resolve("shown.gsp");
    String page = "<g:set var=\"shout\" value=\"${title.toUpperCase()}\"/>\n<p>${owner} ${other} ${found} ${shout}</p>\n"
        + "<g:each in=\"${words}\" var=\"word\" status=\"i\">${word} ${i}</g:each>\n";
    assertEquals(List.of(), hints(compile(shown, page)));
    Map<String, String> types = Map.of("owner", domainType(), "found", "Long", "shout", "String", "word", "String");
    for (Map.Entry<String, String> entry : types.entrySet()) {
      String hover = query(shown, page, "${" + entry.getKey() + "}", 3, (a, path, line, column) -> a.hover(path, line, column));
      assertTrue(hover != null && hover.contains(entry.getValue()), entry.getKey() + ": " + hover);
    }
    Location set = query(shown, page, "${shout}", 3, (a, path, line, column) -> a.definition(path, line, column));
    assertNotNull(set);
    assertEquals(shown.toString() + ":0:12", set.path + ":" + set.range.startLine + ":" + set.range.startColumn, "the var attribute");
    assertTrue(labels(completion(shown, "<g:set var=\"shout\" value=\"${title.toUpperCase()}\"/>${shout.}\n", "${shout.")).contains("toLowerCase"));
    assertTrue(labels(completion(shown, "<g:each in=\"${words}\" var=\"word\">\n  ${word.}\n</g:each>\n", "${word.")).contains("toLowerCase"), "the element type");
    assertTrue(labels(completion(shown, "<p>${owner.}</p>\n", "${owner.")).contains("save"), "the domain class");

    Path listed = views.resolve(c).resolve("listed.gsp");
    String hover = query(listed, "<p>${count}</p>\n", "count", 2, (a, path, line, column) -> a.hover(path, line, column));
    assertTrue(hover != null && hover.contains("Long"), "a returned map: " + hover);
    Path template = views.resolve(c).resolve("_listed.gsp");
    assertNull(query(template, "<p>${count}</p>\n", "count", 2, (a, path, line, column) -> a.definition(path, line, column)), "a template has no action's model");
  }
}
