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

  @BeforeEach
  void setUp() throws Exception {
    ProjectModel model = Fixtures.built(app().fixture, app().jdkVariable);
    workspace = new Workspace(model, AnalysisJar.extract(tmp));
    workspace.index();
    views = Path.of(model.module(":app").projectDir, "grails-app/views");
  }

  /** {@code ns} and {@code tag} stand for the fixture taglib's namespace and tag. */
  private String fill(String text) {
    return text.replace("ns:", app().namespace + ":").replace("TAG", app().tag);
  }

  private Path page() {
    return views.resolve(app().controller).resolve("index.gsp");
  }

  private CompileResult compile(Path path, String text) throws Exception {
    CompileContext context = workspace.contextOf(path);
    assertNotNull(context, "no context for " + path);
    return context.compile(new CompileRequest(path.toString(), text, workspace.lookup(context, p -> null), () -> false)).get(120, TimeUnit.SECONDS);
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
    String filled = fill(text);
    int[] p = at(filled, fill(needle), fill(needle).length());
    CompileContext context = workspace.contextOf(page());
    CompileRequest request = new CompileRequest(page().toString(), filled, workspace.lookup(context, x -> null), () -> false);
    return context.query(a -> a.completion(request, p[0], p[1])).get(60, TimeUnit.SECONDS).items;
  }

  private static List<String> labels(List<Completion> items) {
    return items.stream().map(item -> item.label).collect(Collectors.toList());
  }

  private <T> T query(String text, String needle, int offset, QueryAt<T> query) throws Exception {
    String filled = fill(text);
    compile(page(), filled);
    int[] p = at(filled, fill(needle), offset);
    return workspace.contextOf(page()).query(a -> query.apply(a, page().toString(), p[0], p[1])).get(60, TimeUnit.SECONDS);
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
    assertNull(definitionOf(page, "template=\"${", 12), "an expression");
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
}
