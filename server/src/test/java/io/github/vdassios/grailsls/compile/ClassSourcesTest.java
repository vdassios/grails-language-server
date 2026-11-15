package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassSourcesTest {
  @TempDir
  Path tmp;

  /** A sources jar with these entries (name, text, ...). */
  private static Path sourcesJar(Path file, String... entries) throws IOException {
    Files.createDirectories(file.getParent());
    try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
      for (int i = 0; i < entries.length; i += 2) {
        jar.putNextEntry(new JarEntry(entries[i]));
        jar.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
      }
    }
    return file;
  }

  private static URL classFile(Path jar, String entry) throws IOException {
    return new URL("jar:" + jar.toUri() + "!/" + entry);
  }

  private ClassSources sources() {
    return new ClassSources(tmp.resolve("cache/sources"));
  }

  /** Gradle keeps each file of a module version in a directory named by its hash: the sources jar is in a sibling. */
  @Test
  void aSourcesJarInGradlesCacheIsFoundInASiblingHashDirectory() throws IOException {
    Path version = tmp.resolve("files-2.1/org.example/lib/1.0");
    Path jar = JarIndexTest.jar(version.resolve("0a1b2c/lib-1.0.jar"), new String[] {"a/B.class", "a/G.class"});
    sourcesJar(version.resolve("9f8e7d/lib-1.0-sources.jar"), "a/B.java", "package a;\nclass B {}\n", "a/G.groovy", "package a\nclass G {}\n");
    JarIndexTest.jar(version.resolve("77aa/lib-1.0.pom.jar"), new String[] {});

    Path java = sources().sourceOf("a.B", classFile(jar, "a/B.class"));
    assertEquals(tmp.resolve("cache/sources/lib-1.0-sources/a/B.java"), java);
    assertEquals("package a;\nclass B {}\n", Files.readString(java));
    assertFalse(Files.isWritable(java), "a library's source is not for editing");
    assertEquals(tmp.resolve("cache/sources/lib-1.0-sources/a/G.groovy"), sources().sourceOf("a.G", classFile(jar, "a/G.class")));
    assertNull(sources().sourceOf("a.Missing", classFile(jar, "a/Missing.class")), "not in the sources jar");
  }

  @Test
  void aSourcesJarBesideTheJarIsFoundAndItsFileExtractedOnce() throws IOException {
    Path jar = JarIndexTest.jar(tmp.resolve("m2/lib/2.0/lib-2.0.jar"), new String[] {"a/B.class"});
    sourcesJar(tmp.resolve("m2/lib/2.0/lib-2.0-sources.jar"), "a/B.java", "class B {}\n");
    Path first = sources().sourceOf("a.B", classFile(jar, "a/B.class"));
    assertNotNull(first);
    Object written = Files.getAttribute(first, "fileKey");
    assertEquals(first, sources().sourceOf("a.B", classFile(jar, "a/B.class")));
    assertEquals(written, Files.getAttribute(first, "fileKey"), "not written again");
  }

  @Test
  void aJarWithoutSourcesOnDiskHasNone() throws IOException {
    Path jar = JarIndexTest.jar(tmp.resolve("files-2.1/org.example/lib/1.0/0a1b2c/lib-1.0.jar"), new String[] {"a/B.class"});
    assertNull(sources().sourceOf("a.B", classFile(jar, "a/B.class")));
    assertNull(sources().sourceOf("a.B", tmp.resolve("classes/a/B.class").toUri().toURL()), "a class directory");
  }

  /** As the module loader finds JDK classes: through its parent, the platform loader. */
  @Test
  void aJdkClassComesFromTheJdksSourceArchive() throws IOException {
    URL classFile = ClassLoader.getPlatformClassLoader().getResource("java/util/ArrayList.class");
    assertNotNull(classFile);
    Path source = sources().sourceOf("java.util.ArrayList", classFile);
    if (Files.isRegularFile(Path.of(System.getProperty("java.home"), "lib", "src.zip"))) {
      assertNotNull(source);
      assertTrue(source.endsWith(Path.of("jdk-" + System.getProperty("java.version"), "java.base/java/util/ArrayList.java")), source.toString());
      assertTrue(Files.readString(source).contains("class ArrayList<E>"));
    } else {
      assertNull(source, "a JDK installed without its sources");
    }
  }
}
