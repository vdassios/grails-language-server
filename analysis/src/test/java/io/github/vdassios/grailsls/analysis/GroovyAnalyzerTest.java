package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovySystem;
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
  };
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

  @Test
  void hoverShowsTheSignatureAndOnGroovy3AndLaterTheGroovydoc() {
    String path = tmp.resolve("A.groovy").toString();
    compile(path, "class A {\n  /** Counts things. */\n  int count(String what) { 1 }\n  def run() { count('x') }\n}\n");
    String hover = analyzer.hover(path, 3, 15);
    assertNotNull(hover);
    assertTrue(hover.startsWith("```groovy\npublic int count(String what)\n```"), hover);
    boolean groovydoc = !GroovySystem.getVersion().startsWith("2.");
    assertEquals(groovydoc, hover.contains("Counts things."), hover);
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
    assertEquals(!GroovySystem.getVersion().startsWith("2."), detail.documentation != null && detail.documentation.contains("Counts things."));
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
   * Statically compiled code goes on to instruction selection: the type checker's errors are reported, and the types
   * it infers (closure parameters, flow typing) are what completion and hover use. The other queries still answer.
   */
  @Test
  void staticallyCompiledCodeUsesTheTypesTheCheckerInfers() {
    String path = tmp.resolve("A.groovy").toString();
    String text = "@groovy.transform.CompileStatic\nclass A {\n  String name\n  int run(List<String> names) {\n    names.each { it.toUpperCase() }\n"
        + "    def x\n    x = 'text'\n    x.length() + name.size()\n  }\n}\n";
    assertEquals(List.of(), errors(compile(path, text)));
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
