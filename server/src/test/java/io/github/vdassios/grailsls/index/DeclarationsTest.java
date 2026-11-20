package io.github.vdassios.grailsls.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class DeclarationsTest {
  @Test
  void topLevelTypesUseThePackageLineNotTheDirectory() {
    assertEquals(List.of("fixture.storage.Shelf"), Declarations.of("// The package differs.\npackage fixture.storage\n\nclass Shelf {\n}\n"));
  }

  @Test
  void everyTopLevelTypeOfAFileIsListed() {
    String text = "package p\n\nclass TitleCase {\n  static String of(String s) { s }\n}\n\nfinal class Initials {}\ninterface Named {}\ntrait Flying {}\nenum Mode { A }\n@interface Marker {}\n";
    assertEquals(List.of("p.TitleCase", "p.Initials", "p.Named", "p.Flying", "p.Mode", "p.Marker"), Declarations.of(text));
  }

  @Test
  void nestedTypesAreBinaryNamesOfTheirOuterType() {
    String text = "package lib\n\nclass Book {\n    enum Format { A, B }\n    static class Page {\n        class Line {}\n    }\n    String title\n    static class Cover {}\n}\nclass Shelf {}\n";
    assertEquals(List.of("lib.Book", "lib.Book$Format", "lib.Book$Page", "lib.Book$Page$Line", "lib.Book$Cover", "lib.Shelf"), Declarations.of(text));
  }

  @Test
  void annotationsAndModifiersBeforeTheKeyword() {
    String text = "package s\n@Transactional\nclass BookService {}\n@CompileStatic @Slf4j(value = 'x') abstract class Base {}\n@groovy.transform.ToString\npublic class Shown {}\n";
    assertEquals(List.of("s.BookService", "s.Base", "s.Shown"), Declarations.of(text));
  }

  @Test
  void commentsAndScriptsDeclareNothing() {
    assertEquals(List.of(), Declarations.of("/*\nclass Hidden {}\n*/\n// class AlsoHidden {}\nprintln 'a script'\n"));
    assertEquals(List.of("Real"), Declarations.of("/* class Hidden */class Real {}\n"));
  }

  @Test
  void genericsAndDefaultPackage() {
    assertEquals(List.of("Box"), Declarations.of("class Box<T extends Comparable<T>> {}\n"));
  }

  @Test
  void anIndentedTypeOutsideEveryBlockIsTopLevel() {
    assertEquals(List.of("u.Cookies", "u.Cookies$Jar"), Declarations.of("package u\nimport a.B\n\n final class Cookies {\n    static class Jar {}\n}\n"));
    assertEquals(List.of("Odd"), Declarations.of("def make() {\n  return '}'\n}\n  class Odd {}\n"), "after a script method, braces in strings ignored");
  }

  @Test
  void anIndentedTypeInsideABlockOfNoTypeIsSkipped() {
    assertEquals(List.of(), Declarations.of("def make = {\n  class Odd {}\n}\n"));
  }

  /** A string holding {@code /*} opens no comment: the classes after it are still found. */
  @Test
  void aStringThatLooksLikeACommentHidesNothing() {
    String text = "package p\nclass Urls {\n  static final String API = \"/api/**\"\n}\nclass Lost {}\n/** Docs. */\nclass After {}\n";
    assertEquals(List.of("p.Urls", "p.Lost", "p.After"), Declarations.of(text));
    assertEquals(List.of("Kept"), Declarations.of("def glob = '/static/*'\nclass Kept {}\n// */\n"));
  }

  /** Source embedded in a multi-line string (a Spock spec's input) declares nothing. */
  @Test
  void classesInsideMultiLineStringsAreNotDeclared() {
    String single = "package s\nclass ParserSpec {\n  def source = '''\nclass Phantom {}\n'''\n}\n";
    assertEquals(List.of("s.ParserSpec"), Declarations.of(single));
    String doubled = "package s\nclass ParserSpec {\n  def source = \"\"\"\npackage other\nclass Phantom { def x = \"${1}\" }\n\"\"\"\n}\nclass Real {}\n";
    assertEquals(List.of("s.ParserSpec", "s.Real"), Declarations.of(doubled));
  }

  /** Quotes in comments open no string, and braces in strings and interpolations do not count. */
  @Test
  void commentsAndStringsAreReadInOnePass() {
    assertEquals(List.of("B"), Declarations.of("// don't\nclass B {}\n"));
    assertEquals(List.of("B", "C"), Declarations.of("/* it's ''' */\nclass B {}\n/* ''' */\nclass C {}\n"));
    assertEquals(List.of("Odd"), Declarations.of("def s = \"${'{'}${[a: '}'].a}\"\n  class Odd {}\n"));
    assertEquals(List.of("Odd"), Declarations.of("def s = 'it\\'s {'\n  class Odd {}\n"), "an escaped quote does not end the string");
    assertEquals(List.of("A", "B"), Declarations.of("class A {\n}\n/* note */ class B {}\n"), "a comment before the keyword nests nothing");
  }

  /** A very long line is read without running out of stack. */
  @Test
  void aLongLineIsRead() {
    String text = "def s = \"" + "x\\\"".repeat(10_000) + "\"\n  class Odd {}\n";
    assertEquals(List.of("Odd"), Declarations.of(text));
    assertEquals(List.of("Long"), Declarations.of("/* " + "*".repeat(20_000) + " */\nclass Long {}\n"));
  }
}
