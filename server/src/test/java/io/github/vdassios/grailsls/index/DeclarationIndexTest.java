package io.github.vdassios.grailsls.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
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

  /** Only the modules' build directories are excluded: a package may be named like any build or tool directory. */
  @Test
  void onlyTheBuildDirectoriesAreNeverSource() throws IOException {
    Path root = tmp.resolve("app");
    Path real = write("app/p/A.groovy", "package p\nclass A {}\n");
    write("app/build/p/A.groovy", "package p\nclass A {}\nclass Copy {}\n");
    for (String dir : List.of("bin", "out", "node_modules")) {
      write("app/p/" + dir + "/" + dir + "Class.groovy", "package p." + dir + "\nclass " + dir + "Class {}\n");
    }
    DeclarationIndex index = new DeclarationIndex(Set.of(root.resolve("build")));
    index.addRoots(List.of(root));
    assertEquals(real, index.fileOf("p.A", List.of(root)));
    assertNull(index.fileOf("p.Copy", List.of(root)));
    assertEquals(tmp.resolve("app/p/bin/binClass.groovy"), index.fileOf("p.bin.binClass", List.of(root)));
    assertEquals(4, index.files());
    index.update(tmp.resolve("app/build/p/A.groovy"));
    assertEquals(4, index.files(), "an update below the build directory is ignored");
    Path build = write("app/p/build/B.groovy", "package p.build\nclass B {}\n");
    index.update(build);
    assertEquals(build, index.fileOf("p.build.B", List.of(root)), "a package named build is source");
  }

  @Test
  void searchRootsAreTriedInOrder() throws IOException {
    Path first = tmp.resolve("one");
    Path second = tmp.resolve("two");
    write("one/p/A.groovy", "package p\nclass A {}\n");
    Path b = write("two/p/B.groovy", "package p\nclass A {}\nclass B {}\n");
    DeclarationIndex index = new DeclarationIndex(Set.of());
    index.addRoots(List.of(first, second));
    assertEquals(first.resolve("p/A.groovy"), index.fileOf("p.A", List.of(first, second)));
    assertEquals(b, index.fileOf("p.A", List.of(second, first)));
    assertNull(index.fileOf("p.B", List.of(first)), "only the roots asked for");
  }

  @Test
  void updatesFollowEditsAndDeletes() throws IOException {
    Path root = tmp.resolve("src");
    Path file = write("src/p/A.groovy", "package p\nclass A {}\n");
    DeclarationIndex index = new DeclarationIndex(Set.of());
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
    DeclarationIndex index = new DeclarationIndex(Set.of());
    index.addRoots(List.of(first, second));
    List<String> found = index.classesNamed("BookService", List.of(first, second));
    assertEquals("a.BookService", found.get(0), "the first root first");
    assertEquals(List.of("BookService", "a.BookService", "b.BookService"), found.stream().sorted().collect(Collectors.toList()));
    assertEquals(List.of(), index.classesNamed("BookService2", List.of(first)), "nested types are not top-level");
    assertEquals(List.of("a.BookService"), index.classesNamed("BookService", List.of(first)), "only the roots asked for");
  }

  /** A rename delivered as create-then-delete: both files declare the class for a moment, and it must survive the delete. */
  @Test
  void aRenameDeliveredAsCreateThenDeleteKeepsTheClass() throws IOException {
    Path root = tmp.resolve("src");
    Path old = write("src/p/A.groovy", "package p\nclass A {}\n");
    DeclarationIndex index = new DeclarationIndex(Set.of());
    index.addRoots(List.of(root));

    Path moved = write("src/p/sub/A.groovy", "package p\nclass A {}\n");
    index.update(moved);
    Files.delete(old);
    index.update(old);
    assertEquals(moved, index.fileOf("p.A", List.of(root)));
    assertEquals(1, index.files());
  }

  /** A deleted directory is one event: every file below it goes. A directory created with files in it is scanned. */
  @Test
  void aDeletedDirectoryTakesItsFilesAndACreatedOneIsScanned() throws IOException {
    Path root = tmp.resolve("src");
    write("src/p/Keep.groovy", "package p\nclass Keep {}\n");
    write("src/p/gone/A.groovy", "package p.gone\nclass A {}\n");
    write("src/p/gone/deeper/B.groovy", "package p.gone.deeper\nclass B {}\n");
    DeclarationIndex index = new DeclarationIndex(Set.of());
    index.addRoots(List.of(root));
    assertEquals(3, index.files());

    Path gone = root.resolve("p/gone");
    Files.delete(gone.resolve("deeper/B.groovy"));
    Files.delete(gone.resolve("deeper"));
    Files.delete(gone.resolve("A.groovy"));
    Files.delete(gone);
    index.update(gone);
    assertNull(index.fileOf("p.gone.A", List.of(root)));
    assertNull(index.fileOf("p.gone.deeper.B", List.of(root)));
    assertEquals(1, index.files());

    Path c = write("src/p/made/C.groovy", "package p.made\nclass C {}\n");
    index.update(root.resolve("p/made"));
    assertEquals(c, index.fileOf("p.made.C", List.of(root)));
    assertEquals(2, index.files());
  }

  /** A rescan finds what changed on disk without any event: created, edited and deleted files. */
  @Test
  void aRescanAppliesWhatChangedOnDisk() throws IOException {
    Path root = tmp.resolve("src");
    Path edited = write("src/p/A.groovy", "package p\nclass A {}\n");
    Path deleted = write("src/p/B.groovy", "package p\nclass B {}\n");
    write("src/p/Same.groovy", "package p\nclass Same {}\n");
    DeclarationIndex index = new DeclarationIndex(Set.of());
    index.addRoots(List.of(root));

    Files.writeString(edited, "package p\nclass A2 {}\n");
    Files.setLastModifiedTime(edited, java.nio.file.attribute.FileTime.fromMillis(Files.getLastModifiedTime(edited).toMillis() + 2000));
    Files.delete(deleted);
    Path created = write("src/q/C.groovy", "package q\nclass C {}\n");
    DeclarationIndex.Changes changes = index.rescan();
    assertEquals(Set.of(edited, deleted, created), Set.copyOf(changes.files), "only the files that changed are read again");
    assertTrue(changes.declarations);
    assertNull(index.fileOf("p.A", List.of(root)));
    assertEquals(edited, index.fileOf("p.A2", List.of(root)));
    assertNull(index.fileOf("p.B", List.of(root)));
    assertEquals(created, index.fileOf("q.C", List.of(root)));
    assertEquals(List.of(), index.rescan().files, "nothing changed since");
  }

  /** A parse corrects what the text scan got wrong (a slashy string is read as code), until the file changes again. */
  @Test
  void aParseReplacesTheNamesTheScanFound() throws IOException {
    Path root = tmp.resolve("src");
    Path file = write("src/p/A.groovy", "package p\nclass A {\n  def glob = /\\/*/\n}\nclass Lost {}\n// */\n");
    DeclarationIndex index = new DeclarationIndex(Set.of());
    index.addRoots(List.of(root));
    assertNull(index.fileOf("p.Lost", List.of(root)), "the scan reads /* in the slashy string as a comment");

    assertTrue(index.parsed(file, List.of("p.A", "p.Lost")));
    assertEquals(file, index.fileOf("p.Lost", List.of(root)));
    assertEquals(false, index.parsed(file, List.of("p.Lost", "p.A")), "the same names in another order change nothing");
    assertEquals(List.of(), index.rescan().files, "the file did not change: the parsed names stay");
    assertEquals(false, index.parsed(tmp.resolve("src/p/Other.groovy"), List.of("p.Other")), "a file not indexed is ignored");
    assertNull(index.fileOf("p.Other", List.of(root)));

    Files.writeString(file, "package p\nclass A {}\n");
    index.update(file);
    assertNull(index.fileOf("p.Lost", List.of(root)), "an edit is scanned again");
  }
}
