package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.Diagnostic;
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
