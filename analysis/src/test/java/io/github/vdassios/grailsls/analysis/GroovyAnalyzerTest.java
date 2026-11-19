package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import groovy.lang.GroovyClassLoader;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.CompletionDetail;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SignatureHelp;
import io.github.vdassios.grailsls.api.SourceLookup;
import io.github.vdassios.grailsls.api.Symbol;

/** Runs on Groovy 2.5 ({@code test}) and Groovy 4 ({@code testGroovy4}). */
class GroovyAnalyzerTest {
  @TempDir
  Path tmp;

  private final GroovyAnalyzer analyzer = new GroovyAnalyzer();
  /** The declaration index, as the server would build it. */
  private final Map<String, String> index = new HashMap<>();
  private final Map<String, String> open = new HashMap<>();
  private final SourceLookup lookup = new SourceLookup() {
    @Override
    public String fileOf(String className) {
      return index.get(className);
    }

    @Override
    public String openText(String path) {
      return open.get(path);
    }

    @Override
    public List<Symbol> types() {
      return types;
    }

    @Override
    public String classSource(String className) {
      return classSources.get(className);
    }

    @Override
    public List<String> classpathPackages(String parent) {
      return jarPackages.getOrDefault(parent, List.of());
    }

    @Override
    public List<String> classpathClasses(String packageName) {
      return jarClasses.getOrDefault(packageName, List.of());
    }
  };
  /** The jars' packages below a package and classes in a package, as the server would list them. */
  private final Map<String, List<String>> jarPackages = new HashMap<>();
  private final Map<String, List<String>> jarClasses = new HashMap<>();
  /** Source files of classes that come from class files, as the server would find them in sources jars. */
  private final Map<String, String> classSources = new HashMap<>();
  /** The symbol index's classes, as the server would give them. */
  private final List<Symbol> types = new ArrayList<>();

  @AfterEach
  void close() {
    analyzer.close();
  }

  private String write(String relative, String text) throws IOException {
    Path file = tmp.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    return file.toString();
  }

  private CompileResult compile(String path, String text) {
    return analyzer.compile(new CompileRequest(path, text, lookup, () -> false));
  }

  private static List<String> errors(CompileResult result) {
    return result.diagnostics.stream().filter(d -> d.severity == Diagnostic.ERROR).map(Diagnostic::toString).collect(Collectors.toList());
  }

  @Test
  void validDocumentHasNoDiagnostics() {
    CompileResult result = compile(tmp.resolve("A.groovy").toString(), "class A { String name\n int size() { name.size() } }\n");
    assertEquals(List.of(), errors(result));
    assertFalse(result.cancelled);
    assertEquals(1, result.sources);
  }

  @Test
  void syntaxErrorHasItsPosition() {
    String path = tmp.resolve("A.groovy").toString();
    CompileResult result = compile(path, "class A {\n  void run() {\n    def x = \n  }\n}\n");
    assertEquals(1, result.diagnostics.size(), result.diagnostics.toString());
    Diagnostic error = result.diagnostics.get(0);
    assertEquals(path, error.path);
    assertEquals(Diagnostic.ERROR, error.severity);
    assertTrue(error.startLine >= 2 && error.startLine <= 3, error.toString());
    assertFalse(error.message.contains("@ line"), "the message without Groovy's position suffix: " + error.message);
  }

  @Test
  void aNameWithoutAClassFileIsNotLoadedAsAClass() {
    List<String> loaded = java.util.Collections.synchronizedList(new ArrayList<>());
    ClassLoader recording = new ClassLoader(GroovyAnalyzer.class.getClassLoader()) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        loaded.add(name);
        return super.loadClass(name, resolve);
      }
    };
    GroovyAnalyzer recorded = new GroovyAnalyzer(recording);
    try {
      CompileResult result = recorded.compile(new CompileRequest(tmp.resolve("p/A.groovy").toString(), "package p\nclass A { Missing m\n List<String> names }\n", lookup, () -> false));
      assertEquals(1, errors(result).size(), errors(result).toString());
      assertTrue(errors(result).get(0).contains("unable to resolve class Missing"), errors(result).toString());
      assertEquals(List.of(), loaded.stream().filter(name -> name.endsWith("Missing")).collect(Collectors.toList()),
          "each candidate name of Missing was found to have no class file; loading it would search the classpath again");
    } finally {
      recorded.close();
    }
  }

  @Test
  void unresolvedClassIsReportedAndLaterPhasesStillRun() {
    String path = tmp.resolve("A.groovy").toString();
    CompileResult result = compile(path, "@groovy.transform.ToString\nclass A { Missing m\n String name }\n");
    assertEquals(1, errors(result).size(), errors(result).toString());
    assertTrue(errors(result).get(0).contains("unable to resolve class Missing"));
    // canonicalization ran: @ToString added its method
    assertTrue(analyzer.compiledClass(path, "A").methods.contains("toString"));
  }

  @Test
  void classesInAFileOfAnotherNameResolveThroughTheIndex() throws IOException {
    String formatting = write("src/p/Formatting.groovy", "package p\nclass TitleCase { static String of(String s) { s } }\n");
    String document = tmp.resolve("src/q/User.groovy").toString();
    String text = "package q\nimport p.TitleCase\nclass User { String t = TitleCase.of('x') }\n";

    List<String> withoutIndex = errors(compile(document, text));
    assertTrue(withoutIndex.stream().anyMatch(e -> e.contains("unable to resolve class p.TitleCase")), "control: Groovy's own lookup finds p/TitleCase.groovy only");
    index.put("p.TitleCase", formatting);
    CompileResult result = compile(document, text);
    assertEquals(List.of(), errors(result));
    assertEquals(2, result.sources);
  }

  @Test
  void aPackageThatDiffersFromItsDirectoryResolves() throws IOException {
    index.put("storage.Shelf", write("src/misc/Shelf.groovy", "package storage\nclass Shelf { String label }\n"));
    CompileResult result = compile(tmp.resolve("src/q/User.groovy").toString(), "package q\nimport storage.Shelf\nclass User { Shelf shelf }\n");
    assertEquals(List.of(), errors(result));
  }

  @Test
  void nestedTypesResolveThroughTheIndex() throws IOException {
    String book = write("src/lib/Book.groovy", "package lib\nclass Book {\n  enum Format { HARDCOVER, EBOOK }\n  Format format\n}\n");
    index.put("lib.Book", book);
    index.put("lib.Book$Format", book);
    String document = tmp.resolve("src/web/Shop.groovy").toString();
    CompileResult result = compile(document, "package web\nimport lib.Book\nclass Shop { Book.Format format = Book.Format.EBOOK }\n");
    assertEquals(List.of(), errors(result));
    assertEquals(2, result.sources, "Book.groovy added once");
  }

  @Test
  void pulledInSourcesKeepTheirFilePath() throws IOException {
    String author = write("src/lib/Author.groovy", "package lib\nclass Author { String name }\n");
    index.put("lib.Author", author);
    String document = tmp.resolve("src/lib/Book.groovy").toString();
    compile(document, "package lib\nclass Book { Author author }\n");
    CompiledClass compiled = analyzer.compiledClass(document, "lib.Author");
    assertNotNull(compiled);
    assertEquals(author, compiled.sourcePath, "a file path, not a file: URL (Grails decides artefacts from it)");
    assertEquals(document, analyzer.compiledClass(document, "lib.Book").sourcePath);
  }

  /** Groovy's static cache of parameterized types keeps class nodes, and with them the whole compile, in its keys. */
  @Test
  void compilesLeaveNoParameterizedTypeInGroovysCache() throws Exception {
    String document = tmp.resolve("src/lib/Shelf.groovy").toString();
    assertEquals(List.of(), errors(compile(document, "package lib\n@groovy.transform.CompileStatic\nclass Shelf<T extends Comparable<T>> {\n"
        + "  List<T> items = new ArrayList<T>()\n  Map<String, List<T>> byName(Map<String, T> named) {\n"
        + "    named.collectEntries { String k, T v -> [(k): [v]] } as Map<String, List<T>>\n  }\n"
        + "  T first() { items.sort { T a, T b -> a <=> b }.first() }\n}\n")));
    Field field = Class.forName("org.codehaus.groovy.ast.tools.GenericsUtils").getDeclaredField("PARAMETERIZED_TYPE_CACHE");
    field.setAccessible(true);
    assertTrue(((Map<?, ?>) field.get(null)).isEmpty());
  }

  /**
   * Micronaut 4's global transformation reads annotation members through the annotation type's methods; Grails 7's
   * {@code @GrailsCompileStatic(TypeCheckingMode.SKIP)} is an alias with none until Groovy expands it, after the global
   * transformations of the same phase. The server runs that transformation after the expansion.
   */
  @Test
  void aGlobalTransformThatReadsAliasMembersRunsAfterTheAliasIsExpanded() throws Exception {
    Path services = tmp.resolve("services");
    Files.writeString(services, io.micronaut.ast.groovy.TypeElementVisitorTransform.class.getName() + "\n");
    String name = "META-INF/services/org.codehaus.groovy.transform.ASTTransformation";
    ClassLoader registering = new ClassLoader(GroovyAnalyzer.class.getClassLoader()) {
      @Override
      protected Enumeration<URL> findResources(String resource) throws IOException {
        return resource.equals(name) ? Collections.enumeration(List.of(services.toUri().toURL())) : super.findResources(resource);
      }
    };
    String source = "import groovy.transform.AnnotationCollector\nimport groovy.transform.CompileStatic\nimport groovy.transform.TypeCheckingMode\n"
        + "@AnnotationCollector([CompileStatic])\n@interface Fast {}\n"
        + "class A {\n  @Fast(TypeCheckingMode.SKIP)\n  def b(x) { x.foo }\n}\n";
    GroovyAnalyzer registered = new GroovyAnalyzer(registering);
    try {
      int before = io.micronaut.ast.groovy.TypeElementVisitorTransform.METHODS_READ.get();
      CompileResult result = registered.compile(new CompileRequest(tmp.resolve("A.groovy").toString(), source, lookup, () -> false));
      assertEquals(List.of(), errors(result));
      assertTrue(io.micronaut.ast.groovy.TypeElementVisitorTransform.METHODS_READ.get() > before, "the transformation ran");
    } finally {
      registered.close();
    }
  }

  /** Groovy 3 and 4 empty the parser's prediction cache after every 64 parsed files: mid-compile for a large closure. */
  @Test
  void theParserCacheOutlivesManyParsedFiles() {
    ParserCache cache = new ParserCache(GroovyAnalyzer.class.getClassLoader());
    compile(tmp.resolve("src/lib/Rich.groovy").toString(), "package lib\nclass Rich {\n  Map<String, List<Integer>> byName = [:]\n"
        + "  int total(String name) {\n    int sum = 0\n    for (int n in byName[name] ?: []) { if (n > 0) { sum += n } else { sum -= 1 } }\n"
        + "    try { return byName.values().flatten().findAll { it != null }.size() + sum } finally { byName.clear() }\n  }\n}\n");
    int afterRich = cache.states();
    for (int i = 0; i < 70; i++) {
      compile(tmp.resolve("src/lib/Plain" + i + ".groovy").toString(), "package lib\nclass Plain" + i + " {}\n");
    }
    assertTrue(cache.states() >= afterRich, cache.states() + " states left of " + afterRich);
  }

  /** The server empties the cache itself, between compiles, once it is over a limit. */
  @Test
  void aParserCacheOverItsLimitIsEmptied() {
    compile(tmp.resolve("src/lib/Shelf.groovy").toString(), "package lib\nclass Shelf {\n  List<String> items = []\n}\n");
    ClassLoader loader = GroovyAnalyzer.class.getClassLoader();
    ParserCache cache = new ParserCache(loader);
    int filled = cache.states();
    new ParserCache(loader, Integer.MAX_VALUE).trim();
    assertEquals(filled, cache.states());
    new ParserCache(loader, 1).trim();
    assertTrue(cache.states() <= 0, cache.states() + " states left"); // -1: Groovy 2.5 has no such cache
  }

  /**
   * Groovy checks type argument bounds before the trait transformation turns a trait's {@code extends} into
   * {@code implements}: against compiled traits the check passes, with the traits pulled in as sources it failed.
   */
  @Test
  void aTypeArgumentMeetsABoundThroughATraitThatExtendsATrait() throws IOException {
    index.put("lib.Diffable", write("src/lib/Diffable.groovy", "package lib\ntrait Diffable<T extends Diffable> {}\n"));
    index.put("lib.Catalogued", write("src/lib/Catalogued.groovy", "package lib\ntrait Catalogued<D extends Diffable> implements Diffable<D> {}\n"));
    index.put("lib.Item", write("src/lib/Item.groovy", "package lib\ntrait Item<D extends Diffable> extends Catalogued<D> {}\n"));
    String document = tmp.resolve("src/lib/Part.groovy").toString();
    CompileResult result = compile(document, "package lib\nclass Part implements Item<Part> {\n  List<Item<Part>> parts\n}\n");
    assertEquals(List.of(), errors(result));
    assertEquals(4, result.sources);
  }

  /**
   * Groovy 4 restarts resolving when a type names a source not yet in the unit, as each one the index adds does. A
   * restart from inside a declaration's type left the resolver checking the type of every variable the next class
   * reads, and a property typed by a superclass's type parameter failed (rundeck: "unable to resolve class ID").
   */
  @Test
  void aSourceAddedForADeclarationsTypeLeavesOtherClassesVariablesUnchecked() throws IOException {
    index.put("p.Helper", write("src/p/Helper.groovy", "package p\nclass Helper {\n  def run() { q.Other other = null }\n}\n"));
    index.put("q.Other", write("src/q/Other.groovy", "package q\nclass Other {}\n"));
    String document = tmp.resolve("src/p/Entry.groovy").toString();
    CompileResult result = compile(document, "package p\nclass Entry extends AbstractMap.SimpleEntry<String, String> {\n  Entry() { super('k', 'v') }\n"
        + "  Helper helper\n  int length() { key.length() }\n}\n");
    assertEquals(List.of(), errors(result));
    assertEquals(3, result.sources);
  }

  /**
   * A class file's member types resolve through Groovy's reader of class files, which throws {@code NoClassDefFoundError}
   * for a class whose source is added but not converted yet. Here the static import reads {@code Module}'s methods
   * before anything else named {@code Settings} (rundeck: a spec's Spock transformation read the methods of a built
   * Java class that returns a service; "internal compiler error in phase 4").
   */
  @Test
  void aClassFileMayReferToAClassStillInSource() throws Exception {
    Path java = Files.createDirectories(tmp.resolve("java/app"));
    Files.writeString(java.resolve("Module.java"), "package app;\npublic class Module {\n  public static Settings settings() { return null; }\n"
        + "  public static int port() { return 1; }\n}\n");
    Files.writeString(java.resolve("Settings.java"), "package app;\npublic class Settings {}\n");
    Path classes = tmp.resolve("classes");
    assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
        java.resolve("Module.java").toString(), java.resolve("Settings.java").toString()));
    Files.delete(classes.resolve("app/Settings.class"));
    index.put("app.Settings", write("src/app/Settings.groovy", "package app\nclass Settings {\n  String host\n}\n"));

    try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new URL[] { classes.toUri().toURL() }, getClass().getClassLoader())) {
      GroovyAnalyzer built = new GroovyAnalyzer(loader);
      try {
        String document = tmp.resolve("src/app/Entry.groovy").toString();
        String text = "package app\nimport static app.Module.port\nclass Entry {\n  int run() { port() }\n  def host() { Module.settings().host }\n}\n";
        CompileResult result = built.compile(new CompileRequest(document, text, lookup, () -> false));
        assertEquals(List.of(), errors(result));
        assertEquals(2, result.sources);
        org.codehaus.groovy.control.CompilationUnit unit = built.unit(document);
        MethodNode settings = unit.getClassNodeResolver().resolveName("app.Module", unit).getClassNode().getMethods("settings").get(0);
        assertEquals(List.of("host"), settings.getReturnType().getFields().stream().map(f -> f.getName()).collect(Collectors.toList()),
            "the class from source");
      } finally {
        built.close();
      }
    }
  }

  /** A bound that does not hold stays an error. */
  @Test
  void aTypeArgumentOutsideItsBoundIsStillAnError() throws IOException {
    index.put("lib.Diffable", write("src/lib/Diffable.groovy", "package lib\ntrait Diffable<T extends Diffable> {}\n"));
    index.put("lib.Item", write("src/lib/Item.groovy", "package lib\ntrait Item<D extends Diffable> extends Diffable<D> {}\n"));
    String document = tmp.resolve("src/lib/Part.groovy").toString();
    List<String> errors = errors(compile(document, "package lib\nclass Part implements Item<String> {}\n"));
    assertEquals(1, errors.size(), errors.toString());
    assertTrue(errors.get(0).contains("The type String is not a valid substitute for the bounded parameter"), errors.toString());
  }

  @Test
  void keptCompilesHoldNoParseTree() throws IOException {
    String author = write("src/lib/Author.groovy", "package lib\nclass Author {\n  String name\n  Author(String name) { this.name = name }\n"
        + "  String greet(String other) { [other].collect { String s -> s + name }.join() }\n}\n");
    index.put("lib.Author", author);
    String document = tmp.resolve("src/lib/Book.groovy").toString();
    compile(document, "package lib\nclass Book {\n  Closure sort = { a, b -> a <=> b }\n  Author author(String name) { new Author(name) }\n"
        + "  Runnable task(int n) { new Runnable() { void run() { [n].each { int i -> i } } } }\n}\n");
    List<Parameter> parameters = new ArrayList<>();
    for (ModuleNode module : analyzer.unit(document).getAST().getModules()) {
      for (ClassNode type : module.getClasses()) {
        new ClassCodeVisitorSupport() {
          @Override
          protected SourceUnit getSourceUnit() {
            return module.getContext();
          }

          @Override
          protected void visitConstructorOrMethod(MethodNode node, boolean isConstructor) {
            parameters.addAll(List.of(node.getParameters()));
            super.visitConstructorOrMethod(node, isConstructor);
          }

          @Override
          public void visitClosureExpression(ClosureExpression expression) {
            parameters.addAll(List.of(expression.getParameters()));
            super.visitClosureExpression(expression);
          }
        }.visitClass(type);
      }
    }
    assertTrue(parameters.size() >= 7, "parameters found: " + parameters.size());
    // Groovy 4 and 5 put each parameter's parse tree context, whose parents hold the file's whole tree, on the node
    for (Parameter parameter : parameters) {
      assertNull(parameter.getNodeMetaData("_PARAMETER_CONTEXT"), parameter.getName());
      assertNull(parameter.getNodeMetaData("_PARAMETER_MODIFIER_MANAGER"), parameter.getName());
    }
  }

  @Test
  void anOpenDocumentIsReadFromItsTextNotTheFile() throws IOException {
    String author = write("src/lib/Author.groovy", "package lib\nclass Author { String name }\n");
    index.put("lib.Author", author);
    open.put(author, "package lib\nclass Author { String name\n String initials() { name[0] } }\n");
    String document = tmp.resolve("src/lib/Book.groovy").toString();
    assertEquals(List.of(), errors(compile(document, "package lib\nclass Book { Author author\n def i() { author.initials() } }\n")));
    assertTrue(analyzer.compiledClass(document, "lib.Author").methods.contains("initials"));
  }

  @Test
  void theDocumentsOwnClassesAreNotAddedAgain() {
    String document = tmp.resolve("src/lib/Book.groovy").toString();
    index.put("lib.Book", document);
    index.put("lib.Book$Format", document);
    CompileResult result = compile(document, "package lib\nclass Book {\n enum Format { A }\n Format f\n Book other\n}\n");
    assertEquals(List.of(), errors(result));
    assertEquals(1, result.sources);
  }

  @Test
  void syntaxErrorInOneFileLeavesAnotherFileClean() throws IOException {
    String broken = write("src/Broken.groovy", "class Broken { void x( }\n");
    assertEquals(1, errors(compile(broken, Files.readString(Path.of(broken)))).size());
    String other = tmp.resolve("src/Other.groovy").toString();
    assertEquals(List.of(), errors(compile(other, "class Other { int n }\n")));
  }

  @Test
  void astTestClosuresDoNotRun() throws Exception {
    Path marker = tmp.resolve("marker");
    String source = "import groovy.transform.ASTTest\n@ASTTest(value = { new File('" + marker + "').text = 'ran' })\nclass A {}\n";
    compile(tmp.resolve("A.groovy").toString(), source);
    assertFalse(Files.exists(marker), "@ASTTest ran in the server");

    try (GroovyClassLoader plain = new GroovyClassLoader()) {
      plain.parseClass(source);
    }
    assertTrue(Files.exists(marker), "control: a plain compile runs it");
  }

  @Test
  void grapeIsDisabled() {
    CompileResult result = compile(tmp.resolve("A.groovy").toString(), "@Grab('org.example:nothing:1.0')\nimport groovy.lang.Grab\nclass A {}\n");
    assertEquals(List.of(), errors(result));
    assertEquals("false", System.getProperty("groovy.grape.enable"));
  }

  @Test
  void aCancelledCompileStopsAndKeepsNothing() {
    String path = tmp.resolve("A.groovy").toString();
    CompileResult result = analyzer.compile(new CompileRequest(path, "class A {}\n", lookup, () -> true));
    assertTrue(result.cancelled);
    assertNull(analyzer.compiledClass(path, "A"));
  }

  @Test
  void forgetDropsTheKeptCompile() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {}\n");
    assertNotNull(analyzer.compiledClass(path, "A"));
    analyzer.forget(path);
    assertNull(analyzer.compiledClass(path, "A"));
  }

  @Test
  void definitionOfAClassPulledInFromSourceIsItsFile() throws IOException {
    String book = write("src/lib/Book.groovy", "package lib\n\nclass Book {\n  String title\n}\n");
    index.put("lib.Book", book);
    String path = tmp.resolve("src/A.groovy").toString();
    compile(path, "import lib.Book\nclass A {\n  def run() { Book.newInstance() }\n}\n");
    Location location = analyzer.definition(path, 2, 15); // on Book
    assertNotNull(location);
    assertEquals(book, location.path);
    assertEquals(2, location.range.startLine);
  }

  @Test
  void definitionOfAMemberOfAnOpenUnsavedSourceIsThatFile() {
    String book = tmp.resolve("src/Book.groovy").toString(); // not on disk
    index.put("Book", book);
    open.put(book, "class Book {\n  String title() { 'x' }\n}\n");
    String path = tmp.resolve("src/A.groovy").toString();
    compile(path, "class A {\n  def run(Book b) { b.title() }\n}\n");
    Location location = analyzer.definition(path, 1, 23); // on title
    assertNotNull(location);
    assertEquals(book, location.path);
    assertEquals(1, location.range.startLine);
  }

  @Test
  void definitionFromAnImportIsTheImportedClass() throws IOException {
    String shelf = write("src/misc/Shelf.groovy", "package storage\n\nclass Shelf {\n  Shelf(String label) {}\n}\n");
    index.put("storage.Shelf", shelf);
    String path = tmp.resolve("src/A.groovy").toString();
    compile(path, "import storage.Shelf\nclass A {\n  def run() { new Shelf('x') }\n}\n");
    Location location = analyzer.definition(path, 0, 15); // on Shelf
    assertNotNull(location);
    assertEquals(shelf, location.path);
    assertEquals(2, location.range.startLine);
  }

  @Test
  void definitionOfALocalVariableIsInTheDocument() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  def run() {\n    def count = 1\n    count + 1\n  }\n}\n");
    Location location = analyzer.definition(path, 3, 5);
    assertNotNull(location);
    assertEquals(path, location.path);
    assertEquals(2, location.range.startLine);
  }

  /** {@code new P()}: the constructor when the class declares it, else the class (no source for a default one). */
  @Test
  void definitionOfAConstructorCallWithoutADeclaredConstructorIsTheClass() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class P {}\nclass Q {\n  Q(int n) {}\n}\nclass A {\n  def run() { [new P(), new Q(1)] }\n}\n");
    Location p = analyzer.definition(path, 5, 18); // on P
    assertNotNull(p);
    assertEquals(0, p.range.startLine);
    Location q = analyzer.definition(path, 5, 27); // on Q
    assertNotNull(q);
    assertEquals(2, q.range.startLine);
  }

  @Test
  void definitionOfAJdkMethodIsNull() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  def run() { 'x'.toUpperCase() }\n}\n");
    assertNull(analyzer.definition(path, 1, 19)); // on toUpperCase
  }

  /** A class read from a class file: its source file, when the lookup has one, and the comment there. */
  @Test
  void definitionAndHoverOfAJdkMemberUseItsSourceFile() throws IOException {
    String source = write("jdk/java/lang/String.java", "package java.lang;\n\n/** A string. */\npublic final class String {\n"
        + "  /**\n   * Upper case, by the rules of {@code locale}.\n   * @param locale the rules\n   */\n  public String toUpperCase(Locale locale) { return this; }\n\n"
        + "  /** Upper case, by the default locale's rules. */\n  public String toUpperCase() {\n    return toUpperCase(Locale.getDefault());\n  }\n}\n");
    classSources.put("java.lang.String", source);
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  def run() { 'x'.toUpperCase() }\n  def all() { String.valueOf(1) }\n}\n");

    Location method = analyzer.definition(path, 1, 19); // on toUpperCase
    assertNotNull(method);
    assertEquals(source + ":11:16", method.path + ":" + method.range.startLine + ":" + method.range.startColumn);
    String hover = analyzer.hover(path, 1, 19);
    assertTrue(hover.endsWith("---\n\nUpper case, by the default locale's rules."), hover);

    Location type = analyzer.definition(path, 2, 15); // on String
    assertNotNull(type);
    assertEquals(source + ":3", type.path + ":" + type.range.startLine);
    Location missing = analyzer.definition(path, 2, 22); // valueOf: not in that file, so its class
    assertNotNull(missing);
    assertEquals(3, missing.range.startLine);
  }

  /** Grails's render and save: the class of the compile has only a generated bridge; the trait's source declares it. */
  @Test
  void definitionOfAMethodFromACompiledTraitIsInTheTraitsSource() throws Exception {
    String trait = write("lib/lib/Greets.groovy", "package lib\n\ntrait Greets {\n  /** Says hello. */\n  String greet(String name) { 'hello ' + name }\n}\n");
    Path classes = tmp.resolve("classes");
    org.codehaus.groovy.control.CompilerConfiguration config = new org.codehaus.groovy.control.CompilerConfiguration();
    config.setTargetDirectory(classes.toFile());
    org.codehaus.groovy.control.CompilationUnit compiled = new org.codehaus.groovy.control.CompilationUnit(config);
    compiled.addSource(new java.io.File(trait));
    compiled.compile();
    classSources.put("lib.Greets", trait);

    try (java.net.URLClassLoader loader = new java.net.URLClassLoader(new URL[] { classes.toUri().toURL() }, getClass().getClassLoader())) {
      GroovyAnalyzer built = new GroovyAnalyzer(loader);
      try {
        String document = tmp.resolve("src/A.groovy").toString();
        CompileResult result = built.compile(new CompileRequest(document, "class A implements lib.Greets {\n  def run() { greet('x') }\n}\n", lookup, () -> false));
        assertEquals(List.of(), errors(result));
        Location location = built.definition(document, 1, 15); // on greet
        assertNotNull(location);
        assertEquals(trait + ":4", location.path + ":" + location.range.startLine);
        assertTrue(built.hover(document, 1, 15).endsWith("Says hello."), built.hover(document, 1, 15));
      } finally {
        built.close();
      }
    }
  }

  @Test
  void hoverShowsTheSignatureAndTheDocumentationComment() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  /** Counts things. */\n  int count(String what) { 1 }\n  def run() { count('x') }\n}\n");
    String hover = analyzer.hover(path, 3, 15);
    assertNotNull(hover);
    assertTrue(hover.startsWith("```groovy\npublic int count(String what)\n```"), hover);
    // Groovy 3 and later attach the comment to the node; on 2.5 it is read from the document's text
    assertTrue(hover.endsWith("---\n\nCounts things."), hover);
  }

  /** The comment is read from the text each source was compiled from: a pulled-in file's, and the open document's own. */
  @Test
  void documentationOfProjectDeclarationsIsReadOnEveryGroovy() throws IOException {
    index.put("app.Tally", write("src/app/Tally.groovy", "package app\n\n/** Keeps a tally. */\nclass Tally {\n  /** How many so far. */\n  int total\n\n"
        + "  /**\n   * Adds to the tally.\n   * @param amount how much\n   */\n  @Deprecated\n  void add(int amount) { total += amount }\n  void reset() {}\n}\n"));
    String path = tmp.resolve("src/app/A.groovy").toString();
    String text = "package app\n\nclass A {\n  /** Not saved yet. */\n  int fresh() { 1 }\n  def run(Tally tally) {\n    tally.add(1)\n    tally.reset()\n    tally.total\n    fresh()\n    Tally.name\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
    assertTrue(analyzer.hover(path, 10, 5).endsWith("---\n\nKeeps a tally."), analyzer.hover(path, 10, 5)); // Tally
    assertTrue(analyzer.hover(path, 6, 11).contains("---\n\nAdds to the tally."), analyzer.hover(path, 6, 11)); // add
    assertFalse(analyzer.hover(path, 7, 11).contains("---"), analyzer.hover(path, 7, 11)); // reset has no comment
    assertTrue(analyzer.hover(path, 8, 11).endsWith("---\n\nHow many so far."), analyzer.hover(path, 8, 11)); // total
    assertTrue(analyzer.hover(path, 9, 5).endsWith("---\n\nNot saved yet."), analyzer.hover(path, 9, 5)); // fresh
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 6, 14);
    assertTrue(help.signatures.get(0).documentation.startsWith("Adds to the tally."), help.signatures.get(0).documentation);
  }

  /** A trait of the project: the class has only a generated bridge without a position, the trait's source declares the method. */
  @Test
  void aMethodFromATraitOfTheProjectIsFoundInTheTraitsSource() throws IOException {
    String trait = write("src/app/Greets.groovy", "package app\n\ntrait Greets {\n  /** Who is greeted. */\n  String greeted\n\n  /** Says hello. */\n  String greet(String name) { 'hello ' + name }\n}\n");
    index.put("app.Greets", trait);
    String path = tmp.resolve("src/app/A.groovy").toString();
    String text = "package app\n\nclass A implements Greets {\n  def run() {\n    greet('x')\n    getGreeted()\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
    Location method = analyzer.definition(path, 4, 6); // on greet
    assertNotNull(method);
    assertEquals(trait + ":7:9", method.path + ":" + method.range.startLine + ":" + method.range.startColumn);
    assertTrue(analyzer.hover(path, 4, 6).endsWith("---\n\nSays hello."), analyzer.hover(path, 4, 6));
    Location getter = analyzer.definition(path, 5, 6); // on getGreeted: the property
    assertNotNull(getter);
    assertEquals(trait + ":4:9", getter.path + ":" + getter.range.startLine + ":" + getter.range.startColumn);
    assertTrue(analyzer.hover(path, 5, 6).endsWith("---\n\nWho is greeted."), analyzer.hover(path, 5, 6));
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 4, 11);
    assertEquals("Says hello.", help.signatures.get(0).documentation);
  }

  /** Completion resolve and signature help read the same comment as hover for a member that comes from a class file. */
  @Test
  void resolveAndSignatureHelpShowTheCommentOfAClassFilesMember() throws IOException {
    classSources.put("java.lang.String", write("jdk/java/lang/String.java", "package java.lang;\n\npublic final class String {\n"
        + "  /** Whether it starts with {@code prefix}. */\n  public boolean startsWith(String prefix) { return true; }\n\n"
        + "  /** The same, from an offset. */\n  public boolean startsWith(String prefix, int offset) { return true; }\n}\n"));
    String path = tmp.resolve("A.groovy").toString();
    String text = "class A {\n  def run(String s) {\n    s.startsWith('x')\n    s.startsW\n  }\n}\n";
    CompletionList list = complete(path, text, 3, 13);
    Completion startsWith = list.items.stream().filter(c -> c.label.equals("startsWith")).findFirst().orElseThrow();
    String documentation = analyzer.resolveCompletion(path, startsWith.id).documentation;
    assertTrue(documentation != null && (documentation.startsWith("Whether it starts with") || documentation.startsWith("The same, from an offset.")), documentation);
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text.replace("    s.startsW\n", ""), lookup, () -> false), 2, 18);
    List<String> documented = new ArrayList<>();
    for (SignatureHelp.Signature signature : help.signatures) {
      documented.add(signature.label + ": " + signature.documentation);
    }
    assertEquals(2, documented.size(), documented.toString());
    assertTrue(documented.contains("public boolean startsWith(String arg0): Whether it starts with `prefix`."), documented.toString());
    assertTrue(documented.contains("public boolean startsWith(String arg0, int arg1): The same, from an offset."), documented.toString());
  }

  /** Grails's idiom: render(view: 'x'), save(flush: true). Named arguments arrive as one map. */
  @Test
  void hoverOnACallWithNamedArgumentsPicksTheMapOverload() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  void show(String text) {}\n  void show(Map options, Closure body) {}\n  void show(Map options) {}\n"
        + "  def run() { show(view: 'x') }\n}\n");
    String hover = analyzer.hover(path, 4, 15);
    assertNotNull(hover);
    assertEquals("public void show(Map options)", hover.split("\n")[1]);
  }

  /**
   * Groovy 2.5 parses {@code this} to one shared node, also used by the {@code previous()} and {@code next()} it
   * generates for every enum (and, in a Grails controller, by every implicit-this call in an action). Its parent in
   * the node lookup is wherever it was visited last: here the enum after the class.
   */
  @Test
  void anImplicitThisCallResolvesInItsOwnClassWhenAnEnumFollows() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  int count() { 1 }\n  def run() { this.count() }\n}\nenum Kind { ONE }\n");
    String hover = analyzer.hover(path, 2, 20);
    assertNotNull(hover);
    assertEquals("public int count()", hover.split("\n")[1]);
    Location definition = analyzer.definition(path, 2, 20);
    assertNotNull(definition);
    assertEquals(1, definition.range.startLine);
  }

  @Test
  void symbolsAreTheDocumentsDeclarationsOnly() throws IOException {
    String other = write("src/Other.groovy", "class Other { String x }\n");
    index.put("Other", other);
    String path = tmp.resolve("src/A.groovy").toString();
    compile(path, "class A {\n  String name\n  Other other\n  void run() {}\n  enum Kind { ONE }\n}\n");
    List<String> symbols = analyzer.symbols(path).stream().map(s -> s.kind + " " + s.container + " " + s.name).collect(Collectors.toList());
    assertEquals(List.of("CLASS null A", "FIELD A name", "FIELD A other", "METHOD A run", "ENUM null A$Kind", "FIELD A$Kind ONE"), symbols);
    Symbol run = analyzer.symbols(path).get(3);
    assertEquals(3, run.range.startLine);
  }

  @Test
  void queriesWithoutACompileFindNothing() {
    String path = tmp.resolve("A.groovy").toString();
    assertNull(analyzer.definition(path, 0, 0));
    assertNull(analyzer.hover(path, 0, 0));
    assertEquals(List.of(), analyzer.symbols(path));
  }

  private CompletionList complete(String path, String text, int line, int column) {
    compile(path, text);
    return analyzer.completion(new CompileRequest(path, text, lookup, () -> false), line, column);
  }

  private static List<String> labels(CompletionList list) {
    return list.items.stream().map(c -> c.label).collect(Collectors.toList());
  }

  /** "a." does not parse: the answer comes from a compile of a copy, and the kept compile stays that of the text. */
  @Test
  void completionAfterADotUsesACopyWithAPlaceholderName() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "class A {\n  String name\n  def run() {\n    name.\n  }\n}\n";
    List<String> labels = labels(complete(path, text, 3, 9));
    assertTrue(labels.contains("toUpperCase"), labels.toString());
    assertEquals(List.of(), analyzer.symbols(path), "the copy is not kept");
  }

  /** groovy-language-server #94: members of superclasses and traits were missing. */
  @Test
  void membersComeFromTheWholeTypeHierarchyTraitsIncluded() throws IOException {
    index.put("Greets", write("src/Greets.groovy", "trait Greets {\n  String greet() { 'hi' }\n}\n"));
    index.put("Base", write("src/Base.groovy", "class Base implements Greets {\n  String baseName\n  void fromBase() {}\n}\n"));
    String path = tmp.resolve("src/A.groovy").toString();
    List<String> labels = labels(complete(path, "class A extends Base {\n  void own() {}\n  def run(A a) {\n    a.\n  }\n}\n", 3, 6));
    assertTrue(labels.containsAll(List.of("own", "fromBase", "baseName", "greet", "hashCode")), labels.toString());
    assertTrue(labels.stream().noneMatch(label -> label.contains("$")), "no generated names: " + labels);

    List<String> bare = labels(complete(path, "class A extends Base {\n  void own() {}\n  def run() {\n    gr\n  }\n}\n", 3, 6));
    assertEquals(List.of("greet"), bare, "a name in a method: the enclosing class and its supertypes");
  }

  @Test
  void afterAClassNameOnlyStaticMembers() {
    String path = tmp.resolve("A.groovy").toString();
    List<String> labels = labels(complete(path, "class A {\n  static int count() { 1 }\n  int size() { 2 }\n  def run() {\n    A.\n  }\n}\n", 4, 6));
    assertTrue(labels.contains("count"), labels.toString());
    assertFalse(labels.contains("size"), labels.toString());
  }

  @Test
  void resolveGivesTheSignatureAndDocumentationUntilTheNextCompile() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "class A {\n  /** Counts things. */\n  int count(String what) { 1 }\n  def run() {\n    cou\n  }\n}\n";
    CompletionList list = complete(path, text, 4, 7);
    Completion count = list.items.stream().filter(c -> c.label.equals("count")).findFirst().orElseThrow();
    assertEquals(Completion.Kind.METHOD, count.kind);
    assertNull(count.detail, "left to resolve");
    CompletionDetail detail = analyzer.resolveCompletion(path, count.id);
    assertEquals("public int count(String what)", detail.detail);
    assertEquals("Counts things.", detail.documentation);
    compile(path, text);
    assertNull(analyzer.resolveCompletion(path, count.id));
  }

  @Test
  void aClassOfAnotherPackageComesWithItsImport() throws IOException {
    index.put("other.Widget", write("src/other/Widget.groovy", "package other\nclass Widget {}\n"));
    String path = tmp.resolve("src/app/A.groovy").toString();
    CompletionList list = complete(path, "package app\n\nclass A {\n  other.Widget widget\n  def run() {\n    Wid\n  }\n}\n", 5, 7);
    Completion widget = list.items.stream().filter(c -> c.label.equals("Widget")).findFirst().orElseThrow();
    assertEquals("other", widget.detail);
    assertEquals("[2:1-2:1 import other.Widget\n]", widget.additionalEdits.toString());
  }

  /** Classes the compile does not contain yet (the symbol index's) are offered too, with their import. */
  @Test
  void aClassNotImportedYetComesFromTheLookupsTypes() {
    types.add(new Symbol("other.Gadget", Symbol.Kind.ENUM, null, new Range(1, 0, 1, 10)));
    types.add(new Symbol("TopLevel", Symbol.Kind.CLASS, null, new Range(0, 0, 0, 10)));
    String path = tmp.resolve("src/app/A.groovy").toString();
    CompletionList list = complete(path, "package app\n\nclass A {\n  def run() {\n    Gad\n  }\n}\n", 4, 7);
    Completion gadget = list.items.stream().filter(c -> c.label.equals("Gadget")).findFirst().orElseThrow();
    assertEquals(Completion.Kind.ENUM, gadget.kind);
    assertEquals("other", gadget.detail);
    assertEquals("[2:1-2:1 import other.Gadget\n]", gadget.additionalEdits.toString());

    CompletionList imports = complete(path, "package app\nimport oth\nclass A {}\n", 1, 10);
    assertTrue(labels(imports).containsAll(List.of("other", "other.Gadget")), labels(imports).toString());
  }

  /**
   * The name on an import line is read from the text: it does not parse after a dot, and a star import has no type.
   * The line after it is another import, the case where Groovy 2.5 gives an import node the next one's position.
   */
  @Test
  void importCompletionOffersThePackagesAndClassesOfTheJarsBelowTheNameTyped() {
    jarPackages.put("", List.of("java", "javax"));
    jarPackages.put("java", List.of("java.io", "java.util"));
    jarPackages.put("java.util", List.of("java.util.concurrent"));
    jarClasses.put("java.util", List.of("java.util.ArrayList", "java.util.Arrays", "java.util.List"));
    types.add(new Symbol("lib.shelf.Book", Symbol.Kind.CLASS, null, new Range(1, 0, 1, 10)));
    String path = tmp.resolve("src/app/A.groovy").toString();
    String rest = "\nimport java.io.File\nclass A {}\n";

    assertEquals(List.of("java.util"), labels(complete(path, "package app\nimport java.ut" + rest, 1, 14)));
    CompletionList below = complete(path, "package app\nimport java.util." + rest, 1, 17);
    assertEquals(List.of("java.util.concurrent", "java.util.ArrayList", "java.util.Arrays", "java.util.List"), labels(below));
    assertEquals(Completion.Kind.PACKAGE, below.items.get(0).kind);
    assertEquals("1:7-1:17", below.items.get(1).replace.startLine + ":" + below.items.get(1).replace.startColumn + "-" + below.items.get(1).replace.endLine + ":"
        + below.items.get(1).replace.endColumn, "the whole name typed is replaced");
    assertEquals(List.of("java.util.ArrayList", "java.util.Arrays"), labels(complete(path, "package app\nimport java.util.Ar" + rest, 1, 19)));
    assertEquals(List.of("lib.shelf", "lib.shelf.Book"), labels(complete(path, "package app\nimport lib." + rest, 1, 11)));
    assertEquals(List.of("java.util.ArrayList"), labels(complete(path, "package app\nimport    java.util.ArrayL" + rest, 1, 26)));
    assertEquals(List.of(), labels(complete(path, "package app\nimport java.util.*" + rest, 1, 18)), "nothing to offer after a star, and no failure");
  }

  /** {@code import static a.B.m}: the class as the compile resolved it, its static members that another class sees. */
  @Test
  void aStaticImportOffersTheStaticMembersOfItsClass() {
    jarClasses.put("java.util", List.of("java.util.Collections"));
    String path = tmp.resolve("src/app/A.groovy").toString();
    String rest = "\nimport java.io.File\nclass A {}\n";
    List<String> members = labels(complete(path, "package app\nimport static java.util.Collections.emptyL" + rest, 1, 42));
    assertEquals(List.of("emptyList", "emptyListIterator"), members.stream().sorted().collect(Collectors.toList()));
    List<String> afterDot = labels(complete(path, "package app\nimport static java.util.Collections." + rest, 1, 36));
    assertTrue(afterDot.containsAll(List.of("EMPTY_LIST", "emptyList", "sort")), afterDot.toString());
    assertFalse(afterDot.contains("r") || afterDot.contains("BINARYSEARCH_THRESHOLD"), "private members: " + afterDot);
    assertFalse(afterDot.contains("toString"), "instance members: " + afterDot);
    assertEquals(List.of("java.util.Collections"), labels(complete(path, "package app\nimport static java.util.Coll" + rest, 1, 28)), "the class is still being typed");
  }

  /**
   * Statically compiled code goes on to instruction selection: the type checker's errors are reported, and the types
   * it infers (closure parameters, flow typing) are what completion and hover use. The other queries still answer.
   */
  @Test
  void staticallyCompiledCodeUsesTheTypesTheCheckerInfers() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "@groovy.transform.CompileStatic\nclass A {\n  String name\n  int run(List<String> names) {\n    names.each { it.toUpperCase() }\n"
        + "    def x\n    x = 'text'\n    x.length() + name.size()\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
    assertEquals(Phases.INSTRUCTION_SELECTION, analyzer.phase(path));
    String hover = analyzer.hover(path, 4, 21);
    assertNotNull(hover, "it.toUpperCase(): it is a String");
    assertEquals("public String toUpperCase()", hover.split("\n")[1]);
    assertEquals("public int length()", analyzer.hover(path, 7, 7).split("\n")[1], "flow typing: x holds a String");
    // String.size() is a Groovy extension method: the checker's target shows as static on 2.5
    assertTrue(analyzer.hover(path, 7, 22).split("\n")[1].matches("public (static )?int size\\(\\)"), analyzer.hover(path, 7, 22));
    assertEquals(2, analyzer.definition(path, 7, 18).range.startLine, "name: the property");

    String typed = text.replace("it.toUpperCase()", "it.");
    List<String> labels = labels(complete(path, typed, 4, 20));
    assertTrue(labels.contains("toUpperCase"), labels.toString());

    CompileResult wrong = compile(path, "@groovy.transform.CompileStatic\nclass A {\n  int run() { 'x'.nope() }\n}\n");
    assertEquals(1, errors(wrong).size(), "the static type checker ran: " + wrong.diagnostics);
    assertTrue(errors(wrong).get(0).contains("nope"), errors(wrong).toString());
  }

  /**
   * A transformation that adds statically compiled members (Grails's controller traits, here a trait's) does not make
   * the class statically compiled: it stops at canonicalization, as the document's own annotations say.
   */
  @Test
  void membersAddedStaticallyCompiledDoNotMakeTheDocumentStaticallyCompiled() throws IOException {
    index.put("Greets", write("src/Greets.groovy", "@groovy.transform.CompileStatic\ntrait Greets {\n  String name\n  String greet() { 'hi ' + name }\n}\n"));
    String path = tmp.resolve("src/A.groovy").toString();
    assertEquals(List.of(), errors(compile(path, "class A implements Greets {\n  def run() { greet() }\n}\n")));
    assertEquals(Phases.CANONICALIZATION, analyzer.phase(path));
  }

  /** The symbol index's parse: declarations only, no class resolved, no transformation run. */
  @Test
  void declarationsComeFromAParseOfEachFile() throws IOException {
    String a = write("src/p/A.groovy", "package p\n@groovy.transform.ToString\nclass A {\n  Missing m\n  void run() {}\n  static class In {}\n}\n");
    String broken = write("src/p/B.groovy", "class B {\n  void run( {\n}\n");
    String missing = tmp.resolve("src/p/Gone.groovy").toString();
    Map<String, List<Symbol>> found = analyzer.declarations(List.of(a, broken, missing));
    List<String> symbols = found.get(a).stream().map(s -> s.kind + " " + s.container + " " + s.name).collect(Collectors.toList());
    assertEquals(List.of("CLASS null p.A", "FIELD p.A m", "METHOD p.A run", "CLASS null p.A$In"), symbols, "no toString: @ToString did not run");
    assertEquals(4, found.get(a).get(2).range.startLine);
    assertEquals(List.of(), found.get(broken));
    assertEquals(List.of(), found.get(missing));
  }

  @Test
  void signatureHelpOnAnUnfinishedCallListsTheOverloads() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "class A {\n  void show(Map options) {}\n  void show(String text, int times) {}\n  def run() {\n    show(\n  }\n}\n";
    compile(path, text);
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 4, 9);
    assertNotNull(help);
    assertEquals("[public void show(Map options), public void show(String text, int times)]", help.signatures.toString());
    assertEquals(List.of("String text", "int times"), help.signatures.get(1).parameters);
    assertEquals(0, help.activeParameter);
  }

  @Test
  void signatureHelpPicksTheOverloadAndArgumentAtThePosition() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "class A {\n  void show(Map options) {}\n  void show(String text, int times) {}\n  def run() {\n    show('x', 2)\n    show(view: 'x')\n  }\n}\n";
    compile(path, text);
    SignatureHelp positional = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 4, 14);
    assertEquals(1, positional.activeSignature);
    assertEquals(1, positional.activeParameter);
    SignatureHelp named = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 5, 17);
    assertEquals(0, named.activeSignature, "named arguments are one map");
    assertEquals(0, named.activeParameter);
  }
}
