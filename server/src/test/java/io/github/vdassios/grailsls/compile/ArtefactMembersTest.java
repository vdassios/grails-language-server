package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * Members Grails adds to controllers, domain classes, services and taglibs (M2), on the open file and on classes pulled
 * in from source, and untyped injection ({@code def bookService}). The same checks run on each Grails fixture.
 */
@Timeout(900)
abstract class ArtefactMembersTest {
  @TempDir
  Path tmp;

  /** The fixture's Grails app: artefact files (relative to the app module) and names. */
  static final class App {
    final String fixture;
    final String jdkVariable;
    final String controller;
    final String domain;
    final String service;
    final String taglib;
    final String domainClass;
    final String serviceClass;
    /** The service's bean name, injected untyped in the taglib and typed in the controller. */
    final String bean;
    /** A service method the taglib calls through the untyped bean, and its declaration line (1-based). */
    final String serviceMethod;
    final int serviceMethodLine;

    App(String fixture, String jdkVariable, String controller, String domain, String service, String taglib, String domainClass, String serviceClass, String bean,
        String serviceMethod, int serviceMethodLine) {
      this.fixture = fixture;
      this.jdkVariable = jdkVariable;
      this.controller = controller;
      this.domain = domain;
      this.service = service;
      this.taglib = taglib;
      this.domainClass = domainClass;
      this.serviceClass = serviceClass;
      this.bean = bean;
      this.serviceMethod = serviceMethod;
      this.serviceMethodLine = serviceMethodLine;
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

  private static CompileResult compile(Workspace workspace, Path path, String text) throws Exception {
    CompileContext context = workspace.contextOf(path);
    assertNotNull(context, "no source set for " + path);
    return context.compile(new CompileRequest(path.toString(), text, workspace.lookup(context, p -> null), () -> false)).get(120, TimeUnit.SECONDS);
  }

  private static List<String> errors(CompileResult result) {
    return result.diagnostics.stream().filter(d -> d.severity == Diagnostic.ERROR).map(Diagnostic::toString).collect(Collectors.toList());
  }

  /** Compiles the file as saved, with no errors, and returns how its class came out. */
  private CompiledClass compiled(Workspace workspace, String relative, String className) throws Exception {
    Path path = file(relative);
    assertEquals(List.of(), errors(compile(workspace, path, Files.readString(path))), relative);
    CompiledClass compiled = workspace.contextOf(path).analyzer().compiledClass(path.toString(), className);
    assertNotNull(compiled, className + " in the compile of " + relative);
    return compiled;
  }

  private static String className(String relative) {
    String name = relative.substring(relative.indexOf('/', "grails-app/".length()) + 1, relative.length() - ".groovy".length());
    return name.replace('/', '.');
  }

  @Test
  void eachArtefactAsTheOpenFileGetsItsGrailsMembers() throws Exception {
    try (Workspace workspace = workspace()) {
      Map<String, Boolean> found = new TreeMap<>();
      CompiledClass controller = compiled(workspace, app().controller, className(app().controller));
      found.put("controller trait", controller.interfaces.contains("grails.artefact.Controller") && controller.methods.containsAll(List.of("render", "redirect", "getParams")));
      CompiledClass domain = compiled(workspace, app().domain, app().domainClass);
      found.put("domain GORM entity", domain.interfaces.contains("org.grails.datastore.gorm.GormEntity") && domain.methods.containsAll(List.of("save", "get", "list")));
      // Grails gives services no artefact trait; @Transactional adds the transaction manager
      CompiledClass service = compiled(workspace, app().service, app().serviceClass);
      found.put("transactional service", service.methods.containsAll(List.of("getTransactionManager", "setTransactionManager")));
      CompiledClass taglib = compiled(workspace, app().taglib, className(app().taglib));
      found.put("taglib trait", taglib.interfaces.contains("grails.artefact.TagLibrary") && taglib.methods.containsAll(List.of("getOut", "throwTagError")));
      assertEquals(Map.of("controller trait", true, "domain GORM entity", true, "transactional service", true, "taglib trait", true), found);
    }
  }

  /** The file's text with {@code def probe() { <typed> }} added as the last member of its class, and the position after {@code typed}. */
  private static Object[] withProbe(String saved, String typed) {
    int end = saved.lastIndexOf('}');
    String text = saved.substring(0, end) + "\n    def probe() {\n        " + typed + "\n    }\n" + saved.substring(end);
    int line = (int) text.substring(0, text.indexOf("        " + typed + "\n    }")).chars().filter(c -> c == '\n').count();
    return new Object[] { text, line, 8 + typed.length() };
  }

  /** Completion labels at a position of {@code text}, the file's current text. */
  private static List<String> completion(Workspace workspace, Path path, String text, int line, int column) throws Exception {
    CompileContext context = workspace.contextOf(path);
    compile(workspace, path, text);
    CompileRequest request = new CompileRequest(path.toString(), text, workspace.lookup(context, p -> null), () -> false);
    return context.query(a -> a.completion(request, line, column)).get(60, TimeUnit.SECONDS).items.stream().map(c -> c.label).collect(Collectors.toList());
  }

  /** Completion after {@code typed} in a method added to the file. */
  private List<String> completionInProbe(Workspace workspace, String relative, String typed) throws Exception {
    Path path = file(relative);
    Object[] probe = withProbe(Files.readString(path), typed);
    return completion(workspace, path, (String) probe[0], (int) probe[1], (int) probe[2]);
  }

  @Test
  void membersCompleteOnEachArtefactAsTheOpenFile() throws Exception {
    try (Workspace workspace = workspace()) {
      Map<String, List<String>> missing = new TreeMap<>();
      Map<String, List<String>> expected = Map.of(app().controller, List.of("render", "redirect", "getParams", "bindData"), //
          app().domain, List.of("save", "validate", "delete", "errors"), //
          app().service, List.of("getTransactionManager", app().serviceMethod), //
          app().taglib, List.of("getOut", "throwTagError", "getPageScope", app().bean));
      for (Map.Entry<String, List<String>> entry : expected.entrySet()) {
        List<String> labels = completionInProbe(workspace, entry.getKey(), "this.");
        missing.put(entry.getKey(), entry.getValue().stream().filter(m -> !labels.contains(m)).collect(Collectors.toList()));
      }
      assertEquals(expected.keySet().stream().collect(Collectors.toMap(k -> k, k -> List.<String>of())), missing);
    }
  }

  @Test
  void membersCompleteOnClassesPulledInFromSource() throws Exception {
    try (Workspace workspace = workspace()) {
      String domain = app().domainClass.substring(app().domainClass.lastIndexOf('.') + 1);
      List<String> statics = completionInProbe(workspace, app().controller, domain + ".");
      assertTrue(statics.containsAll(List.of("list", "get", "findAll", "where", "count")), statics.toString());
      List<String> service = completionInProbe(workspace, app().controller, app().bean + ".");
      assertTrue(service.containsAll(List.of(app().serviceMethod, "getTransactionManager")), "a typed service property: " + service);
    }
  }

  @Test
  void hoverShowsMembersAddedByGrails() throws Exception {
    try (Workspace workspace = workspace()) {
      Map<String, String> hovers = new TreeMap<>();
      hovers.put("controller render", hover(workspace, app().controller, "render(", 1));
      hovers.put("service log", hover(workspace, app().service, "log.info", 1));
      hovers.put("taglib out", hover(workspace, app().taglib, "out <<", 1));
      // Groovy 4's class files keep parameter names
      String render = app().fixture.equals("grails4") ? "public void render(Map param1)" : "public void render(Map argMap)";
      assertEquals(Map.of("controller render", render, "service log", "private final static Logger log", "taglib out", "public GrailsPrintWriter getOut()"), hovers);
    }
  }

  /**
   * Names the transformations add after Groovy binds variables ({@code params}, {@code log}, {@code out}) are typed by
   * their getter or field, and getters complete as properties.
   */
  @Test
  void implicitMembersTypeTheirReceivers() throws Exception {
    try (Workspace workspace = workspace()) {
      Map<String, Boolean> found = new TreeMap<>();
      List<String> params = completionInProbe(workspace, app().controller, "params.");
      found.put("controller params.", params.containsAll(List.of("int", "list", "getIdentifier")));
      found.put("controller bare params", completionInProbe(workspace, app().controller, "para").contains("params"));
      found.put("service log.", completionInProbe(workspace, app().service, "log.").containsAll(List.of("info", "debug", "isDebugEnabled")));
      found.put("taglib out.", completionInProbe(workspace, app().taglib, "out.").containsAll(List.of("print", "leftShift")));
      found.put("domain errors.", completionInProbe(workspace, app().domain, "errors.").containsAll(List.of("hasErrors", "getFieldError")));
      assertEquals(Map.of("controller params.", true, "controller bare params", true, "service log.", true, "taglib out.", true, "domain errors.", true), found, params.toString());
    }
  }

  /** The signature line of the hover at {@code needle} (which occurs once in the saved file) plus {@code offset} columns. */
  private String hover(Workspace workspace, String relative, String needle, int offset) throws Exception {
    Path path = file(relative);
    String text = Files.readString(path);
    int[] p = at(text, needle, offset);
    assertEquals(List.of(), errors(compile(workspace, path, text)));
    String hover = workspace.contextOf(path).query(a -> a.hover(path.toString(), p[0], p[1])).get(60, TimeUnit.SECONDS);
    return hover == null ? null : hover.split("\n")[1];
  }

  private static int[] at(String text, String needle, int offset) {
    int index = text.indexOf(needle);
    assertTrue(index >= 0 && text.indexOf(needle, index + 1) < 0, needle + " occurs once");
    int line = (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    return new int[] { line, index - (text.lastIndexOf('\n', index) + 1) + offset };
  }

  /** {@code def bookService} in a taglib: typed as the service Grails injects by that name. */
  @Test
  void anUntypedServicePropertyIsTypedByItsBeanName() throws Exception {
    try (Workspace workspace = workspace()) {
      Path taglib = file(app().taglib);
      String text = Files.readString(taglib);
      String call = app().bean + "." + app().serviceMethod + "(";
      int[] method = at(text, call, app().bean.length() + 1);

      List<String> members = completion(workspace, taglib, text, method[0], method[1]);
      assertTrue(members.containsAll(List.of(app().serviceMethod, "getTransactionManager")), members.toString());

      Location location = workspace.contextOf(taglib).query(a -> a.definition(taglib.toString(), method[0], method[1])).get(60, TimeUnit.SECONDS);
      assertNotNull(location, "definition of " + call);
      assertEquals(file(app().service).toString() + ":" + app().serviceMethodLine, location.path + ":" + (location.range.startLine + 1));

      String hover = workspace.contextOf(taglib).query(a -> a.hover(taglib.toString(), method[0], method[1])).get(60, TimeUnit.SECONDS);
      assertNotNull(hover);
      assertTrue(hover.contains(" " + app().serviceMethod + "(String wanted)"), hover);
    }
  }

  @Test
  void anUntypedPropertyWithoutAServiceOfItsNameStaysUntyped() throws Exception {
    try (Workspace workspace = workspace()) {
      Path taglib = file(app().taglib);
      String saved = Files.readString(taglib);
      int end = saved.lastIndexOf('}');
      String withProperty = saved.substring(0, end) + "    def missingService\n" + saved.substring(end);
      Object[] probe = withProbe(withProperty, "missingService.");
      List<String> labels = completion(workspace, taglib, (String) probe[0], (int) probe[1], (int) probe[2]);
      assertTrue(!labels.contains("getTransactionManager") && !labels.contains(app().serviceMethod), labels.toString());
      assertTrue(labels.contains("getClass"), "members of Object: " + labels);
    }
  }
}
