package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import groovy.lang.GroovyClassLoader;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.CompiledClass;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.SourceLookup;

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
  };

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
}
