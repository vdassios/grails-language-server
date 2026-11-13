package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.Symbol;
import io.github.vdassios.grailsls.project.ModuleRecord;
import io.github.vdassios.grailsls.project.ModuleRecord.SourceSetRecord;
import io.github.vdassios.grailsls.project.ProjectModel;

/** Open, edit and close documents of a one-module project whose classpath is a real Groovy 2.5 jar. */
class CompileServiceTest {
  @TempDir
  Path tmp;

  private final BlockingQueue<PublishDiagnosticsParams> published = new LinkedBlockingQueue<>();
  private CompileService service;
  private Path src;

  @BeforeEach
  void start() {
    src = tmp.resolve("src");
    service = new CompileService(published::add, CompileService.DEBOUNCE_MILLIS);
    service.setWorkspace(workspace());
  }

  @AfterEach
  void stop() {
    service.shutdown();
  }

  private Workspace workspace(Path... classpath) {
    SourceSetRecord main = new SourceSetRecord();
    main.name = "main";
    main.sourceRoots.add(src.toString());
    main.compileClasspath.add(System.getProperty("gls.testGroovy"));
    for (Path entry : classpath) {
      main.compileClasspath.add(entry.toString());
    }
    ModuleRecord module = new ModuleRecord();
    module.path = ":";
    module.projectDir = tmp.toString();
    module.groovyVersion = "2.5.23";
    module.sourceSets.add(main);
    Workspace workspace = new Workspace(new ProjectModel(tmp, List.of(module), false), AnalysisJar.extract(tmp.resolve("cache")));
    workspace.index();
    return workspace;
  }

  private String uri(String relative) {
    return src.resolve(relative).toUri().toString();
  }

  private PublishDiagnosticsParams next() throws InterruptedException {
    PublishDiagnosticsParams params = published.poll(30, TimeUnit.SECONDS);
    assertNotNull(params, "no diagnostics within 30 s");
    return params;
  }

  private static List<String> messages(PublishDiagnosticsParams params) {
    return params.getDiagnostics().stream().map(d -> d.getRange().getStart().getLine() + ": " + d.getMessage()).collect(Collectors.toList());
  }

  @Test
  void aSyntaxErrorInOneFileLeavesAnotherClean() throws Exception {
    service.open(uri("Broken.groovy"), "class Broken {\n  void x( }\n", 1);
    PublishDiagnosticsParams broken = next();
    assertEquals(uri("Broken.groovy"), broken.getUri());
    assertEquals(1, broken.getDiagnostics().size(), messages(broken).toString());
    assertEquals(1, broken.getVersion());

    service.open(uri("Clean.groovy"), "class Clean { int n }\n", 1);
    PublishDiagnosticsParams clean = next();
    assertEquals(uri("Clean.groovy"), clean.getUri());
    assertEquals(List.of(), messages(clean));
  }

  @Test
  void editsAreDebouncedIntoOneCompileOfTheLastVersion() throws Exception {
    service.open(uri("A.groovy"), "class A {}\n", 1);
    assertEquals(List.of(), messages(next()));
    for (int version = 2; version <= 6; version++) {
      Position end = new Position(0, 9);
      service.change(uri("A.groovy"), List.of(new TextDocumentContentChangeEvent(new Range(end, end), 0, " int f" + version + "\n")), version);
    }
    PublishDiagnosticsParams last = next();
    assertEquals(6, last.getVersion());
    assertNull(published.poll(2 * CompileService.DEBOUNCE_MILLIS + 500, TimeUnit.MILLISECONDS), "one compile for five edits");
    assertEquals("class A { int f6\n int f5\n int f4\n int f3\n int f2\n}\n", service.documents().get(src.resolve("A.groovy")).text);
  }

  @Test
  void otherFilesResolveFromTheIndexAndOpenText() throws Exception {
    Path author = src.resolve("lib/Names.groovy");
    Files.createDirectories(author.getParent());
    Files.writeString(author, "package lib\nclass Author { String name }\n");
    service.fileChanged(author); // created after the workspace was indexed

    service.open(uri("Book.groovy"), "import lib.Author\nclass Book { Author a\n def i() { a.initials() } }\n", 1);
    assertEquals(List.of(), messages(next()), "dynamic call: no error even before initials() exists");

    service.open(author.toUri().toString(), "package lib\nclass Author {\n  String name\n  Missing m\n}\n", 1);
    assertEquals(List.of("3: unable to resolve class Missing"), messages(next()), "the open text, not the file");
  }

  @Test
  void closingClearsTheDiagnostics() throws Exception {
    service.open(uri("Broken.groovy"), "class Broken { void x( }\n", 1);
    assertEquals(1, next().getDiagnostics().size());
    service.close(uri("Broken.groovy"));
    PublishDiagnosticsParams cleared = next();
    assertEquals(uri("Broken.groovy"), cleared.getUri());
    assertEquals(List.of(), cleared.getDiagnostics());
  }

  @Test
  void filesOutsideTheSourceRootsAreNotCompiled() throws Exception {
    service.open(tmp.resolve("build.gradle").toUri().toString(), "this is not groovy {\n", 1);
    service.open(src.resolve("build/Copy.groovy").toUri().toString(), "class Copy {\n", 1);
    assertNull(published.poll(1, TimeUnit.SECONDS));
  }

  @Test
  void nothingCompilesWithoutAWorkspace() throws Exception {
    service.setWorkspace(null); // untrusted or not resolved yet
    service.open(uri("A.groovy"), "class A {\n", 1);
    assertNull(published.poll(1, TimeUnit.SECONDS));
    service.setWorkspace(workspace());
    assertEquals(1, next().getDiagnostics().size(), "compiled once the project is ready");
  }

  @Test
  void aReplacedWorkspaceReleasesItsClassLoader() throws Exception {
    service.open(uri("A.groovy"), "@groovy.transform.ToString class A { String name }\n", 1);
    assertEquals(List.of(), messages(next()));
    WeakReference<ClassLoader> loader = new WeakReference<>(service.workspace().contexts().get(0).loader());
    assertNotNull(loader.get());

    service.setWorkspace(workspace());
    assertEquals(List.of(), messages(next()), "recompiled in the new workspace");
    assertTrue(Gc.collected(loader), "the old module loader (and the Groovy it loaded) is garbage");
  }

  /** A query right after an edit answers about the edited text: it ends the debounce delay instead of waiting it out. */
  @Test
  void aQueryRunsThePendingCompileAtOnce() throws Exception {
    CompileService slow = new CompileService(published::add, 60_000);
    try {
      slow.setWorkspace(workspace());
      slow.open(uri("A.groovy"), "class A {\n}\n", 1);
      assertEquals(List.of("CLASS A"), symbols(slow, "A.groovy"));
      Position end = new Position(0, 9);
      slow.change(uri("A.groovy"), List.of(new TextDocumentContentChangeEvent(new Range(end, end), 0, "\n  int count() { 1 }")), 2);
      assertEquals(List.of("CLASS A", "METHOD count"), symbols(slow, "A.groovy"));
    } finally {
      slow.shutdown();
    }
  }

  @Test
  void aQueryOnADocumentThatIsNotOpenFindsNothing() throws Exception {
    assertEquals(List.of(), symbols(service, "A.groovy"));
    service.open(uri("A.groovy"), "class A {}\n", 1);
    assertEquals(List.of("CLASS A"), symbols(service, "A.groovy"));
    service.close(uri("A.groovy"));
    assertEquals(List.of(), symbols(service, "A.groovy"));
  }

  /**
   * A class on the classpath loads its members on first use; when one of their types is missing (stale build output),
   * Groovy throws. Seen on hover in the target project: the request failed.
   */
  @Test
  void queriesAnswerWhenAClassOnTheClasspathRefersToAMissingClass() throws Exception {
    Path sources = Files.createDirectories(tmp.resolve("java/lib"));
    Files.writeString(sources.resolve("A.java"), "package lib;\npublic class A { public B make() { return null; } public int size() { return 0; } }\n");
    Files.writeString(sources.resolve("B.java"), "package lib;\npublic class B {}\n");
    Path classes = tmp.resolve("classes");
    int exit = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sources.resolve("A.java").toString(), sources.resolve("B.java").toString());
    assertEquals(0, exit);
    Files.delete(classes.resolve("lib/B.class"));

    service.setWorkspace(workspace(classes));
    String text = "class D {\n  def run(lib.A a) {\n    a.size()\n  }\n}\n";
    service.open(uri("D.groovy"), text, 1);
    assertEquals(List.of(), messages(next()));
    String hover = service.query(uri("D.groovy"), null, (analyzer, path) -> analyzer.hover(path, 2, 7)).get(30, TimeUnit.SECONDS);
    assertNull(hover, "A's methods cannot be read");
    io.github.vdassios.grailsls.api.CompletionList completion = service.queryText(uri("D.groovy"), null, (analyzer, request) -> analyzer.completion(request, 2, 6))
        .get(30, TimeUnit.SECONDS);
    assertTrue(completion.items.stream().anyMatch(c -> c.label.equals("hashCode")), "Object's members still: " + completion.items);
  }

  private List<String> symbols(CompileService compiles, String relative) throws Exception {
    List<Symbol> symbols = compiles.query(uri(relative), List.<Symbol>of(), (analyzer, path) -> analyzer.symbols(path)).get(10, TimeUnit.SECONDS);
    return symbols.stream().map(s -> s.kind + " " + s.name).collect(Collectors.toList());
  }
}
