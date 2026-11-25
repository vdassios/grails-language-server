package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.CompileUnit;
import org.codehaus.groovy.ast.MethodNode;
import org.codehaus.groovy.ast.ModuleNode;
import org.codehaus.groovy.ast.Parameter;
import org.codehaus.groovy.ast.expr.ClosureExpression;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.messages.SyntaxErrorMessage;
import org.codehaus.groovy.transform.sc.StaticCompilationMetadataKeys;
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

/** Runs on Groovy 2.5 ({@code test}), 3, 4 and 5 ({@code testGroovy3}, {@code testGroovy4}, {@code testGroovy5}). */
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

    @Override
    public List<String> grailsAppDirs() {
      return grailsApps;
    }
  };
  /** The modules' {@code grails-app} directories. */
  private final List<String> grailsApps = new ArrayList<>();
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

  /** Each Groovy tested reads the class files of the JDKs the tests run on (11 and 17). */
  @Test
  void readsTheRunningJdksClassFiles() {
    assertNull(analyzer.jdkClassFileError());
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

  /**
   * Groovy 3 and later keep no tree of a text that does not parse, and each later phase would parse it again: about
   * 45 ms a parse on a large file, with ANTLR's LL pass. Each parse adds its syntax error to the error collector.
   */
  @Test
  void aTextThatDoesNotParseIsParsedOnce() {
    String path = tmp.resolve("A.groovy").toString();
    for (String text : List.of("class A {\n  int run(String s) {\n    s.\n  }\n}\n",
        "@groovy.transform.CompileStatic\nclass A {\n  int run(String s) {\n    s.\n  }\n}\n")) {
      CompileResult result = compile(path, text);
      assertEquals(1, result.diagnostics.size(), result.diagnostics.toString());
      long syntaxErrors = analyzer.unit(path).getErrorCollector().getErrors().stream().filter(SyntaxErrorMessage.class::isInstance).count();
      assertEquals(1, syntaxErrors, "one parse, so one syntax error in the collector: " + text);
      assertEquals(Phases.CANONICALIZATION, analyzer.phase(path));
    }
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
  void aliasedAstTestClosuresDoNotRun() throws Exception {
    Path marker = tmp.resolve("marker");
    String source = "import groovy.transform.ASTTest as T\n@T(value = { new File('" + marker + "').text = 'ran' })\nclass A {}\n";
    compile(tmp.resolve("A.groovy").toString(), source);
    assertFalse(Files.exists(marker), "an aliased @ASTTest ran in the server");

    try (GroovyClassLoader plain = new GroovyClassLoader()) {
      plain.parseClass(source);
    }
    assertTrue(Files.exists(marker), "control: a plain compile runs it");
  }

  @Test
  void astTestClosuresInAnAnnotationCollectorDoNotRun() throws Exception {
    Path marker = tmp.resolve("marker");
    String source = "import groovy.transform.*\n@ASTTest(value = { new File('" + marker + "').text = 'ran' })\n@AnnotationCollector\n@interface Probe {}\n"
        + "@Probe\nclass A {}\n";
    compile(tmp.resolve("A.groovy").toString(), source);
    assertFalse(Files.exists(marker), "@ASTTest collected by an annotation collector ran in the server");
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

  /**
   * After a typed dot completion compiles a copy of the text; a cancelled request (the server sets the flag on
   * {@code $/cancelRequest}) stops that compile before its first phase and gives nothing.
   */
  @Test
  void aCancelledCompletionStopsItsCompileOfACopy() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "class A {\n  def run(String s) {\n    s.\n  }\n}\n";
    compile(path, text);
    AtomicInteger polls = new AtomicInteger();
    CompletionList cancelled = analyzer.completion(new CompileRequest(path, text, lookup, () -> {
      polls.incrementAndGet();
      return true;
    }), 2, 6);
    assertEquals(List.of(), labels(cancelled));
    assertEquals(1, polls.get(), "stopped at its first check, and no second copy was compiled");
    assertTrue(labels(complete(path, text, 2, 6)).contains("substring"), "not cancelled: String's methods");
  }

  /**
   * Reading the tags walks the taglib directories and reads every project taglib: a request's tag lookups ({@code g.},
   * page tags, a tag's attributes) read them once.
   */
  @Test
  void aRequestsTagLookupsReadTheTaglibsOnce() throws IOException {
    write("app/grails-app/taglib/demo/XTagLib.groovy", "package demo\nclass XTagLib {\n  static namespace = 'x'\n  def one = { attrs -> }\n}\n");
    grailsApps.add(tmp.resolve("app/grails-app").toString());
    AtomicInteger reads = new AtomicInteger();
    SourceLookup counting = new SourceLookup() {
      @Override
      public String fileOf(String className) {
        return null;
      }

      @Override
      public String openText(String path) {
        return null;
      }

      @Override
      public List<String> grailsAppDirs() {
        reads.incrementAndGet();
        return grailsApps;
      }
    };
    Function<String, Map<String, Taglibs.Tag>> namespaces = analyzer.namespaces(counting);
    assertEquals(List.of("one"), new ArrayList<>(namespaces.apply("x").keySet()));
    assertNull(namespaces.apply("y"));
    assertEquals(1, reads.get());
  }

  @Test
  void forgetDropsTheKeptCompile() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {}\n");
    assertNotNull(analyzer.compiledClass(path, "A"));
    analyzer.forget(path);
    assertNull(analyzer.compiledClass(path, "A"));
  }

  /** A page's model variables read a controller's text, parse and compile: kept for the next page, dropped when the controller is closed. */
  @Test
  void forgetDropsWhatPagesKeepOfAFile() throws IOException {
    String controller = write("grails-app/controllers/BookController.groovy", "class BookController {\n  def index() { render view: 'index', model: [count: size()] }\n  int size() { 1 }\n}\n");
    index.put("BookController", controller);
    grailsApps.add(tmp.resolve("grails-app").toString());
    compile(tmp.resolve("grails-app/views/book/index.gsp").toString(), "<p>${count}</p>\n");
    assertTrue(analyzer.pagesKeep(controller), "the page's model read the controller");
    analyzer.forget(controller);
    assertFalse(analyzer.pagesKeep(controller));
  }

  /** Controllers of every module with a {@code grails-app} (a plugin's and the app's) are named, defined and completed. */
  @Test
  void controllersOfEveryModuleAreNamed() throws IOException {
    String admin = write("plugin/grails-app/controllers/admin/AdminController.groovy", "package admin\n\nclass AdminController {\n  def list() {}\n}\n");
    write("app/grails-app/controllers/demo/ShopController.groovy", "package demo\n\nclass ShopController {\n  def index() {}\n}\n");
    index.put("admin.AdminController", admin);
    grailsApps.add(tmp.resolve("app/grails-app").toString());
    grailsApps.add(tmp.resolve("plugin/grails-app").toString());
    // not saved yet: completion offers the controllers on disk
    String path = tmp.resolve("app/grails-app/controllers/demo/HomeController.groovy").toString();
    String text = "package demo\n\nclass HomeController {\n  def index() { redirect(controller: 'admin', action: 'list') }\n}\n";
    compile(path, text);
    Location controller = analyzer.definition(path, 3, 38); // on 'admin'
    assertEquals(admin + ":2", controller == null ? null : controller.path + ":" + controller.range.startLine);
    Location action = analyzer.definition(path, 3, 56); // on 'list'
    assertEquals(admin + ":3", action == null ? null : action.path + ":" + action.range.startLine);
    String typing = text.replace("controller: 'admin'", "controller: ''");
    assertEquals(List.of("admin", "shop"), labels(complete(path, typing, 3, 38)).stream().sorted().collect(Collectors.toList()));
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
  /**
   * Groovy 3 and later count columns in code points; LSP positions count UTF-16 units, two for an emoji. The document,
   * a file read from disk and a comment's text all keep their emoji.
   */
  @Test
  void columnsAfterAnEmojiCountUtf16Units() throws IOException {
    String other = write("src/Other.groovy", "class Other {\n  def s = '\uD83D\uDE00'; def target = 1\n}\n");
    index.put("Other", other);
    String path = tmp.resolve("src/A.groovy").toString();
    CompileResult result = compile(path, "class A {\n  /** Counts \uD83D\uDE00 things. */\n  def run(Other other) {\n"
        + "    def count = 1; ['\uD83D\uDE00', count, new Missing(), other.target]\n  }\n}\n");
    assertEquals(List.of(path + ":4:34: unable to resolve class Missing"), errors(result));
    assertEquals("4:9-4:14", definitionRange(path, 3, 26)); // on count, after the emoji
    assertEquals("4:9-4:14", definitionRange(path, 3, 30));
    Location target = analyzer.definition(path, 3, 56);
    assertNotNull(target);
    assertEquals(other + ":2:17", target.path + ":" + target.range.toString().split("-")[0]);
    assertTrue(analyzer.hover(path, 2, 7).endsWith("---\n\nCounts \uD83D\uDE00 things."), analyzer.hover(path, 2, 7));
  }

  /** A letter outside the BMP in a name (mathematical italic x): the name still parses, and columns after it are right. */
  @Test
  void aNameWithALetterOutsideTheBmpResolves() {
    String path = tmp.resolve("A.groovy").toString();
    CompileResult result = compile(path, "class A {\n  def run() {\n    def \uD835\uDC65 = 1; [\uD835\uDC65, new Missing()]\n  }\n}\n");
    assertEquals(List.of(path + ":3:22: unable to resolve class Missing"), errors(result));
    assertEquals("3:9-3:11", definitionRange(path, 2, 17)); // on the name's use
  }

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

  /** The range of the definition at a position in the document, as {@link Range#toString()} gives it (1-based), or null. */
  private String definitionRange(String path, int line, int column) {
    Location location = analyzer.definition(path, line, column);
    if (location == null) {
      return null;
    }
    assertEquals(path, location.path);
    return location.range.toString();
  }

  @Test
  void definitionOfAParameterIsItsDeclaration() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  A(int param) {\n  }\n  void run(int param) {\n    param\n  }\n}\n");
    assertEquals("2:5-2:14", definitionRange(path, 1, 10), "on a constructor's parameter");
    assertEquals("4:12-4:21", definitionRange(path, 3, 16), "on a method's parameter");
    assertEquals("4:12-4:21", definitionRange(path, 4, 6), "on a use of it");
  }

  @Test
  void definitionOfAMemberOfAnArrayItemIsTheMember() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  A() {\n    A[] items\n    items[0].hello\n  }\n  public String hello\n}\n");
    assertEquals("6:3-6:22", definitionRange(path, 3, 15));
  }

  /** A field, a method and a class of the document: from their declarations, an assignment, a call, a type, a class expression. */
  @Test
  void definitionOfTheDocumentsMembersAndClasses() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  public int memberVar\n  public void memberMethod() {}\n  public static void staticMethod() {}\n  A() {\n"
        + "    memberVar = 123\n    memberMethod()\n    A a\n    A.staticMethod()\n  }\n}\n");
    assertEquals("2:3-2:23", definitionRange(path, 1, 18), "on the field's declaration");
    assertEquals("2:3-2:23", definitionRange(path, 5, 6), "on an assignment to the field");
    assertEquals("3:3-3:32", definitionRange(path, 2, 16), "on the method's declaration");
    assertEquals("3:3-3:32", definitionRange(path, 6, 6), "on a call of the method");
    assertEquals("1:1-11:2", definitionRange(path, 0, 6), "on the class's declaration");
    assertEquals("1:1-11:2", definitionRange(path, 7, 4), "on a variable's type");
    assertEquals("1:1-11:2", definitionRange(path, 8, 4), "on a class expression");
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
    compile(path, "class A {\n  /**\n   * Counts things.\n   * Each {@code what} once, see {@link #run}.\n   * @param what the thing\n   */\n"
        + "  int count(String what) { 1 }\n  def run() { count('x') }\n}\n");
    String hover = analyzer.hover(path, 7, 15);
    assertNotNull(hover);
    assertTrue(hover.startsWith("```groovy\npublic int count(String what)\n```"), hover);
    // Groovy 3 and later attach the comment to the node; on 2.5 it is read from the document's text. Either way a line
    // with an inline tag stays, and the block tags go.
    assertTrue(hover.endsWith("---\n\nCounts things.\nEach `what` once, see `run`."), hover);
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

  /** Signatures keep their types' arguments, wildcards and bounds: declared, read from a class file, and inferred. */
  @Test
  void hoverShowsTypeArguments() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "class Box<T extends Number> extends ArrayList<T> {\n"
        + "  static List<Map<String, Integer>> rows(List<? extends Number> index, Map<String, ? super Integer> sink) { [] }\n"
        + "  T first(List<T> items) { items[0] }\n"
        + "  def run() {\n    List<String> names = ['a']\n    first(names)\n    Collections.emptyList()\n  }\n}\n"
        + "@groovy.transform.CompileStatic\nclass C {\n  def run(List<String> names) {\n    def sizes = names.collect { it.size() }\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
    Function<int[], String> signature = at -> analyzer.hover(path, at[0], at[1]).split("\n")[1];
    assertEquals("class Box<T extends Number> extends ArrayList<T>", signature.apply(new int[] { 0, 7 }));
    assertEquals("static List<Map<String, Integer>> rows(List<? extends Number> index, Map<String, ? super Integer> sink)",
        signature.apply(new int[] { 1, 37 }));
    assertEquals("public T first(List<T> items)", signature.apply(new int[] { 5, 5 }));
    assertEquals("List<String> names", signature.apply(new int[] { 4, 19 }));
    assertEquals("public static final <T> List<T> emptyList()", signature.apply(new int[] { 6, 18 }));
    assertEquals("List<Integer> sizes", signature.apply(new int[] { 12, 9 }), "the type the checker infers");
  }

  /**
   * Hover, signature help, and completion details write a nested class as source does ({@code Map.Entry}), not by its
   * binary name ({@code Map$Entry}): from a class file and from the compile, nested twice, and with type arguments. An
   * anonymous class has no name to write, so it keeps its binary one.
   */
  @Test
  void nestedClassesShowAsSourceWritesThem() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "package p\n@groovy.transform.CompileStatic\nclass A {\n  static class Outer {\n    static class In<T> {\n"
        + "      In<T> self(Map.Entry<String, T> entry) { this }\n    }\n  }\n"
        + "  Map.Entry<String, Integer> first(Map<String, Integer> map) {\n    Outer.In<Integer> deep = new Outer.In<Integer>()\n"
        + "    deep.self(null)\n    def simple = new AbstractMap.SimpleEntry<String, Integer>('a', 1)\n    def anon = new Runnable() { void run() {} }\n"
        + "    map.entrySet().first()\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
    Function<int[], String> signature = at -> analyzer.hover(path, at[0], at[1]).split("\n")[1];
    assertEquals("public Map.Entry<String, Integer> first(Map<String, Integer> map)", signature.apply(new int[] { 8, 30 }));
    assertEquals("A.Outer.In<Integer> deep", signature.apply(new int[] { 9, 23 }));
    assertEquals("public A.Outer.In<T> self(Map.Entry<String, T> entry)", signature.apply(new int[] { 10, 10 }));
    assertEquals("class A.Outer.In<T>", analyzer.hover(path, 4, 18).split("\n")[2], "the class, after its package");
    assertEquals("AbstractMap.SimpleEntry<String, Integer> simple", signature.apply(new int[] { 11, 9 }));
    assertEquals("A$1 anon", signature.apply(new int[] { 12, 9 }));
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 11, 62);
    List<String> labels = help.signatures.stream().map(s -> s.label).collect(Collectors.toList());
    assertTrue(labels.stream().anyMatch(label -> label.startsWith("java.util.AbstractMap.SimpleEntry(Map.Entry<?, ?> ")), labels.toString());
    CompletionList list = complete(path, text, 13, 8);
    Completion entrySet = list.items.stream().filter(c -> c.label.equals("entrySet")).findFirst().orElseThrow();
    assertEquals("public Set<Map.Entry<K, V>> entrySet()", analyzer.resolveCompletion(path, entrySet.id).detail);
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

  /**
   * A call without a receiver to a static method of the class: dynamic Groovy makes it a
   * {@code StaticMethodCallExpression}, and static compilation a call on the class. Hover, definition, signature help,
   * and the call's type find the method in both, from an instance method, a static one, and as another call's receiver.
   */
  @Test
  void anImplicitThisStaticCallFindsItsMethod() {
    String path = tmp.resolve("A.groovy").toString();
    String body = "class A {\n  /** Repeats. */\n  static String count(int n) { 'x' * n }\n  String size() { count(1) }\n  static String twice() {\n"
        + "    def d = count(2)\n    count(3).trim()\n  }\n}\n";
    BiFunction<Integer, Integer, String> hover = (line, column) -> {
      String markdown = analyzer.hover(path, line, column);
      return markdown == null ? null : markdown.split("\n")[1];
    };
    BiFunction<Integer, Integer, String> definition = (line, column) -> {
      Location location = analyzer.definition(path, line, column);
      return location == null ? null : String.valueOf(location.range.startLine);
    };
    Map<String, String> expected = new java.util.LinkedHashMap<>();
    expected.put("hover in an instance method", "static String count(int n)");
    expected.put("hover in a static method", "static String count(int n)");
    expected.put("hover on the first letter of a receiver", "static String count(int n)");
    expected.put("hover on the last letter of a receiver", "static String count(int n)");
    expected.put("hover on the variable it sets", "String d");
    expected.put("hover on the method called on its result", "public String trim()");
    expected.put("definition in an instance method", "2");
    expected.put("definition of a receiver", "2");
    expected.put("signature help", "static String count(int n)");
    for (String annotation : List.of("", "@groovy.transform.CompileStatic ")) {
      String text = annotation + body;
      assertEquals(List.of(), errors(compile(path, text)), annotation);
      Map<String, String> found = new java.util.LinkedHashMap<>();
      found.put("hover in an instance method", hover.apply(3, 19));
      found.put("hover in a static method", hover.apply(5, 14));
      found.put("hover on the first letter of a receiver", hover.apply(6, 4));
      found.put("hover on the last letter of a receiver", hover.apply(6, 8));
      found.put("hover on the variable it sets", hover.apply(5, 8));
      found.put("hover on the method called on its result", hover.apply(6, 14));
      found.put("definition in an instance method", definition.apply(3, 19));
      found.put("definition of a receiver", definition.apply(6, 4));
      SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 5, 18);
      found.put("signature help", help == null ? null : help.signatures.get(help.activeSignature).label);
      assertEquals(expected, found, annotation.isEmpty() ? "dynamic" : annotation);
    }
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

  /**
   * The copy is repaired once, as the line needs: in a string the line leaves open the string is closed too, else only
   * a name is typed. Counted with a cancelling flag: each copy compiled checks it once, then stops.
   */
  @Test
  void completionCompilesOneCopy() {
    String path = tmp.resolve("A.groovy").toString();
    for (String line : List.of("    render(view: 'sh", "    def s = \"sh", "    name.", "    def s = 'a' + name.", "    render(view: 'a' + name.")) {
      String text = "class A {\n  String name\n  def run() {\n" + line + "\n  }\n}\n";
      compile(path, text);
      AtomicInteger polls = new AtomicInteger();
      analyzer.completion(new CompileRequest(path, text, lookup, () -> {
        polls.incrementAndGet();
        return true;
      }), 3, line.length());
      assertEquals(1, polls.get(), line);
    }
    String closed = "class A {\n  String name\n  def run() {\n    def s = \"${name.}\" + 'x'\n  }\n}\n";
    int column = closed.split("\n")[3].indexOf('}');
    assertTrue(labels(complete(path, closed, 3, column)).contains("toUpperCase"), "a string the line closes later is not closed again");
  }

  /**
   * A block, bracket or parenthesis opened before the position and left open (a method being written before its
   * closing brace) is closed in the copy, on a line after the position's; braces in strings and comments do not count.
   */
  @Test
  void completionInABlockLeftOpenClosesItInTheCopy() {
    String path = tmp.resolve("A.groovy").toString();
    String start = "class A {\n  String name\n  def run() {\n";
    for (String rest : List.of("    name.\n}\n", "    name.", "    name.\n  def other() {}\n}\n", "    name.\n    int x = 1\n}\n", "    [1].each { n ->\n      name.\n  }\n}\n",
        "    def x = [name.\n  }\n}\n", "    println(name.)\n}\n", "    def s = '{' // {\n    /* { */ name.\n}\n")) {
      String text = start + rest;
      String[] lines = text.split("\n", -1);
      int line = 3;
      while (!lines[line].contains("name.")) {
        line++;
      }
      CompletionList list = complete(path, text, line, lines[line].indexOf("name.") + 5);
      assertTrue(labels(list).contains("toUpperCase"), rest + ": " + labels(list));
    }
  }

  /** A list left empty because no copy parses asks again as more is typed: the next text may parse. */
  @Test
  void anEmptyCompletionOfATextThatDoesNotParseIsIncomplete() {
    String path = tmp.resolve("A.groovy").toString();
    CompletionList list = complete(path, "class A {\n  String name\n  def run() {\n    name.\n  }\n  def x = )\n}\n", 3, 9);
    assertEquals(List.of(), list.items);
    assertTrue(list.incomplete);
    assertFalse(complete(path, "class A {\n  String name\n  def run() {\n    name.\n  }\n}\n", 3, 9).incomplete, "a list of a copy that parses is complete");
  }

  /** A completion's labels, and whether it compiled: the flag is checked before each compile phase. */
  private static final class Answer {
    final List<String> labels;
    final boolean compiled;
    final CompletionList list;

    Answer(CompletionList list, boolean compiled) {
      this.list = list;
      this.labels = labels(list);
      this.compiled = compiled;
    }
  }

  /** Compiles {@code before}, then {@code typed} (as the server does before a query on it), and completes at the end of a line of {@code typed}. */
  private Answer afterTypedDot(String path, String before, String typed, int line) {
    compile(path, before);
    compile(path, typed);
    AtomicInteger polls = new AtomicInteger();
    CompletionList list = analyzer.completion(new CompileRequest(path, typed, lookup, () -> polls.incrementAndGet() < 0), line, typed.split("\n", -1)[line].length());
    return new Answer(list, polls.get() > 0);
  }

  /** What a compile of a copy answers: with no earlier compile to answer from. */
  private List<String> fromACopy(String path, String typed, int line) {
    analyzer.forget(path);
    Answer answer = afterTypedDot(path, typed, typed, line);
    assertTrue(answer.compiled);
    return answer.labels;
  }

  /**
   * A dot just typed does not parse: the receiver's members come from the last compile that parsed, without a compile,
   * the same as a compile of a copy gives; for a member deleted after the dot, and for a dot typed after a receiver.
   */
  @Test
  void completionAfterATypedDotIsAnsweredFromTheLastCompileThatParsed() {
    String path = tmp.resolve("A.groovy").toString();
    String template = "class A {\n  String name\n  static int count() { 1 }\n  String[] items\n  String upper() { name }\n  def run() {\n    %s\n  }\n}\n";
    // receiver, dot, member
    List<String[]> accesses = List.of(new String[] { "name", ".", "toUpperCase()" }, new String[] { "name", "?.", "size()" }, new String[] { "this", ".", "name" },
        new String[] { "items[0]", ".", "size()" }, new String[] { "A", ".", "count()" }, new String[] { "upper()", ".", "size()" },
        new String[] { "name.toUpperCase()", ".", "trim()" }, new String[] { "name.substring(1)", ".", "trim()" }, new String[] { "this.name", ".", "trim()" });
    for (String[] access : accesses) {
      String typed = String.format(template, access[0] + access[1]);
      for (String before : List.of(String.format(template, access[0] + access[1] + access[2]), String.format(template, access[0]))) {
        Answer answer = afterTypedDot(path, before, typed, 6);
        assertFalse(answer.compiled, before + " then " + typed);
        assertFalse(answer.labels.isEmpty(), access[0]);
        assertEquals(fromACopy(path, typed, 6), answer.labels, access[0]);
      }
    }
    Answer answer = afterTypedDot(path, String.format(template, "name.trim()"), String.format(template, "name."), 6);
    Completion trim = answer.list.items.stream().filter(c -> c.label.equals("trim")).findFirst().orElseThrow();
    assertTrue(analyzer.resolveCompletion(path, trim.id).detail.contains("trim()"), "resolved on that compile");
  }

  /** The receiver's type changed since the compile that parsed: answered from a compile of a copy. */
  @Test
  void aTypedDotAfterAChangeToTheReceiversTypeCompilesACopy() throws IOException {
    String path = tmp.resolve("src/A.groovy").toString();
    // in the document
    Answer retyped = afterTypedDot(path, "class A {\n  String name\n  def run() {\n    name.trim()\n  }\n}\n", "class A {\n  Integer name\n  def run() {\n    name.\n  }\n}\n", 3);
    assertTrue(retyped.compiled);
    assertTrue(retyped.labels.contains("intValue") && !retyped.labels.contains("trim"), retyped.labels.toString());

    // in the source of its class: open text, the file, and which file declares the class
    String book = write("src/Book.groovy", "class Book {\n  String title\n}\n");
    index.put("Book", book);
    String before = "class A {\n  def run(Book book) {\n    book.title\n  }\n}\n";
    String typed = "class A {\n  def run(Book book) {\n    book.\n  }\n}\n";
    assertFalse(afterTypedDot(path, before, typed, 2).compiled, "control");
    compile(path, before);
    open.put(book, "class Book {\n  String title\n  String author\n}\n");
    compile(path, typed);
    assertTrue(labels(analyzer.completion(new CompileRequest(path, typed, lookup, () -> false), 2, 9)).contains("author"), "open text changed");
    open.remove(book);

    compile(path, before);
    Files.writeString(Path.of(book), "class Book {\n  String title\n  String isbn\n}\n");
    compile(path, typed);
    assertTrue(labels(analyzer.completion(new CompileRequest(path, typed, lookup, () -> false), 2, 9)).contains("isbn"), "file changed");

    compile(path, before);
    index.put("Book", write("src/other/Book.groovy", "class Book {\n  String publisher\n}\n"));
    compile(path, typed);
    assertTrue(labels(analyzer.completion(new CompileRequest(path, typed, lookup, () -> false), 2, 9)).contains("publisher"), "declared by another file");
  }

  /** Where the dot makes the text read differently, or more than names changed, the copy is compiled. */
  @Test
  void aTypedDotThatChangesMoreThanAMemberCompilesACopy() {
    String path = tmp.resolve("A.groovy").toString();
    String template = "class A {\n  String name\n  def run() {\n    %s\n  }\n}\n";
    // the dot ends a class name of instanceof: no member access in the copy
    assertTrue(afterTypedDot(path, String.format(template, "name instanceof String"), String.format(template, "name instanceof String."), 3).compiled);
    // a call deleted after the dot: an argument could declare or retype
    assertTrue(afterTypedDot(path, String.format(template, "name.trim(); def x = 1"), String.format(template, "name."), 3).compiled);
    // another line edited too
    assertTrue(afterTypedDot(path, "class A {\n  String name\n  def run() {\n    name.trim()\n  }\n  def other() {}\n}\n", "class A {\n  String name\n  def run() {\n    name.\n  }\n}\n", 3).compiled);
    // the receiver's span was a method's name: count(1), then count.
    Answer call = afterTypedDot(path, "class A {\n  Integer count\n  def run() {\n    count(1)\n  }\n}\n", "class A {\n  Integer count\n  def run() {\n    count.\n  }\n}\n", 3);
    assertTrue(call.compiled);
    assertTrue(call.labels.contains("intValue") && !call.labels.contains("toUpperCase"), call.labels.toString());
  }

  /** groovy-language-server #94: members of superclasses and traits were missing. */
  @Test
  void membersComeFromTheWholeTypeHierarchyTraitsIncluded() throws IOException {
    index.put("Greets", write("src/Greets.groovy", "trait Greets {\n  String greet() { 'hi' }\n}\n"));
    index.put("Base", write("src/Base.groovy", "class Base implements Greets {\n  String baseName\n  void fromBase() {}\n  private void grow() {}\n}\n"));
    String path = tmp.resolve("src/A.groovy").toString();
    List<String> labels = labels(complete(path, "class A extends Base {\n  void own() {}\n  def run(A a) {\n    a.\n  }\n}\n", 3, 6));
    assertTrue(labels.containsAll(List.of("own", "fromBase", "baseName", "greet", "hashCode")), labels.toString());
    assertTrue(labels.stream().noneMatch(label -> label.contains("$")), "no generated names: " + labels);

    List<String> bare = labels(complete(path, "class A extends Base {\n  void own() {}\n  def run() {\n    gr\n  }\n}\n", 3, 6));
    assertEquals(List.of("greet"), bare, "a name in a method: the enclosing class and its supertypes, not their private members");
  }

  /**
   * A class's private members are offered only in code of the top-level class that declares it, nested classes
   * included: where the static type checker accepts them ({@code No such property} from any other class).
   */
  @Test
  void privateMembersAreOfferedOnlyInsideTheirTopLevelClass() throws IOException {
    index.put("Book", write("src/Book.groovy", "class Book {\n  String title\n  private int pages\n  private static int count\n  private void grow() {}\n  static int total() { 1 }\n}\n"));
    String path = tmp.resolve("src/A.groovy").toString();
    List<String> fromOutside = labels(complete(path, "class A {\n  def run(Book b) {\n    b.\n  }\n}\n", 2, 6));
    assertTrue(fromOutside.contains("title"), fromOutside.toString());
    assertFalse(fromOutside.contains("pages") || fromOutside.contains("grow"), "another class's private members: " + fromOutside);
    List<String> statics = labels(complete(path, "class A {\n  def run() {\n    Book.\n  }\n}\n", 2, 9));
    assertTrue(statics.contains("total"), statics.toString());
    assertFalse(statics.contains("count"), "another class's private static members: " + statics);
    Answer typedDot = afterTypedDot(path, "class A {\n  def run(Book b) {\n    b.title\n  }\n}\n", "class A {\n  def run(Book b) {\n    b.\n  }\n}\n", 2);
    assertFalse(typedDot.compiled);
    assertTrue(typedDot.labels.contains("title") && !typedDot.labels.contains("pages"), "answered from the last compile that parsed: " + typedDot.labels);

    String own = "class A {\n  private int secret\n  private void hide() {}\n  def run(A a, Inner i) {\n    %s\n  }\n  static class Inner {\n    private int nested\n    def run(A a) {\n      %s\n    }\n  }\n}\n";
    List<String> sameClass = labels(complete(path, String.format(own, "a.", "a"), 4, 6));
    assertTrue(sameClass.containsAll(List.of("secret", "hide")), "the class's own: " + sameClass);
    List<String> nestedClass = labels(complete(path, String.format(own, "i.", "a"), 4, 6));
    assertTrue(nestedClass.contains("nested"), "a nested class's, from the outer class: " + nestedClass);
    List<String> outerClass = labels(complete(path, String.format(own, "a", "a."), 9, 8));
    assertTrue(outerClass.containsAll(List.of("secret", "hide")), "the outer class's, from a nested class: " + outerClass);
  }

  /**
   * Package-private and protected members are offered where Java's rules allow them, as {@code groovyc} applies them
   * under static compilation: package-private ones in the declaring class's package, protected ones there and in its
   * subclasses, an instance member in a subclass only through a receiver of that subclass. Dynamic code too.
   */
  @Test
  void packagePrivateAndProtectedMembersAreOfferedOnlyWhereTheyAreAccessible() throws IOException {
    index.put("p1.Lib", write("src/p1/Lib.groovy", "package p1\nimport groovy.transform.PackageScope\nclass Lib {\n  String title\n  @PackageScope int pf\n  @PackageScope int pm() { 1 }\n"
        + "  protected int prf\n  protected int prm() { 1 }\n  @PackageScope static int spm() { 1 }\n  protected static int sprm() { 1 }\n  int open() { 1 }\n}\n"));
    List<String> hidden = List.of("pf", "pm", "prf", "prm", "spm", "sprm");
    for (String mode : List.of("", "@groovy.transform.CompileStatic\n")) {
      String same = tmp.resolve("src/p1/Same.groovy").toString();
      String sameText = "package p1\n" + mode + "class Same {\n  def run(Lib l) {\n    %s\n  }\n}\n";
      int line = mode.isEmpty() ? 3 : 4;
      List<String> samePackage = labels(complete(same, String.format(sameText, "l."), line, 6));
      assertTrue(samePackage.containsAll(List.of("pf", "pm", "prf", "prm", "open")), mode + "the same package: " + samePackage);
      List<String> samePackageStatics = labels(complete(same, String.format(sameText, "Lib."), line, 8));
      assertTrue(samePackageStatics.containsAll(List.of("spm", "sprm")), mode + "the same package: " + samePackageStatics);

      String other = tmp.resolve("src/p2/Other.groovy").toString();
      String otherText = "package p2\nimport p1.Lib\n" + mode + "class Other {\n  def run(Lib l) {\n    %s\n  }\n}\n";
      List<String> otherPackage = labels(complete(other, String.format(otherText, "l."), line + 1, 6));
      assertTrue(otherPackage.contains("open") && otherPackage.stream().noneMatch(hidden::contains), mode + "another package: " + otherPackage);
      List<String> otherPackageStatics = labels(complete(other, String.format(otherText, "Lib."), line + 1, 8));
      assertTrue(otherPackageStatics.stream().noneMatch(hidden::contains), mode + "another package: " + otherPackageStatics);
      Answer typedDot = afterTypedDot(other, String.format(otherText, "l.title"), String.format(otherText, "l."), line + 1);
      assertFalse(typedDot.compiled);
      assertTrue(typedDot.labels.contains("open") && typedDot.labels.stream().noneMatch(hidden::contains), mode + "answered from the last compile that parsed: " + typedDot.labels);

      String sub = tmp.resolve("src/p2/Sub.groovy").toString();
      String subText = "package p2\nimport p1.Lib\n" + mode + "class Sub extends Lib {\n  def run(Lib l, Sub s) {\n    %s\n  }\n  static class Nested {\n    def run(Sub s) {\n      %s\n    }\n  }\n}\n";
      List<String> throughSubclass = labels(complete(sub, String.format(subText, "s.", "s"), line + 1, 6));
      assertTrue(throughSubclass.containsAll(List.of("prf", "prm", "open")), mode + "a subclass, through itself: " + throughSubclass);
      assertFalse(throughSubclass.contains("pf") || throughSubclass.contains("pm"), mode + "a subclass in another package: " + throughSubclass);
      List<String> throughSuperclass = labels(complete(sub, String.format(subText, "l.", "s"), line + 1, 6));
      assertTrue(throughSuperclass.contains("open") && throughSuperclass.stream().noneMatch(hidden::contains), mode + "a subclass, through the superclass: " + throughSuperclass);
      List<String> subclassStatics = labels(complete(sub, String.format(subText, "Lib.", "s"), line + 1, 8));
      assertTrue(subclassStatics.contains("sprm") && !subclassStatics.contains("spm"), mode + "a subclass, static: " + subclassStatics);
      List<String> inherited = labels(complete(sub, String.format(subText, "p", "s"), line + 1, 5));
      assertTrue(inherited.containsAll(List.of("prf", "prm")) && !inherited.contains("pf") && !inherited.contains("pm"), mode + "a subclass, without a receiver: " + inherited);
      List<String> fromNested = labels(complete(sub, String.format(subText, "s", "s."), line + 5, 8));
      assertTrue(fromNested.contains("prm") && !fromNested.contains("pm"), mode + "a subclass's nested class: " + fromNested);
    }
  }

  @Test
  void afterAClassNameOnlyStaticMembers() {
    String path = tmp.resolve("A.groovy").toString();
    List<String> labels = labels(complete(path, "class A {\n  static int count() { 1 }\n  int size() { 2 }\n  def run() {\n    A.\n  }\n}\n", 4, 6));
    assertTrue(labels.contains("count"), labels.toString());
    assertFalse(labels.contains("size"), labels.toString());
  }

  /** The kind of the item with a label at a position, or null when there is no such item. */
  private Completion.Kind kindOf(String path, String text, int line, int column, String label) {
    return complete(path, text, line, column).items.stream().filter(c -> c.label.equals(label)).map(c -> c.kind).findFirst().orElse(null);
  }

  @Test
  void membersAfterADotOnALocalVariableThisAndAnArrayItem() {
    String path = tmp.resolve("A.groovy").toString();
    assertEquals(Completion.Kind.METHOD, kindOf(path, "class A {\n  A() {\n    String local\n    local.\n  }\n}\n", 3, 10, "charAt"));
    assertEquals(Completion.Kind.FIELD, kindOf(path, "class A {\n  String field\n  A() {\n    this.\n  }\n}\n", 3, 9, "field"));
    assertEquals(Completion.Kind.METHOD, kindOf(path, "class A {\n  A() {\n    String[] items\n    items[0].\n  }\n}\n", 3, 13, "charAt"));
  }

  /** "a." followed by a statement parses as one expression ({@code a.b}, {@code a.m()}): still the members of a. */
  @Test
  void aDotBeforeAnotherStatementCompletesTheReceiversMembers() {
    String path = tmp.resolve("A.groovy").toString();
    assertEquals(Completion.Kind.METHOD, kindOf(path, "class A {\n  A() {\n    String local\n    local.\n    local\n  }\n}\n", 3, 10, "charAt"));
    assertEquals(Completion.Kind.METHOD, kindOf(path, "class A {\n  A() {\n    String local\n    local.\n    run()\n  }\n}\n", 3, 10, "charAt"));
  }

  @Test
  void aMemberNameTypedInPartNarrowsTheMembersToThoseItStarts() {
    String path = tmp.resolve("A.groovy").toString();
    List<String> labels = labels(complete(path, "class A {\n  A() {\n    String local\n    local.charA\n  }\n}\n", 3, 15));
    assertEquals(List.of("charAt"), labels);
    String text = "class A {\n  A() {\n    this.abcde\n  }\n  def abc() {}\n  def abcdef() {}\n}\n";
    assertEquals(List.of("abc", "abcdef"), labels(complete(path, text, 2, 11)).stream().sorted().collect(Collectors.toList()), "on \"ab\"");
    assertEquals(List.of("abcdef"), labels(complete(path, text, 2, 13)), "on \"abcd\"");
  }

  /** A field, a method, a parameter and a local variable, on a name typed in part and on a whole name. */
  @Test
  void namesInScopeCompleteFromAPartOrAllOfTheName() {
    String path = tmp.resolve("A.groovy").toString();
    String field = "class A {\n  String memberVar\n  A() {\n    %s\n  }\n}\n";
    assertEquals(Completion.Kind.FIELD, kindOf(path, String.format(field, "mem"), 3, 7, "memberVar"));
    assertEquals(Completion.Kind.FIELD, kindOf(path, String.format(field, "memberVar"), 3, 7, "memberVar"));
    String method = "class A {\n  String memberMethod() {}\n  A() {\n    %s\n  }\n}\n";
    assertEquals(Completion.Kind.METHOD, kindOf(path, String.format(method, "mem"), 3, 7, "memberMethod"));
    assertEquals(Completion.Kind.METHOD, kindOf(path, String.format(method, "memberMethod"), 3, 7, "memberMethod"));
    String parameter = "class A {\n  void run(String paramName) {\n    %s\n  }\n}\n";
    assertEquals(Completion.Kind.VARIABLE, kindOf(path, String.format(parameter, "par"), 2, 7, "paramName"));
    assertEquals(Completion.Kind.VARIABLE, kindOf(path, String.format(parameter, "paramName"), 2, 13, "paramName"));
    String local = "class A {\n  void run() {\n    String localVar\n    %s\n  }\n}\n";
    assertEquals(Completion.Kind.VARIABLE, kindOf(path, String.format(local, "loc"), 3, 7, "localVar"));
    assertEquals(Completion.Kind.VARIABLE, kindOf(path, String.format(local, "localVar"), 3, 12, "localVar"));
    String inBlock = "class A {\n  void run() {\n    String localVar\n    if (true) {\n      loc\n    }\n  }\n}\n";
    assertEquals(Completion.Kind.VARIABLE, kindOf(path, inBlock, 4, 9, "localVar"));
  }

  @Test
  void theDocumentsOwnClassCompletesWithItsPackage() {
    String path = tmp.resolve("src/com/example/Completion.groovy").toString();
    CompletionList list = complete(path, "package com.example\nclass Completion {\n  Completion() {\n    Completio\n  }\n}\n", 3, 13);
    Completion own = list.items.stream().filter(c -> c.label.equals("Completion")).findFirst().orElseThrow();
    assertEquals(Completion.Kind.CLASS, own.kind);
    assertEquals("com.example", own.detail);
    assertEquals(List.of(), own.additionalEdits, "no import for the document's own class");
  }

  @Test
  void resolveGivesTheSignatureAndDocumentationUntilACompileOfAnotherText() {
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
    assertEquals("public int count(String what)", analyzer.resolveCompletion(path, count.id).detail, "a compile of the same text");
    compile(path, text.replace("cou\n", "coun\n"));
    assertNull(analyzer.resolveCompletion(path, count.id));
  }

  /**
   * Completion right after a dot answers before the compile of its text (CompileService), whose text does not parse.
   * The client still shows the items after that compile, so they still resolve.
   */
  @Test
  void aCompletionAnsweredBeforeTheCompileOfItsTextResolvesAfterIt() {
    String path = tmp.resolve("A.groovy").toString();
    String template = "class A {\n  String name\n  def run() {\n    %s\n  }\n}\n";
    compile(path, String.format(template, "name"));
    String typed = String.format(template, "name.");
    CompletionList list = analyzer.completion(new CompileRequest(path, typed, lookup, () -> false), 3, 9);
    Completion upper = list.items.stream().filter(c -> c.label.equals("toUpperCase")).findFirst().orElseThrow();
    assertTrue(analyzer.resolveCompletion(path, upper.id).detail.startsWith("public String toUpperCase("));
    compile(path, typed);
    CompletionDetail detail = analyzer.resolveCompletion(path, upper.id);
    assertNotNull(detail, "the compile of the completion's text keeps it");
    assertTrue(detail.detail.startsWith("public String toUpperCase("), detail.detail);
    compile(path, String.format(template, "name.t"));
    assertNull(analyzer.resolveCompletion(path, upper.id), "the compile of another text drops it");
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

  /** The import edit of each class whose name starts with {@code Gad} at a position, by qualified name. */
  private Map<String, String> importEdits(String path, String text, int line, int column) {
    Map<String, String> edits = new TreeMap<>();
    for (Completion item : complete(path, text, line, column).items) {
      if (item.label.startsWith("Gad")) {
        edits.put(item.detail == null || item.detail.isEmpty() ? item.label : item.detail + "." + item.label, item.additionalEdits.toString());
      }
    }
    return edits;
  }

  /** No import for a class the file sees already: its own package, a star import, a Groovy default import, the default package. */
  @Test
  void aClassTheFileSeesAlreadyComesWithoutAnImport() {
    for (String name : List.of("app.Gadget", "other.Gadgets", "java.util.GadgetList", "groovy.lang.GadgetMeta", "GadgetTop", "far.Gadgetry")) {
      types.add(new Symbol(name, Symbol.Kind.CLASS, null, new Range(0, 0, 0, 10)));
    }
    String path = tmp.resolve("src/app/A.groovy").toString();
    Map<String, String> expected = new TreeMap<>();
    for (String name : List.of("app.Gadget", "other.Gadgets", "java.util.GadgetList", "groovy.lang.GadgetMeta", "GadgetTop")) {
      expected.put(name, "[]");
    }
    expected.put("far.Gadgetry", "[3:1-3:1 import far.Gadgetry\n]");
    assertEquals(expected, importEdits(path, "package app\nimport other.*\n\nclass A {\n  def run() {\n    Gad\n  }\n}\n", 5, 7));
  }

  /**
   * The import goes on the line after the last import, else after the package line, else below a shebang and a header
   * comment, never above them; a doc comment right above the class stays with it. Groovy 2.5 may give an import node
   * the next import's position: every Groovy runs this.
   */
  @Test
  void anImportGoesBelowTheImportsThePackageOrTheFilesHeader() {
    types.add(new Symbol("far.Gadget", Symbol.Kind.CLASS, null, new Range(0, 0, 0, 10)));
    String path = tmp.resolve("src/A.groovy").toString();
    String body = "class A {\n  def run() {\n    Gad\n  }\n}\n";
    Map<String, String> found = new TreeMap<>();
    Map<String, String> expected = new TreeMap<>();
    String[][] cases = {
      { "imports", "package app\nimport java.io.File\nimport java.net.URL\n\n", "4:1" },
      { "one import", "package app\n\nimport java.io.File\n", "4:1" },
      { "static and star imports", "package app\nimport java.io.File\nimport static java.util.Collections.emptyList\nimport java.nio.file.*\n\n", "5:1" },
      { "package", "package app\n\n", "2:1" },
      { "nothing", "", "1:1" },
      { "shebang", "#!/usr/bin/env groovy\n", "2:1" },
      { "licence", "/*\n * Licensed under the Apache License.\n */\n\n", "4:1" },
      { "line comments", "// Copyright someone\n// SPDX-License-Identifier: MIT\n", "3:1" },
      { "shebang and licence", "#!/usr/bin/env groovy\n/* Licensed under the Apache License. */\n\n", "3:1" },
      { "doc comment", "/** An A. */\n", "1:1" },
      { "licence and doc comment", "/* Licensed under the Apache License. */\n\n/**\n * An A.\n */\n", "3:1" },
    };
    for (String[] c : cases) {
      int line = (int) c[1].chars().filter(ch -> ch == '\n').count() + 2;
      found.put(c[0], importEdits(path, c[1] + body, line, 7).get("far.Gadget"));
      expected.put(c[0], "[" + c[2] + "-" + c[2] + " import far.Gadget\n]");
    }
    assertEquals(expected, found);
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
   * In statically compiled code the type checker infers {@code Class<A>} for a class name. As a receiver, the class
   * name still stands for the class: {@code A.} offers its static members, and hover, definition, and signature help
   * find them.
   */
  @Test
  void aClassNameInStaticallyCompiledCodeIsItsClass() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "@groovy.transform.CompileStatic\nclass A {\n  static int COUNT = 1\n  static int count(int n) { n }\n  int size() { 2 }\n"
        + "  def run() {\n    A.count(A.COUNT)\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
    assertEquals(Phases.INSTRUCTION_SELECTION, analyzer.phase(path));
    assertNotNull(analyzer.hover(path, 6, 15), "hover on COUNT");
    assertEquals("int COUNT", analyzer.hover(path, 6, 15).split("\n")[1]);
    assertEquals(2, analyzer.definition(path, 6, 15).range.startLine, "definition of COUNT");
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 6, 12);
    assertNotNull(help, "signature help in A.count(");
    assertEquals("static int count(int n)", help.signatures.get(0).label);

    List<String> labels = labels(complete(path, text.replace("    A.count", "    A.\n    A.count"), 6, 6));
    assertTrue(labels.containsAll(List.of("COUNT", "count")), labels.toString());
    assertFalse(labels.contains("size") || labels.contains("forName"), "instance members and Class's statics: " + labels);
  }

  /**
   * In statically compiled code, a call that leaves out a default argument targets an overload the type checker makes
   * for it, with no position and no doc. Hover and definition show the method it was made from, as in dynamic code.
   */
  @Test
  void aCallThatLeavesOutADefaultArgumentFindsTheMethodItWasMadeFrom() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "@groovy.transform.CompileStatic\nclass A {\n  /** Reads a file. */\n  static int read(Map options = [:], File file) { 1 }\n"
        + "  static int read(Map options = [:], String name) { 2 }\n  /** Shifts. */\n  int shift(int periods = 1, int step = 2) { periods }\n"
        + "  /** Makes one. */\n  A(int size = 1) {}\n  def run(File file) {\n"
        + "    A.read(file) + read('x') + new A().shift() + new A().shift(3)\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
    String hover = analyzer.hover(path, 10, 7);
    assertNotNull(hover, "hover on read in A.read(file)");
    assertTrue(hover.split("\n")[1].endsWith("int read(Map options, File file)"), hover);
    assertTrue(hover.contains("Reads a file."), hover);
    assertEquals(3, analyzer.definition(path, 10, 7).range.startLine, "definition of read in A.read(file)");
    assertEquals(4, analyzer.definition(path, 10, 20).range.startLine, "definition of read in read('x')");
    hover = analyzer.hover(path, 10, 40);
    assertTrue(hover.split("\n")[1].endsWith("int shift(int periods, int step)"), hover);
    assertTrue(hover.contains("Shifts."), hover);
    assertEquals(6, analyzer.definition(path, 10, 40).range.startLine, "definition of shift in shift()");
    assertEquals(6, analyzer.definition(path, 10, 58).range.startLine, "definition of shift in shift(3)");
    assertEquals(8, analyzer.definition(path, 10, 32).range.startLine, "definition of new A()");
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 10, 11);
    assertEquals(2, help.signatures.size(), "signature help in A.read(");
  }

  /**
   * The type checker runs for every annotation that runs it ({@code @TypeChecked}, Grails's {@code @GrailsTypeChecked}
   * and {@code @GrailsCompileStatic} too), under an aliased import, and on a constructor.
   */
  @Test
  void typeCheckedCodeGoesToTheTypeChecker() {
    String path = tmp.resolve("A.groovy").toString();
    Map<String, String> sources = new java.util.LinkedHashMap<>();
    sources.put("TypeChecked", "@groovy.transform.TypeChecked\nclass A {\n  int run() { 'x'.nope() }\n}\n");
    sources.put("aliased", "import groovy.transform.CompileStatic as Checked\n@Checked\nclass A {\n  int run() { 'x'.nope() }\n}\n");
    sources.put("aliased TypeChecked", "import groovy.transform.TypeChecked as TC\nclass A {\n  @TC int run() { 'x'.nope() }\n}\n");
    sources.put("constructor", "class A {\n  @groovy.transform.CompileStatic\n  A() { 'x'.nope() }\n}\n");
    Map<String, Integer> found = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, String> source : sources.entrySet()) {
      found.put(source.getKey(), errors(compile(path, source.getValue())).size());
    }
    assertEquals(Map.of("TypeChecked", 1, "aliased", 1, "aliased TypeChecked", 1, "constructor", 1), found);
    // Grails's annotations, by name (no Grails here: an annotation of that name stands in)
    compile(path, "@interface GrailsTypeChecked {}\n@GrailsTypeChecked\nclass A {}\n");
    assertEquals(Phases.INSTRUCTION_SELECTION, analyzer.phase(path));
  }

  /**
   * A source set's configuration script (Gradle's {@code groovyOptions.configurationScript}) runs on each compile, as
   * {@code groovyc --configscript} runs it: {@code ast(CompileStatic)} makes the document statically compiled without
   * an annotation. Its imports apply to the classes pulled in from source too, but its type checker does not.
   */
  @Test
  void aConfigurationScriptRunsOnEachCompile() throws IOException {
    String script = write("config/compileStatic.groovy", "import groovy.transform.CompileStatic\nwithConfig(configuration) {\n  ast(CompileStatic)\n"
        + "  imports {\n    normal 'java.util.concurrent.TimeUnit'\n  }\n}\n");
    index.put("B", write("src/B.groovy", "class B {\n  TimeUnit unit() { TimeUnit.SECONDS }\n  def count() { this.nope() }\n}\n"));
    String path = tmp.resolve("src/A.groovy").toString();
    String text = "class A {\n  long run(B b) {\n    List<String> names = []\n    int cols = names\n    b.unit().toSeconds(1)\n  }\n}\n";
    try (GroovyAnalyzer configured = new GroovyAnalyzer(getClass().getClassLoader(), (show, message) -> System.err.println(message), script)) {
      List<String> errors = errors(configured.compile(new CompileRequest(path, text, lookup, () -> false)));
      assertEquals(1, errors.size(), errors.toString());
      // flow typing: names holds an ArrayList, written java.util.ArrayList <String> before Groovy 4
      assertTrue(errors.get(0).matches(".*:4:16: \\[Static type checking\\] - Cannot assign value of type java.util.ArrayList ?<(java.lang.)?String> to variable of type int"), errors.get(0));
      assertEquals(Phases.INSTRUCTION_SELECTION, configured.phase(path));
      CompileUnit classes = configured.unit(path).getAST();
      assertEquals(Boolean.TRUE, classes.getClass("A").getNodeMetaData(StaticCompilationMetadataKeys.STATIC_COMPILE_NODE));
      assertNull(classes.getClass("B").getNodeMetaData(StaticCompilationMetadataKeys.STATIC_COMPILE_NODE), "B is pulled in, not checked");
    }
    // without the script, the same text is dynamic (B's TimeUnit does not resolve then)
    assertEquals(List.of(), errors(compile(path, text)).stream().filter(e -> e.startsWith(path)).collect(Collectors.toList()));
    assertEquals(Phases.CANONICALIZATION, analyzer.phase(path));
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

  /**
   * Only the classes a file declares are class symbols, not the types it extends or implements; a script's class and
   * an anonymous class are not declared by name.
   */
  @Test
  void classSymbolsAreTheClassesAFileDeclares() throws IOException {
    String script = write("src/p/Run.groovy", "package p\nprintln 'hi'\ndef helper() {}\n");
    String types = write("src/p/C.groovy", "package p\nclass C {\n  def r = new Runnable() { void run() {} }\n}\n"
        + "enum E { X { void f() {} } }\nclass D extends C implements Runnable, Serializable { void run() {} }\n");
    Map<String, List<Symbol>> found = analyzer.declarations(List.of(script, types));
    Function<String, List<String>> classes = path -> found.get(path).stream().filter(s -> s.container == null)
        .map(s -> s.name + (s.named ? "" : " (unnamed)")).sorted().collect(Collectors.toList());
    assertEquals(List.of("p.Run (unnamed)"), classes.apply(script));
    assertEquals(List.of("p.C", "p.C$1 (unnamed)", "p.D", "p.E", "p.E$1 (unnamed)"), classes.apply(types));
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

  /** After {@code show('x', }, with or without a statement on the next line, signature help is of the copy {@code show('x', )}. */
  @Test
  void signatureHelpAfterAnArgumentAndAComma() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "class A {\n  void show(Map options) {}\n  void show(String text, int times) {}\n  def run() {\n    show('x', \n  }\n}\n";
    compile(path, text);
    SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 4, 14);
    assertNotNull(help);
    assertEquals(1, help.activeSignature);
    assertEquals(1, help.activeParameter);
    String next = text.replace("show('x', \n", "show('x',\n    println 'y'\n");
    compile(path, next); // Groovy 2.5 keeps a partial AST of what it could not parse: a list, not the call
    SignatureHelp before = analyzer.signatureHelp(new CompileRequest(path, next, lookup, () -> false), 4, 13);
    assertNotNull(before, "a statement on the next line");
    assertEquals(1, before.activeParameter);
  }

  /**
   * Groovy reports the static type checker's errors and unresolved classes as syntax errors too, after the text
   * parsed: signature help answers from the compile of such a text, as from one without errors.
   */
  @Test
  void signatureHelpInATextWithATypeCheckerErrorOrAnUnresolvedClass() {
    String path = tmp.resolve("A.groovy").toString();
    for (String error : List.of("    int n = 'x'.toList()\n", "    Nope nope = null\n")) {
      String text = "@groovy.transform.CompileStatic\nclass A {\n  void show(String text, int times) {}\n  def run() {\n    show('x', 2)\n"
          + error + "  }\n}\n";
      assertEquals(1, errors(compile(path, text)).size(), error);
      SignatureHelp help = analyzer.signatureHelp(new CompileRequest(path, text, lookup, () -> false), 4, 14);
      assertNotNull(help, error);
      assertEquals(1, help.activeParameter);
    }
  }

  /** A class of the document's kept compile, by name: its own or pulled in. */
  private ClassNode keptClass(String path, String className) {
    return analyzer.unit(path).getAST().getClass(className);
  }

  /**
   * A compile reuses the sources the document's last compile pulled in, when they have not changed: with the same
   * diagnostics (a pulled-in file's included), completion and hover as a compile that reads them again.
   */
  @Test
  void aCompileReusesTheUnchangedSourcesTheLastCompilePulledIn() throws IOException {
    index.put("lib.Book", write("src/lib/Book.groovy", "package lib\nclass Book {\n  String title\n  Unknown unknown\n  /** Its pages. */\n  int pages() { 1 }\n}\n"));
    String path = tmp.resolve("src/web/Shop.groovy").toString();
    String text = "package web\nimport lib.Book\nclass Shop {\n  Missing m\n  def run(Book book) {\n    book.pages()\n  }\n}\n";
    compile(path, text);
    ClassNode book = keptClass(path, "lib.Book");
    String edited = text.replace("book.pages()\n", "book.pages()\n    book.ti\n");
    CompileResult second = compile(path, edited);
    assertSame(book, keptClass(path, "lib.Book"), "Book.groovy reused");

    try (GroovyAnalyzer fresh = new GroovyAnalyzer()) {
      CompileResult expected = fresh.compile(new CompileRequest(path, edited, lookup, () -> false));
      assertEquals(2, expected.diagnostics.size(), expected.diagnostics.toString());
      assertEquals(expected.diagnostics.toString(), second.diagnostics.toString());
      assertEquals(expected.sources, second.sources);
      assertTrue(fresh.hover(path, 5, 10).contains("Its pages."), fresh.hover(path, 5, 10));
      assertEquals(fresh.hover(path, 5, 10), analyzer.hover(path, 5, 10));
      for (int column : new int[] { 9, 11 }) {
        CompileRequest request = new CompileRequest(path, edited, lookup, () -> false);
        List<String> labels = labels(fresh.completion(request, 6, column));
        assertTrue(labels.contains("title"), labels.toString());
        assertEquals(labels, labels(analyzer.completion(request, 6, column)));
      }
    }
  }

  /** A pulled-in file changed since the last compile, in its open text or on disk, is compiled again. */
  @Test
  void anEditedPulledInFileIsCompiledAgain() throws IOException {
    String book = write("src/lib/Book.groovy", "package lib\nclass Book {\n  String title\n}\n");
    index.put("lib.Book", book);
    String path = tmp.resolve("src/web/Shop.groovy").toString();
    String text = "package web\nimport lib.Book\nclass Shop {\n  Book book\n}\n";
    compile(path, text);
    ClassNode first = keptClass(path, "lib.Book");

    open.put(book, "package lib\nclass Book {\n  String title\n  String author\n}\n");
    compile(path, text);
    ClassNode opened = keptClass(path, "lib.Book");
    assertNotSame(first, opened);
    assertNotNull(opened.getProperty("author"), "open text");
    compile(path, text);
    assertSame(opened, keptClass(path, "lib.Book"), "control: unchanged");

    open.remove(book);
    compile(path, text);
    ClassNode closed = keptClass(path, "lib.Book");
    assertNotSame(opened, closed);
    assertNull(closed.getProperty("author"), "closed: from disk");

    Files.writeString(Path.of(book), "package lib\nclass Book {\n  String title\n  String isbn\n}\n");
    Files.setLastModifiedTime(Path.of(book), FileTime.fromMillis(Files.getLastModifiedTime(Path.of(book)).toMillis() + 10_000));
    compile(path, text);
    assertNotNull(keptClass(path, "lib.Book").getProperty("isbn"), "file changed");
  }

  /**
   * A pulled-in file that names a class the document declares (or declared in the last compile) is compiled again:
   * its references point at the document's classes of the compile that converted it.
   */
  @Test
  void aPulledInFileThatNamesADocumentClassIsCompiledAgain() throws IOException {
    String path = tmp.resolve("src/web/Shop.groovy").toString();
    index.put("lib.Book", write("src/lib/Book.groovy", "package lib\nclass Book {\n  web.Shop shop\n}\n"));
    index.put("web.Shop", path);
    String text = "package web\nimport lib.Book\nclass Shop {\n  Book book\n}\n";
    compile(path, text);
    ClassNode first = keptClass(path, "lib.Book");
    compile(path, text.replace("Book book", "Book book\n  int count"));
    ClassNode book = keptClass(path, "lib.Book");
    assertNotSame(first, book);
    assertSame(keptClass(path, "web.Shop"), book.getProperty("shop").getType().redirect(), "the document's class of this compile");
  }

  /** Completion's compile of a repaired copy reuses the sources of the document's compile. */
  @Test
  void aCopyCompiledForCompletionReusesThePulledInSources() throws IOException {
    index.put("lib.Book", write("src/lib/Book.groovy", "package lib\nclass Book {\n  String title\n}\n"));
    String path = tmp.resolve("src/web/Shop.groovy").toString();
    String text = "package web\nimport lib.Book\nclass Shop {\n  def run(Book book) {\n    \n  }\n}\n";
    compile(path, text);
    ClassNode book = keptClass(path, "lib.Book");
    String typed = text.replace("    \n", "    book.\n");
    compile(path, typed);
    List<String> labels = labels(analyzer.completion(new CompileRequest(path, typed, lookup, () -> false), 4, 9));
    assertTrue(labels.contains("title"), labels.toString());
    GroovyAnalyzer.Kept copy = analyzer.completions.get(path).compile;
    assertNotSame(analyzer.units.get(path), copy, "answered from a copy");
    assertSame(book, copy.unit.getAST().getClass("lib.Book"));
  }

  /**
   * Sources compiled to another last phase are compiled again: a compile to instruction selection would run its
   * phase on the reused classes, and one to canonicalization would keep what that phase added to them.
   */
  @Test
  void aCompileToAnotherLastPhaseCompilesThePulledInSourcesAgain() throws IOException {
    index.put("lib.Book", write("src/lib/Book.groovy", "package lib\nclass Book {\n  String title\n}\n"));
    String path = tmp.resolve("src/web/Shop.groovy").toString();
    String dynamic = "package web\nimport lib.Book\nclass Shop {\n  Book book\n}\n";
    String checked = dynamic.replace("class Shop", "@groovy.transform.CompileStatic\nclass Shop");
    compile(path, dynamic);
    ClassNode first = keptClass(path, "lib.Book");
    compile(path, checked);
    assertEquals(Phases.INSTRUCTION_SELECTION, analyzer.phase(path));
    ClassNode second = keptClass(path, "lib.Book");
    assertNotSame(first, second);
    compile(path, checked);
    assertSame(second, keptClass(path, "lib.Book"), "control: the same phase");
    compile(path, dynamic);
    assertNotSame(second, keptClass(path, "lib.Book"));
  }

  /** The configuration script's imports apply to the pulled-in sources: when the script changes, they are compiled again. */
  @Test
  void aChangedConfigurationScriptCompilesThePulledInSourcesAgain() throws IOException {
    String script = write("config/imports.groovy", "withConfig(configuration) {\n  imports {\n    normal 'java.util.concurrent.TimeUnit'\n  }\n}\n");
    String b = write("src/B.groovy", "class B {\n  TimeUnit unit() { TimeUnit.SECONDS }\n}\n");
    index.put("B", b);
    String path = tmp.resolve("src/A.groovy").toString();
    String text = "class A {\n  def run(B b) {\n    b.unit()\n  }\n}\n";
    try (GroovyAnalyzer configured = new GroovyAnalyzer(getClass().getClassLoader(), (show, message) -> System.err.println(message), script)) {
      assertEquals(List.of(), errors(configured.compile(new CompileRequest(path, text, lookup, () -> false))));
      Files.writeString(Path.of(script), "withConfig(configuration) {\n}\n");
      Files.setLastModifiedTime(Path.of(script), FileTime.fromMillis(Files.getLastModifiedTime(Path.of(script)).toMillis() + 10_000));
      List<String> errors = errors(configured.compile(new CompileRequest(path, text, lookup, () -> false)));
      assertTrue(errors.stream().anyMatch(e -> e.startsWith(b) && e.contains("TimeUnit")), errors.toString());
    }
  }
}
