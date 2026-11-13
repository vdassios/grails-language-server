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
  void anIndentedTypeWithoutAnOuterTypeIsSkipped() {
    assertEquals(List.of(), Declarations.of("def make() {\n  return 1\n}\n  class Odd {}\n"));
  }
}
