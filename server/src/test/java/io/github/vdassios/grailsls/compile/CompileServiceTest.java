package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
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
  void start() throws IOException {
    tmp = tmp.toRealPath(); // Gradle reports real paths; on macOS the temporary directory is under the /var symlink
    src = tmp.resolve("src");
    service = new CompileService(published::add, CompileService.DEBOUNCE_MILLIS);
    service.setWorkspace(workspace());
  }

  @AfterEach
  void stop() {
    service.shutdown();
  }

  private Workspace workspace(Path... classpath) {
    return workspace(AnalysisJar.extract(tmp.resolve("cache")), (show, message) -> System.err.println(message), classpath);
  }

  private Workspace workspace(Path analysisJar, BiConsumer<Boolean, String> reporter, Path... classpath) {
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
    Workspace workspace = new Workspace(new ProjectModel(tmp, List.of(module), false), analysisJar, reporter);
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

  /** Every compile throws when the analysis layer does not load: the user is told once per module, not only in stderr. */
  @Test
  void aCompileThatThrowsIsShownOncePerContext() throws Exception {
    List<String> shown = new CopyOnWriteArrayList<>();
    List<String> logged = new CopyOnWriteArrayList<>();
    service.setWorkspace(workspace(tmp.resolve("missing-analysis.jar"), (show, message) -> (show ? shown : logged).add(message)));
    service.open(uri("A.groovy"), "class A {}\n", 1);
    service.open(uri("B.groovy"), "class B {}\n", 1);
    service.afterPendingCompiles().get(30, TimeUnit.SECONDS);
    assertEquals(1, shown.size(), shown.toString());
    assertTrue(shown.get(0).startsWith(": main: the compile of " + src.resolve("A.groovy") + " failed"), shown.get(0));
    assertTrue(shown.get(0).contains("cannot load the analysis layer in : main"), shown.get(0));
    assertEquals(1, logged.size(), "the next failure is logged: " + logged);
    assertTrue(logged.get(0).startsWith(": main: the compile of " + src.resolve("B.groovy") + " failed"), logged.get(0));
    assertNull(published.poll(1, TimeUnit.SECONDS), "no diagnostics");
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

  private List<String> found(String query) {
    return service.workspace().symbols().search(query).stream().map(f -> src.relativize(f.file) + " " + f.symbol.kind + " " + f.symbol.name)
        .sorted().collect(Collectors.toList());
  }

  private void awaitFound(String query, List<String> expected) throws InterruptedException {
    for (int i = 0; i < 300 && !found(query).equals(expected); i++) {
      Thread.sleep(100);
    }
    assertEquals(expected, found(query));
  }

  /** Filled in the background after the project resolves, then kept current by saves, creates and deletes. */
  @Test
  void theSymbolIndexFollowsTheFiles() throws Exception {
    Path names = src.resolve("lib/Names.groovy");
    Files.createDirectories(names.getParent());
    Files.writeString(names, "package lib\nclass Author {\n  String name\n  String initials() { name[0] }\n}\n");
    Files.writeString(src.resolve("Broken.groovy"), "class Broken {\n  void x( }\n");
    Workspace workspace = workspace();
    service.setWorkspace(workspace);
    workspace.indexSymbols().get(60, TimeUnit.SECONDS);
    assertEquals(List.of("lib/Names.groovy CLASS lib.Author", "lib/Names.groovy FIELD name", "lib/Names.groovy METHOD initials"), found(""));
    assertEquals(2, workspace.symbols().files(), "the file that does not parse is indexed with no symbols");

    Files.writeString(names, "package lib\nclass Writer {\n  String pen\n}\n");
    service.fileChanged(names);
    awaitFound("er", List.of("lib/Names.groovy CLASS lib.Writer"));

    Files.delete(names);
    service.fileChanged(names);
    awaitFound("", List.of());
  }

  /**
   * The parse behind the symbol index corrects the type names the text scan got wrong (a slashy string read as a
   * comment), and the open documents compile again; a file that does not parse keeps the names of the scan.
   */
  @Test
  void aParseCorrectsTheDeclarationIndex() throws Exception {
    Files.createDirectories(src.resolve("lib"));
    Files.writeString(src.resolve("lib/Globs.groovy"), "package lib\nclass Globs {\n  def glob = /\\/*/\n}\nclass Lost {}\n// */\n");
    Files.writeString(src.resolve("Broken.groovy"), "class Broken {\n  void x( }\n");
    Workspace workspace = workspace();
    service.setWorkspace(workspace);
    assertNull(workspace.declarations().fileOf("lib.Lost", List.of(src)), "the scan misses Lost");
    service.open(uri("Book.groovy"), "import lib.Lost\nclass Book { Lost lost }\n", 1);
    assertEquals(List.of("0: unable to resolve class lib.Lost", "1: unable to resolve class lib.Lost"), messages(next()));

    workspace.indexSymbols().get(60, TimeUnit.SECONDS);
    assertEquals(src.resolve("lib/Globs.groovy"), workspace.declarations().fileOf("lib.Lost", List.of(src)));
    assertEquals(src.resolve("Broken.groovy"), workspace.declarations().fileOf("Broken", List.of(src)));
    assertEquals(List.of(), messages(next()), "the open document compiled again");
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

  /** Opened through a symlink and not saved yet: the file matches the source root through its directory's real path. */
  @Test
  void aNewFileOpenedThroughASymlinkCompiles() throws Exception {
    Files.createDirectories(src);
    Path link = Files.createSymbolicLink(tmp.resolve("link"), src);
    String uri = link.resolve("lib/New.groovy").toUri().toString();
    service.open(uri, "class New {\n", 1);
    PublishDiagnosticsParams published = next();
    assertEquals(uri, published.getUri(), "published to the editor's URI");
    assertEquals(1, published.getDiagnostics().size(), messages(published).toString());
    assertEquals("class New {\n", service.documents().get(src.resolve("lib/New.groovy")).text);
  }

  @Test
  void documentsThatAreNoFilesAreIgnored() {
    for (String uri : List.of("untitled:Untitled-1", "jdt://contents/rt.jar/java.lang/String.class", "file:relative")) {
      service.open(uri, "class A {\n", 1);
      service.change(uri, List.of(new TextDocumentContentChangeEvent("class B {\n")), 2);
      service.close(uri);
    }
    assertEquals(List.of(), service.documents().all());
  }

  @Test
  void filesOutsideTheSourceRootsAreNotCompiled() throws Exception {
    service.open(tmp.resolve("build.gradle").toUri().toString(), "this is not groovy {\n", 1);
    service.open(tmp.resolve("build/tmp/Copy.groovy").toUri().toString(), "class Copy {\n", 1); // the build directory; a package named build is source
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

  /**
   * An edit cancels the compile of the text before it (which stops at its next phase), and that compile publishes
   * nothing: the first diagnostics are of the edited text. The first text is long, so its compile is still running
   * when the edit comes.
   */
  @Test
  void aCompileAnEditReplacedPublishesNothing() throws Exception {
    StringBuilder stale = new StringBuilder("class A {\n  Stale stale\n");
    for (int i = 0; i < 2000; i++) {
      stale.append("  int m").append(i).append("(int a) { a + ").append(i).append(" }\n");
    }
    service.open(uri("A.groovy"), stale.append("}\n").toString(), 1);
    service.change(uri("A.groovy"), List.of(new TextDocumentContentChangeEvent("class A {\n  Fresh fresh\n}\n")), 2);
    PublishDiagnosticsParams first = next();
    assertEquals(2, first.getVersion());
    assertEquals(1, first.getDiagnostics().size(), messages(first).toString());
    assertTrue(messages(first).get(0).contains("Fresh"), messages(first).toString());
    assertNull(published.poll(1, TimeUnit.SECONDS), "nothing from the compile of version 1");
  }

  /**
   * {@code $/cancelRequest} cancels the future a request returned: a query waiting for the module's thread never runs,
   * and a running one sees its request's cancel flag set, which stops a compile of a copy (completion, signature help).
   */
  @Test
  void aCancelledQueryIsSkippedOrStopsItsCompile() throws Exception {
    service.open(uri("A.groovy"), "class A {}\n", 1);
    assertEquals(List.of(), messages(next()));
    CountDownLatch inside = new CountDownLatch(1);
    AtomicBoolean sawCancel = new AtomicBoolean();
    CompletableFuture<Boolean> running = CompileService.mapped(service.queryText(uri("A.groovy"), false, (analyzer, request) -> {
      inside.countDown();
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (!request.cancelled.getAsBoolean() && System.nanoTime() < end) {
        Thread.onSpinWait();
      }
      sawCancel.set(request.cancelled.getAsBoolean());
      return true;
    }), answer -> answer); // as the language server maps an answer to its LSP type
    assertTrue(inside.await(10, TimeUnit.SECONDS));
    AtomicBoolean queuedRan = new AtomicBoolean();
    CompletableFuture<Object> queued = service.query(uri("A.groovy"), null, (analyzer, path) -> {
      queuedRan.set(true);
      return null;
    });
    queued.cancel(true);
    running.cancel(true);
    assertEquals(List.of("CLASS A"), symbols(service, "A.groovy"), "a later query runs after both");
    assertTrue(sawCancel.get(), "the running query's request is cancelled");
    assertFalse(queuedRan.get(), "the cancelled query waiting for the thread is skipped");
  }

  /**
   * A query on a document whose compile waits out its debounce delay, while the workspace is replaced (the old one
   * still closing), answers. It used to wait on the cancelled compile again and again until the stack overflowed.
   */
  @Test
  void aQueryDuringAWorkspaceSwapAnswers() throws Exception {
    CompileService slow = new CompileService(published::add, 60_000);
    try {
      slow.setWorkspace(workspace());
      slow.open(uri("A.groovy"), "class A {}\n", 1);
      slow.open(uri("B.groovy"), "class B {}\n", 1);
      assertEquals(List.of("CLASS A"), symbols(slow, "A.groovy"));
      assertEquals(List.of("CLASS B"), symbols(slow, "B.groovy"));
      slow.change(uri("B.groovy"), List.of(new TextDocumentContentChangeEvent("class B { int n }\n")), 2);
      // a query holds the module's thread, so closing the old workspace waits for it
      CountDownLatch holding = new CountDownLatch(1);
      CountDownLatch release = new CountDownLatch(1);
      slow.query(uri("A.groovy"), null, (analyzer, path) -> {
        holding.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return null;
      });
      assertTrue(holding.await(10, TimeUnit.SECONDS));
      Workspace old = slow.workspace();
      Thread swap = new Thread(() -> slow.setWorkspace(workspace()));
      swap.start();
      try {
        while (slow.workspace() == old) {
          Thread.sleep(10);
        }
        Thread.sleep(200); // the pending compiles are cancelled; the old workspace is closing
        symbols(slow, "B.groovy"); // empty or B's: it answers
      } finally {
        release.countDown();
        swap.join(30_000);
      }
    } finally {
      slow.shutdown();
    }
  }

  /**
   * The last diagnostics a closed document gets are the empty ones of the close, also when a compile publishes while
   * the document closes (here its diagnostics are still on their way to the client).
   */
  @Test
  void aCompileEndingDuringCloseDoesNotPublishAfterTheClear() throws Exception {
    List<PublishDiagnosticsParams> sent = new CopyOnWriteArrayList<>();
    CountDownLatch publishing = new CountDownLatch(1);
    CompileService racing = new CompileService(params -> {
      if (!params.getDiagnostics().isEmpty()) {
        publishing.countDown();
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      sent.add(params);
    }, CompileService.DEBOUNCE_MILLIS);
    try {
      racing.setWorkspace(workspace());
      racing.open(uri("Broken.groovy"), "class Broken { void x( }\n", 1);
      assertTrue(publishing.await(30, TimeUnit.SECONDS));
      racing.close(uri("Broken.groovy"));
      long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      while (sent.size() < 2 && System.nanoTime() < end) {
        Thread.sleep(10);
      }
      assertEquals(2, sent.size());
      assertEquals(List.of(), sent.get(1).getDiagnostics(), "the clear comes last");
    } finally {
      racing.shutdown();
    }
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

  /**
   * An extension module of the project's main source set (descriptor in its resources, class in its output): Gradle
   * puts main's output on the test compile classpath, so statically compiled tests call its methods. Seen in the
   * target project: without it the call fails and every value it gives is typed {@code Object}.
   */
  @Test
  void staticallyCompiledTestsSeeTheExtensionMethodsOfMain() throws Exception {
    Path sources = Files.createDirectories(tmp.resolve("ext-src/ext"));
    Files.writeString(sources.resolve("Shout.java"), "package ext;\npublic class Shout { public static String shout(String self) { return self.toUpperCase(); } }\n");
    Path classes = tmp.resolve("build/classes/groovy/main");
    assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), sources.resolve("Shout.java").toString()));
    Path resources = tmp.resolve("resources");
    Path descriptor = resources.resolve("META-INF/groovy/org.codehaus.groovy.runtime.ExtensionModule");
    Files.createDirectories(descriptor.getParent());
    Files.writeString(descriptor, "moduleName=shout\nmoduleVersion=1.0\nextensionClasses=ext.Shout, ext.Missing\n");

    SourceSetRecord main = new SourceSetRecord();
    main.name = "main";
    main.sourceRoots.add(src.toString());
    main.resourceRoots.add(resources.toString());
    main.compileClasspath.add(System.getProperty("gls.testGroovy"));
    main.classesDirs.add(classes.toString());
    SourceSetRecord test = new SourceSetRecord();
    test.name = "test";
    Path testSrc = Files.createDirectories(tmp.resolve("test"));
    test.sourceRoots.add(testSrc.toString());
    // as Gradle lists it: main's output, which the loader leaves out (build directory)
    test.compileClasspath.addAll(List.of(System.getProperty("gls.testGroovy"), classes.toString(), tmp.resolve("build/resources/main").toString()));
    ModuleRecord module = new ModuleRecord();
    module.path = ":";
    module.projectDir = tmp.toString();
    module.groovyVersion = "2.5.23";
    module.sourceSets.addAll(List.of(main, test));
    Workspace workspace = new Workspace(new ProjectModel(tmp, List.of(module), false), AnalysisJar.extract(tmp.resolve("cache")));
    workspace.index();
    service.setWorkspace(workspace);

    String text = "@groovy.transform.CompileStatic\nclass T {\n  int run() { 'a'.shout().size() }\n}\n";
    service.open(testSrc.resolve("T.groovy").toUri().toString(), text, 1);
    assertEquals(List.of(), messages(next()), "ext.Missing has no class file: skipped, the rest of the module still loads");
    // as in Gradle, main does not see its own extension module
    service.open(uri("M.groovy"), text.replace("class T", "class M"), 1);
    List<String> mainErrors = messages(next());
    assertEquals(1, mainErrors.size(), mainErrors.toString());
    assertTrue(mainErrors.get(0).contains("shout"), mainErrors.toString());
  }

  private List<String> symbols(CompileService compiles, String relative) throws Exception {
    List<Symbol> symbols = compiles.query(uri(relative), List.<Symbol>of(), (analyzer, path) -> analyzer.symbols(path)).get(10, TimeUnit.SECONDS);
    return symbols.stream().map(s -> s.kind + " " + s.name).collect(Collectors.toList());
  }
}
