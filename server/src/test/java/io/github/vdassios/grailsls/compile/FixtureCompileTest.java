package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.Analyzer;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * Compiles files of the built fixtures in their module loaders: the project's own Groovy and Grails, and the shapes
 * Groovy's per-file compile gets wrong (fixtures/README.md).
 */
@Timeout(900)
class FixtureCompileTest {
  @TempDir
  Path tmp;

  private Workspace workspace(ProjectModel model) {
    Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp));
    workspace.index();
    return workspace;
  }

  private static Path file(ProjectModel model, String module, String relative) {
    return Path.of(model.module(module).projectDir, relative);
  }

  private static CompileResult compile(Workspace workspace, Path path, String text) throws Exception {
    CompileContext context = workspace.contextOf(path);
    assertNotNull(context, "no source set for " + path);
    return context.compile(new CompileRequest(path.toString(), text, workspace.lookup(context, p -> null), () -> false)).get(120, TimeUnit.SECONDS);
  }

  private static CompileResult compile(Workspace workspace, Path path) throws Exception {
    return compile(workspace, path, Files.readString(path));
  }

  private static List<String> errors(CompileResult result) {
    return result.diagnostics.stream().filter(d -> d.severity == Diagnostic.ERROR).map(Diagnostic::toString).collect(Collectors.toList());
  }

  @Test
  void grails4CompilesWithTheProjectsOwnGroovy() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      assertEquals("2.5.14", workspace.contextOf(controller).analyzer().groovyVersion(), "the fixture's Groovy, not the 2.5.23 the analysis layer is compiled against");
    }
  }

  @Test
  void controllerUsingEveryShapeCompilesWithoutErrors() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      // joint-compiled Java (Isbn), nested enum (Book.Format), file of another name (TitleCase), package unlike its directory (Shelf)
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      CompileResult result = compile(workspace, controller);
      assertEquals(List.of(), errors(result));
      assertEquals(7, result.sources, "controller, Book, Author, BookService, Formatting, Shelf, sibling Clock");
    }
  }

  @Test
  void aDomainClassPulledInByAControllerCarriesGormMembers() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      compile(workspace, controller);
      CompiledClass book = workspace.contextOf(controller).analyzer().compiledClass(controller.toString(), "fixture.library.Book");
      assertNotNull(book);
      assertEquals(file(model, ":app", "grails-app/domain/fixture/library/Book.groovy").toString(), book.sourcePath);
      assertTrue(book.interfaces.contains("org.grails.datastore.gorm.GormEntity"), book.interfaces.toString());
      assertTrue(book.methods.containsAll(List.of("save", "get", "list", "validate")), book.methods.toString());

      CompiledClass self = workspace.contextOf(controller).analyzer().compiledClass(controller.toString(), "fixture.library.BookController");
      assertTrue(self.interfaces.contains("grails.artefact.Controller"), self.interfaces.toString());
      assertTrue(self.methods.containsAll(List.of("render", "redirect", "getParams")), self.methods.toString());
    }
  }

  /** Runs a query on the context thread of the file, after compiling it. */
  private static <T> T query(Workspace workspace, Path path, Function<Analyzer, T> query) throws Exception {
    assertEquals(List.of(), errors(compile(workspace, path)));
    return workspace.contextOf(path).query(query).get(60, TimeUnit.SECONDS);
  }

  /** Line and column of {@code needle} in the file, plus {@code offset} columns; the needle must occur once. */
  private static int[] at(Path file, String needle, int offset) throws Exception {
    String text = Files.readString(file);
    int index = text.indexOf(needle);
    assertTrue(index >= 0 && text.indexOf(needle, index + 1) < 0, needle + " occurs once");
    int line = (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    return new int[] { line, index - (text.lastIndexOf('\n', index) + 1) + offset };
  }

  private static String definition(Workspace workspace, Path file, String needle, int offset) throws Exception {
    int[] p = at(file, needle, offset);
    Location location = query(workspace, file, a -> a.definition(file.toString(), p[0], p[1]));
    return location == null ? null : Path.of(location.path).getFileName() + ":" + (location.range.startLine + 1);
  }

  @Test
  void definitionFromAControllerReachesEveryShapeInSource() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      Map<String, String> found = new TreeMap<>();
      found.put("domain class", definition(workspace, controller, "Book.get(id)", 0));
      found.put("nested enum", definition(workspace, controller, "Format.PAPERBACK", 0));
      found.put("file of another name", definition(workspace, controller, "TitleCase.of", 0));
      found.put("package unlike its directory", definition(workspace, controller, "Shelf(format", 0)); // its constructor
      found.put("service method", definition(workspace, controller, "store(book)", 0));
      found.put("joint-compiled Java", definition(workspace, controller, "Isbn.normalize", 0));
      Map<String, String> expected = new TreeMap<>();
      expected.put("domain class", "Book.groovy:3");
      expected.put("nested enum", "Book.groovy:5");
      expected.put("file of another name", "Formatting.groovy:4");
      expected.put("package unlike its directory", "Shelf.groovy:7");
      expected.put("service method", "BookService.groovy:8");
      expected.put("joint-compiled Java", null); // from its class file: no source yet (library sources come later)
      assertEquals(expected, found);
    }
  }

  /** Members the Grails transformations add, on the open file and on a domain class pulled in from source. */
  @Test
  void hoverShowsMembersAddedByGrails() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      Path service = file(model, ":app", "grails-app/services/fixture/library/BookService.groovy");
      Map<String, String> hovers = new TreeMap<>();
      for (Object[] probe : new Object[][] { { controller, "list()" }, { controller, "render(" }, { controller, "redirect(" }, { service, "save(" } }) {
        Path file = (Path) probe[0];
        int[] p = at(file, (String) probe[1], 1);
        String hover = query(workspace, file, a -> a.hover(file.toString(), p[0], p[1]));
        hovers.put((String) probe[1], hover == null ? null : hover.split("\n")[1]);
      }
      assertEquals(Map.of("list()", "public static List list()", "render(", "public void render(Map param1)", "redirect(", "public void redirect(Map param1)", "save(",
          "public Book save(Map param1)"), hovers);
    }
  }

  @Test
  void symbolsOfAControllerAreItsOwnDeclarations() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      List<String> symbols = query(workspace, controller, a -> a.symbols(controller.toString())).stream()
          .map(s -> s.kind + " " + s.name + " " + (s.range.startLine + 1)).collect(Collectors.toList());
      assertEquals(List.of("CLASS fixture.library.BookController 7", "FIELD bookService 8", "METHOD index 10", "METHOD show 14", "METHOD save 20"), symbols,
          "no members added by Grails");
    }
  }

  /** Grails prints a warning with a sample of the source to System.err for each command object it cannot use. */
  @Test
  void transformationOutputDuringACompileIsDropped() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      String saved = Files.readString(controller);
      String text = saved.substring(0, saved.lastIndexOf('}')) + "    def run(Runnable task) { [task: task] }\n}\n"; // an interface: Grails warns
      PrintStream savedErr = System.err;
      PrintStream savedOut = System.out;
      ByteArrayOutputStream printed = new ByteArrayOutputStream();
      CompileResult result;
      try {
        System.setErr(new PrintStream(printed, true));
        System.setOut(new PrintStream(printed, true));
        result = compile(workspace, controller, text);
        System.err.print("after");
      } finally {
        System.setErr(savedErr);
        System.setOut(savedOut);
      }
      assertEquals(List.of(), errors(result));
      assertEquals("after", printed.toString(), "only output from outside the compile");
    }
  }

  @Test
  void javaInAGroovySourceDirectoryResolvesFromItsClassFile() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path probe = file(model, ":app", "src/main/groovy/fixture/text/Probe.groovy"); // an unsaved new file
      CompileResult result = compile(workspace, probe, "package fixture.text\nimport fixture.isbn.Isbn\nclass Probe { String s = Slug.of('A b') + Isbn.normalize('1-2') }\n");
      assertEquals(List.of(), errors(result));
      CompileContext context = workspace.contextOf(probe);
      assertEquals(2, context.javaClasses(), "Isbn and Slug only");
      assertEquals("", workspace.summary(), "every Java file is built");
      Path appBuild = Path.of(model.module(":app").projectDir, "build");
      assertTrue(context.classpath().stream().noneMatch(p -> p.startsWith(appBuild)), "the app's class output is not on the loader");
    }
  }

  @Test
  void siblingModuleClassesResolveFromSource() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path service = file(model, ":app", "grails-app/services/fixture/library/BookService.groovy");
      assertEquals(List.of(), errors(compile(workspace, service)));
      CompiledClass clock = workspace.contextOf(service).analyzer().compiledClass(service.toString(), "fixture.support.Clock");
      assertEquals(file(model, ":support", "src/main/groovy/fixture/support/Clock.groovy").toString(), clock.sourcePath);
    }
  }

  /** Each kept every AST it saw until the loader closed: 2.5 GB after a sweep of one module of the target project. */
  @Test
  void grailsTransformationCachesAreEmptyAfterACompile() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    try (Workspace workspace = workspace(model)) {
      Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
      assertEquals(List.of(), errors(compile(workspace, controller)));
      ClassLoader loader = workspace.contextOf(controller).loader();
      Map<String, Integer> sizes = new TreeMap<>();
      sizes.put("data binding", staticMapSize(loader, "org.grails.web.databinding.DefaultASTDatabindingHelper", "CLASS_NODE_TO_WHITE_LIST_PROPERTY_NAMES"));
      sizes.put("GORM domain check", staticMapSize(loader, "org.grails.datastore.mapping.reflect.AstUtils", "memoizedMethodClosure$isDomainClassClassNode"));
      sizes.put("GORM properties", staticMapSize(loader, "org.grails.datastore.gorm.transform.AstPropertyResolveUtils", "cachedClassProperties"));
      assertEquals(Map.of("data binding", 0, "GORM domain check", 0, "GORM properties", 0), sizes);
    }
  }

  /** Size of a static map, or of the cache of a static {@code @Memoized} closure, read through the module loader. */
  private static int staticMapSize(ClassLoader loader, String className, String fieldName) throws ReflectiveOperationException {
    Field field = Class.forName(className, false, loader).getDeclaredField(fieldName);
    field.setAccessible(true);
    Object value = field.get(null);
    if (!(value instanceof Map)) {
      Field cache = value.getClass().getDeclaredField("cache");
      cache.setAccessible(true);
      value = cache.get(value);
    }
    return ((Map<?, ?>) value).size();
  }

  @Test
  void aClosedModuleLoaderWithGrailsLoadedIsGarbage() throws Exception {
    ProjectModel model = Fixtures.built("grails4", "GLS_TEST_JDK11");
    Workspace workspace = workspace(model);
    Path controller = file(model, ":app", "grails-app/controllers/fixture/library/BookController.groovy");
    for (int i = 0; i < 3; i++) {
      assertEquals(List.of(), errors(compile(workspace, controller)));
    }
    WeakReference<ClassLoader> loader = new WeakReference<>(workspace.contextOf(controller).loader());
    assertNotNull(loader.get());
    workspace.close();
    assertTrue(Gc.collected(loader), "a reference to the module loader survived its close");
  }

  @Test
  void plainGroovyLibraryUsesItsGroovy3() throws Exception {
    ProjectModel model = Fixtures.built("groovy-library", "GLS_TEST_JDK17");
    try (Workspace workspace = workspace(model)) {
      Path shape = file(model, ":", "src/main/groovy/fixture/shapes/Shape.groovy");
      assertEquals("3.0.25", workspace.contextOf(shape).analyzer().groovyVersion());
      assertEquals(List.of(), errors(compile(workspace, shape)));
    }
  }
}
