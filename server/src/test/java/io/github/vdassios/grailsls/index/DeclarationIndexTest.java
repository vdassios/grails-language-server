package io.github.vdassios.grailsls.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeclarationIndexTest {
  @TempDir
  Path tmp;

  private Path write(String relative, String text) throws IOException {
    Path file = tmp.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    return file;
  }

  @Test
  void buildOutputAndToolDirectoriesAreNeverSource() throws IOException {
    Path root = tmp.resolve("src");
    Path real = write("src/p/A.groovy", "package p\nclass A {}\n");
    for (String dir : List.of("build", "bin", "out", ".gradle", "node_modules")) {
      write("src/" + dir + "/p/A.groovy", "package p\nclass A {}\nclass Copy" + dir.replace(".", "") + " {}\n");
    }
    DeclarationIndex index = new DeclarationIndex();
    index.addRoots(List.of(root));
    assertEquals(real, index.fileOf("p.A", List.of(root)));
    assertEquals(1, index.files());
    assertNull(index.fileOf("p.Copybuild", List.of(root)));
    index.update(tmp.resolve("src/build/p/A.groovy"));
    assertEquals(1, index.files(), "an update below build/ is ignored");
  }

  @Test
  void searchRootsAreTriedInOrder() throws IOException {
    Path first = tmp.resolve("one");
    Path second = tmp.resolve("two");
    write("one/p/A.groovy", "package p\nclass A {}\n");
    Path b = write("two/p/B.groovy", "package p\nclass A {}\nclass B {}\n");
    DeclarationIndex index = new DeclarationIndex();
    index.addRoots(List.of(first, second));
    assertEquals(first.resolve("p/A.groovy"), index.fileOf("p.A", List.of(first, second)));
    assertEquals(b, index.fileOf("p.A", List.of(second, first)));
    assertNull(index.fileOf("p.B", List.of(first)), "only the roots asked for");
  }

  @Test
  void updatesFollowEditsAndDeletes() throws IOException {
    Path root = tmp.resolve("src");
    Path file = write("src/p/A.groovy", "package p\nclass A {}\n");
    DeclarationIndex index = new DeclarationIndex();
    index.addRoots(List.of(root));

    Files.writeString(file, "package q\nclass Renamed {}\n");
    index.update(file);
    assertNull(index.fileOf("p.A", List.of(root)));
    assertEquals(file, index.fileOf("q.Renamed", List.of(root)));

    Path created = write("src/p/New.groovy", "package p\nclass New {}\n");
    index.update(created);
    assertEquals(created, index.fileOf("p.New", List.of(root)));

    Files.delete(file);
    index.update(file);
    assertNull(index.fileOf("q.Renamed", List.of(root)));
    assertEquals(1, index.files());
  }

  @Test
  void classesBySimpleNameInAnyPackage() throws IOException {
    Path first = tmp.resolve("one");
    Path second = tmp.resolve("two");
    write("one/a/BookService.groovy", "package a\nclass BookService { enum BookService2 {} }\n");
    write("two/b/Other.groovy", "package b\nclass BookService {}\nclass MyBookService {}\n");
    write("two/Top.groovy", "class BookService {}\n");
    DeclarationIndex index = new DeclarationIndex();
    index.addRoots(List.of(first, second));
    List<String> found = index.classesNamed("BookService", List.of(first, second));
    assertEquals("a.BookService", found.get(0), "the first root first");
    assertEquals(List.of("BookService", "a.BookService", "b.BookService"), found.stream().sorted().collect(Collectors.toList()));
    assertEquals(List.of(), index.classesNamed("BookService2", List.of(first)), "nested types are not top-level");
    assertEquals(List.of("a.BookService"), index.classesNamed("BookService", List.of(first)), "only the roots asked for");
  }
}
