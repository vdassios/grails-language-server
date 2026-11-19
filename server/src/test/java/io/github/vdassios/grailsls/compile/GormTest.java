package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.CompletionDetail;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.SignatureHelp;
import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * GORM (M3): dynamic finders, criteria blocks, {@code constraints} blocks and query settings, in a service that is not
 * on disk. The same checks run on each Grails fixture.
 */
@Timeout(900)
abstract class GormTest {
  @TempDir
  Path tmp;

  /** The fixture's domain classes: one with two properties, and one with a {@code hasMany} of it. */
  static final class App {
    final String fixture;
    final String jdkVariable;
    final String packageName;
    /** A domain class, its first property (a String) and its second. */
    final String domain;
    final String first;
    final String second;
    /** A domain class with a String property and a {@code hasMany} of {@link #domain}. */
    final String owner;
    final String ownerProperty;
    final String many;

    App(String fixture, String jdkVariable, String packageName, String domain, String first, String second, String owner, String ownerProperty, String many) {
      this.fixture = fixture;
      this.jdkVariable = jdkVariable;
      this.packageName = packageName;
      this.domain = domain;
      this.first = first;
      this.second = second;
      this.owner = owner;
      this.ownerProperty = ownerProperty;
      this.many = many;
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

  private Path file(String relative) {
    return Path.of(model.module(":app").projectDir, relative);
  }

  private Path domainFile(String simpleName) {
    return file("grails-app/domain/" + app().packageName.replace('.', '/') + "/" + simpleName + ".groovy");
  }

  private Path service() {
    return file("grails-app/services/" + app().packageName.replace('.', '/') + "/QueryService.groovy");
  }

  private static String cap(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }

  /** {@code D} is the domain class, {@code O} the owner, {@code First}/{@code first} and {@code Second}/{@code second} the properties. */
  private String fill(String template) {
    App app = app();
    return template.replace("First", cap(app.first)).replace("Second", cap(app.second)).replace("first", app.first).replace("second", app.second)
        .replace("OWNERPROPERTY", app.ownerProperty).replace("MANY", app.many).replace("Many", cap(app.many)).replace("D.", app.domain + ".").replace("O.", app.owner + ".");
  }

  /** A service with one method of this body ({@link #fill}). */
  private String serviceWith(String... body) {
    StringBuilder text = new StringBuilder("package " + app().packageName + "\n\nclass QueryService {\n    def run(String wanted) {\n");
    for (String line : body) {
      text.append("        ").append(fill(line)).append('\n');
    }
    return text.append("    }\n}\n").toString();
  }

  private CompileResult compile(Workspace workspace, Path path, String text) throws Exception {
    CompileContext context = workspace.contextOf(path);
    assertNotNull(context, "no source set for " + path);
    return context.compile(new CompileRequest(path.toString(), text, workspace.lookup(context, p -> null), () -> false)).get(120, TimeUnit.SECONDS);
  }

  private static List<String> messages(CompileResult result, int severity) {
    return result.diagnostics.stream().filter(d -> d.severity == severity).map(d -> (d.startLine + 1) + ":" + (d.startColumn + 1) + "-" + (d.endColumn + 1) + " " + d.message)
        .collect(Collectors.toList());
  }

  /** The position of {@code needle}, which occurs once, plus {@code offset} columns. */
  private static int[] at(String text, String needle, int offset) {
    int index = text.indexOf(needle);
    assertTrue(index >= 0 && text.indexOf(needle, index + 1) < 0, needle + " occurs once in\n" + text);
    int line = (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    return new int[] { line, index - (text.lastIndexOf('\n', index) + 1) + offset };
  }

  /** {@code File.groovy:line} (1-based) of the definition at a position, after a compile without errors. */
  private String definition(Workspace workspace, Path path, String text, String needle, int offset) throws Exception {
    assertEquals(List.of(), messages(compile(workspace, path, text), Diagnostic.ERROR));
    int[] p = at(text, fill(needle), offset);
    Location location = workspace.contextOf(path).query(a -> a.definition(path.toString(), p[0], p[1])).get(60, TimeUnit.SECONDS);
    return location == null ? null : Path.of(location.path).getFileName() + ":" + (location.range.startLine + 1);
  }

  private String hover(Workspace workspace, Path path, String text, String needle, int offset) throws Exception {
    assertEquals(List.of(), messages(compile(workspace, path, text), Diagnostic.ERROR));
    int[] p = at(text, fill(needle), offset);
    return workspace.contextOf(path).query(a -> a.hover(path.toString(), p[0], p[1])).get(60, TimeUnit.SECONDS);
  }

  /** Completion items right after {@code needle}. */
  private List<Completion> completion(Workspace workspace, Path path, String text, String needle) throws Exception {
    return completionList(workspace, path, text, needle).items;
  }

  private CompletionList completionList(Workspace workspace, Path path, String text, String needle) throws Exception {
    CompileContext context = workspace.contextOf(path);
    compile(workspace, path, text);
    int[] p = at(text, fill(needle), fill(needle).length());
    CompileRequest request = new CompileRequest(path.toString(), text, workspace.lookup(context, x -> null), () -> false);
    return context.query(a -> a.completion(request, p[0], p[1])).get(60, TimeUnit.SECONDS);
  }

  private List<String> labels(Workspace workspace, Path path, String text, String needle) throws Exception {
    return completion(workspace, path, text, needle).stream().map(c -> c.label).collect(Collectors.toList());
  }

  /** {@code File.groovy:line} of the line that declares a property in a domain class of the fixture. */
  private String declared(String domain, String declaration) throws Exception {
    List<String> lines = Files.readAllLines(domainFile(domain));
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).contains(declaration)) {
        return domain + ".groovy:" + (i + 1);
      }
    }
    throw new AssertionError(declaration + " in " + domain);
  }

  @Test
  void finderNamesGoToTheirProperties() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      String text = serviceWith("def one = D.findByFirstLikeAndSecond(wanted, null)", "def all = O.findAllByManyIsNotEmpty()", "[one, all]");
      Map<String, String> found = new TreeMap<>();
      found.put("first condition", definition(workspace, service(), text, "FirstLike", 1));
      found.put("its comparator", definition(workspace, service(), text, "LikeAnd", 1));
      found.put("second condition", definition(workspace, service(), text, "AndSecond", 4));
      found.put("prefix", definition(workspace, service(), text, "findByFirst", 1));
      found.put("hasMany key", definition(workspace, service(), text, "ManyIsNotEmpty", 1));
      assertEquals(Map.of("first condition", declared(app.domain, " " + app.first), "its comparator", declared(app.domain, " " + app.first), "second condition",
          declared(app.domain, " " + app.second), "prefix", declared(app.domain, "class " + app.domain), "hasMany key", declared(app.owner, "hasMany")), found);
    }
  }

  /** GORM adds {@code id} and {@code version} with no position: definition goes to the domain class's declaration. */
  @Test
  void idAndVersionGoToTheDomainClass() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      String text = serviceWith("def d = D.get(1)", "[d.id, d.version, D.get(2)?.id]");
      Map<String, String> found = new TreeMap<>();
      found.put("id", definition(workspace, service(), text, "d.id", 2));
      found.put("version", definition(workspace, service(), text, "d.version", 2));
      found.put("safe id", definition(workspace, service(), text, "?.id", 2));
      String declaration = declared(app.domain, "class " + app.domain);
      assertEquals(Map.of("id", declaration, "version", declaration, "safe id", declaration), found);
    }
  }

  @Test
  void aFinderHasASignatureAndTypesItsResult() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      String text = serviceWith("def one = D.findByFirstLikeAndSecondIsNull(wanted)", "def all = D.findAllByFirst(wanted, [max: 1])", "one.save()", "all.size()",
          "D.countByFirst(wanted)");
      String hover = hover(workspace, service(), text, "findByFirstLike", 1);
      assertNotNull(hover);
      assertEquals("public static " + app.domain + " findBy" + cap(app.first) + "LikeAnd" + cap(app.second) + "IsNull(String " + app.first + ")", hover.split("\n")[1]);
      assertTrue(hover.contains("GORM dynamic finder: `" + app.first + " like ? and " + app.second + " is null`"), hover);
      String list = hover(workspace, service(), text, "findAllByFirst", 1);
      assertEquals("public static List findAllBy" + cap(app.first) + "(String " + app.first + ")", list.split("\n")[1]);
      assertEquals("public static Integer countBy" + cap(app.first) + "(String " + app.first + ")", hover(workspace, service(), text, "countByFirst", 1).split("\n")[1]);
      // the result is typed: members of the domain class, and of a list
      assertTrue(hover(workspace, service(), text, "one.save", 5).contains(" save("), "one.save()");
      assertTrue(hover(workspace, service(), text, "all.size", 5).contains(" size()"), "all.size()");

      String call = fill("D.findByFirstLikeAndSecondIsNull(");
      int[] p = at(text, call, call.length());
      CompileContext context = workspace.contextOf(service());
      CompileRequest request = new CompileRequest(service().toString(), text, workspace.lookup(context, x -> null), () -> false);
      SignatureHelp help = context.query(a -> a.signatureHelp(request, p[0], p[1])).get(60, TimeUnit.SECONDS);
      assertNotNull(help, "signature help in " + call);
      assertEquals(List.of("String " + app.first), help.signatures.get(0).parameters);
      assertEquals("0 0", help.activeSignature + " " + help.activeParameter);
    }
  }

  @Test
  void findersCompleteOneStepAtATime() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      String first = cap(app.first);
      List<String> statics = labels(workspace, service(), serviceWith("D."), "D.");
      assertTrue(statics.containsAll(List.of("findBy", "findAllBy", "countBy", "list", "get")), statics.toString());
      List<String> properties = labels(workspace, service(), serviceWith("D.findBy"), "D.findBy");
      assertTrue(properties.containsAll(List.of("findBy" + first, "findBy" + cap(app.second), "findById")) && !properties.contains("findByErrors"), properties.toString());
      List<Completion> steps = completion(workspace, service(), serviceWith("D.findByFirst"), "D.findByFirst");
      List<String> stepLabels = steps.stream().map(c -> c.label).collect(Collectors.toList());
      assertTrue(stepLabels.containsAll(List.of("findBy" + first + "Like", "findBy" + first + "And", "findBy" + first + "IsNull")), stepLabels.toString());
      // a whole finder resolves to its signature
      Completion like = steps.stream().filter(c -> c.label.equals("findBy" + first + "Like")).findFirst().get();
      CompletionDetail detail = workspace.contextOf(service()).query(a -> a.resolveCompletion(service().toString(), like.id)).get(60, TimeUnit.SECONDS);
      assertEquals("public static " + app.domain + " findBy" + first + "Like(String " + app.first + ")", detail.detail);
      assertTrue(like.sortText.compareTo("a") < 0, "sorted before the members");
      // the next step depends on what is typed next, so clients must ask again rather than filter this list
      assertTrue(completionList(workspace, service(), serviceWith("D.findBy"), "D.findBy").incomplete);
      assertFalse(completionList(workspace, service(), serviceWith("D.withCriteria {", "    il", "}"), "    il").incomplete);
    }
  }

  @Test
  void aFinderGormWouldRejectIsAWarning() throws Exception {
    try (Workspace workspace = workspace()) {
      String text = serviceWith("D.findByFirst(wanted)", "D.findByFirst(wanted, [max: 1])", "D.findAllByFirstIsNull([sort: 'first']) { }", "D.findByNoSuchThing(wanted)",
          "D.findByFirstAndSecondOrFirst(wanted, null, wanted)", "D.findByFirst()", "D.countByFirstBetween(wanted)", "D.findByFirst(*[wanted])");
      CompileResult result = compile(workspace, service(), text);
      assertEquals(List.of(), messages(result, Diagnostic.ERROR));
      String name = app().domain;
      String first = cap(app().first);
      String second = cap(app().second);
      int column = 9 + name.length() + 1;
      assertEquals(List.of(
          "8:" + (column + 6) + "-" + (column + 17) + " No property 'noSuchThing' for this finder (" + name + ".findByNoSuchThing)",
          "9:" + (column + 6) + "-" + (column + ("findBy" + first + "And" + second + "Or" + first).length()) + " A finder cannot mix And and Or (" + name + ".findBy" + first + "And" + second + "Or" + first + ")",
          "10:" + column + "-" + (column + 6 + first.length()) + " This finder takes 1 argument, not 0 (" + name + ".findBy" + first + ")",
          "11:" + column + "-" + (column + 7 + first.length() + 7) + " This finder takes 2 arguments, not 1 (" + name + ".countBy" + first + "Between)"), messages(result, Diagnostic.WARNING));
    }
  }

  @Test
  void criteriaBlocksNameMethodsAndProperties() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      String text = serviceWith("def c = O.createCriteria()", "def viaBuilder = c.list(max: 5, sort: 'OWNERPROPERTY') {", "    eq('OWNERPROPERTY', wanted)", "    MANY {",
          "        like('first', wanted)", "    }", "}", "D.withCriteria {", "    ilike('first', wanted)", "    order('second')", "}", "D.list(sort: 'second', max: 3)");
      Map<String, String> found = new TreeMap<>();
      found.put("builder property", definition(workspace, service(), text, "eq('OWNERPROPERTY", 5));
      found.put("association property", definition(workspace, service(), text, " like('first", 8));
      found.put("withCriteria property", definition(workspace, service(), text, "ilike('first", 8));
      found.put("order", definition(workspace, service(), text, "order('second", 8));
      found.put("builder sort", definition(workspace, service(), text, "sort: 'OWNERPROPERTY", 8));
      found.put("list sort", definition(workspace, service(), text, "sort: 'second", 8));
      String first = declared(app.domain, " " + app.first);
      String second = declared(app.domain, " " + app.second);
      String ownerProperty = declared(app.owner, " " + app.ownerProperty);
      assertEquals(Map.of("builder property", ownerProperty, "association property", first, "withCriteria property", first, "order", second, "builder sort", ownerProperty,
          "list sort", second), found);

      // calls in the block are the criteria's
      String eq = hover(workspace, service(), text, "eq('", 1);
      assertTrue(eq != null && eq.contains(" eq(String "), String.valueOf(eq));
      String ilike = hover(workspace, service(), text, "ilike('", 1);
      assertTrue(ilike != null && ilike.contains(" ilike(String "), String.valueOf(ilike));
      String property = hover(workspace, service(), text, " like('first", 8);
      assertTrue(property != null && property.contains("String " + app.first), String.valueOf(property));
    }
  }

  @Test
  void criteriaBlocksComplete() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      List<String> methods = labels(workspace, service(), serviceWith("D.withCriteria {", "    il", "}"), "    il");
      assertTrue(methods.contains("ilike"), methods.toString());
      List<String> properties = labels(workspace, service(), serviceWith("D.withCriteria {", "    eq('', wanted)", "}"), "eq('");
      assertEquals(List.of(app.first, app.second), properties.stream().filter(p -> p.equals(app.first) || p.equals(app.second)).collect(Collectors.toList()), properties.toString());
      assertTrue(properties.contains("id") && !properties.contains("save"), properties.toString());
      List<String> nested = labels(workspace, service(), serviceWith("O.createCriteria().list {", "    MANY {", "        eq('', wanted)", "    }", "}"), "eq('");
      assertTrue(nested.contains(app.first) && !nested.contains(app.ownerProperty), nested.toString());
      List<String> sort = labels(workspace, service(), serviceWith("D.list(sort: '')"), "sort: '");
      assertTrue(sort.containsAll(List.of(app.first, app.second)), sort.toString());
      List<String> settings = labels(workspace, service(), serviceWith("D.list(ma)"), "list(ma");
      assertTrue(settings.contains("max"), settings.toString());
    }
  }

  @Test
  void aConstraintsBlockNamesPropertiesAndConstraints() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      Path domain = domainFile(app.domain);
      String saved = Files.readString(domain);
      String entry = "        " + app.first + " blank: false";
      assertEquals(declared(app.domain, " " + app.first), definition(workspace, domain, saved, entry, 9));
      String hover = hover(workspace, domain, saved, entry, 9);
      assertTrue(hover != null && hover.contains("String " + app.first), String.valueOf(hover));
      List<String> keys = labels(workspace, domain, saved.replace(entry, entry + ", nu"), entry + ", nu");
      assertTrue(keys.contains("nullable") && !keys.contains("blank"), keys.toString());
    }
  }
  @Test
  void aWhereBlockNamesProperties() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      String text = serviceWith("D.where { first == wanted }.list()", "D.findAll { second != null }");
      Map<String, String> found = new TreeMap<>();
      found.put("where", definition(workspace, service(), text, "{ first ==", 3));
      found.put("findAll", definition(workspace, service(), text, "{ second !=", 3));
      found.put("where hover", String.valueOf(hover(workspace, service(), text, "{ first ==", 3)).contains("String " + app.first) ? "yes" : "no");
      assertEquals(Map.of("where", declared(app.domain, " " + app.first), "findAll", declared(app.domain, " " + app.second), "where hover", "yes"), found);
      List<String> names = labels(workspace, service(), serviceWith("D.where { " + app.first.substring(0, 2) + " }.list()"), "{ " + app.first.substring(0, 2));
      assertTrue(names.contains(app.first) && !names.contains(app.second), names.toString());
      if (app.fixture.equals("grails4")) {
        // along an association: the Grails 7 fixture has no to-one association
        assertEquals("Author.groovy:4", definition(workspace, service(), serviceWith("Book.where { author.name == wanted }.list()"), "author.name", 8));
      }
      List<String> scope = labels(workspace, service(), serviceWith("D.where { first == wa }.list()"), "== wa");
      assertTrue(scope.contains("wanted"), scope.toString());
    }
  }

  @Test
  void aDomainClassFindsWithoutAReceiverAndMapsItsProperties() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      Path domain = domainFile(app.domain);
      String saved = Files.readString(domain);
      int end = saved.lastIndexOf('}');
      String text = saved.substring(0, end) + fill("\n    static mapping = {\n        second column: 'c'\n    }\n\n    static def probe(String wanted) {\n        findByFirstIlike(wanted)\n    }\n") + saved.substring(end);
      assertEquals(declared(app.domain, " " + app.first), definition(workspace, domain, text, fill("findByFirstIlike"), 7));
      assertEquals(declared(app.domain, " " + app.second), definition(workspace, domain, text, fill("        second column"), 9));
      String hover = hover(workspace, domain, text, fill("findByFirstIlike"), 1);
      assertEquals(fill("public static ") + app.domain + fill(" findByFirstIlike(String first)"), hover.split("\n")[1]);
      assertEquals(List.of(), messages(compile(workspace, domain, text.replace(fill("findByFirstIlike"), "findByNothing")), Diagnostic.ERROR));
      assertEquals(1, messages(compile(workspace, domain, text.replace(fill("findByFirstIlike"), "findByNothing")), Diagnostic.WARNING).size());
    }
  }

  /** GORM persists the properties a trait gives a domain class: seen in a corpus project, where every domain class takes its label from one. */
  @Test
  void aPropertyFromATraitIsAFinderCondition() throws Exception {
    try (Workspace workspace = workspace()) {
      App app = app();
      Path domain = domainFile(app.domain);
      String saved = Files.readString(domain);
      int end = saved.lastIndexOf('}');
      String text = saved.substring(0, end) + "\n    static def probe(String wanted) {\n        findByMotto(wanted)\n        findByShoutedMotto(wanted)\n    }\n" + saved.substring(end)
          + "\ntrait Mottoed {\n    String motto\n    String getShoutedMotto() { motto.toUpperCase() }\n}\n";
      text = text.replace("class " + app.domain + " {", "class " + app.domain + " implements Mottoed {");
      CompileResult result = compile(workspace, domain, text);
      assertEquals(List.of(), messages(result, Diagnostic.ERROR));
      List<String> warnings = messages(result, Diagnostic.WARNING);
      assertEquals(1, warnings.size(), warnings.toString());
      assertTrue(warnings.get(0).contains("No property 'shoutedMotto'"), warnings.toString());
      assertEquals("public static " + app.domain + " findByMotto(String motto)", hover(workspace, domain, text, "findByMotto(", 1).split("\n")[1]);
      int declared = (int) text.substring(0, text.indexOf("String motto")).chars().filter(c -> c == '\n').count() + 1;
      assertEquals(app.domain + ".groovy:" + declared, definition(workspace, domain, text, "findByMotto(", 7), "the trait's property");
    }
  }
}
