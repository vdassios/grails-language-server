package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarIndexTest {
  @TempDir
  Path tmp;

  /** A jar with these entries (a name ending in a slash is a directory entry) and manifest attributes (name, value, ...). */
  static Path jar(Path file, String[] entries, String... attributes) throws IOException {
    Files.createDirectories(file.getParent());
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    for (int i = 0; i < attributes.length; i += 2) {
      manifest.getMainAttributes().put(new Attributes.Name(attributes[i]), attributes[i + 1]);
    }
    try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out, manifest)) {
      for (String entry : entries) {
        jar.putNextEntry(new JarEntry(entry));
        jar.closeEntry();
      }
    }
    return file;
  }

  private static JarIndex index(Path... entries) throws IOException {
    URL[] urls = new URL[entries.length];
    for (int i = 0; i < entries.length; i++) {
      urls[i] = entries[i].toUri().toURL();
    }
    return JarIndex.of(urls, new JarEntries());
  }

  @Test
  void aNameInNoJarIsAbsent() throws IOException {
    JarIndex index = index(jar(tmp.resolve("a.jar"), new String[] {"a/", "a/B.class", "a/b.properties"}), jar(tmp.resolve("c.jar"), new String[] {"c/D.class"}));
    assertTrue(index.mayHave("a/B.class"));
    assertTrue(index.mayHave("a/b.properties"));
    assertTrue(index.mayHave("c/D.class"));
    assertFalse(index.mayHave("a/C.class"));
    assertFalse(index.mayHave("c/B.class"));
    assertFalse(index.mayHave("java/lang/a/B.class"));
  }

  @Test
  void aDirectoryEntryIsFoundWithAndWithoutItsSlash() throws IOException {
    JarIndex index = index(jar(tmp.resolve("a.jar"), new String[] {"a/", "a/b/", "a/b/C.class"}));
    assertTrue(index.mayHave("a/b/"));
    assertTrue(index.mayHave("a/b"));
  }

  @Test
  void directoriesAreLookedAtOnEveryLookup() throws IOException {
    Path classes = Files.createDirectories(tmp.resolve("classes"));
    JarIndex index = index(classes);
    assertFalse(index.mayHave("a/B.class"));
    Files.createDirectories(classes.resolve("a"));
    Files.writeString(classes.resolve("a/B.class"), "");
    assertTrue(index.mayHave("a/B.class"), "a class the build wrote after the index");
  }

  /** A directory URL (trailing slash) for class output that is not built yet: probed from the first lookup after the build. */
  @Test
  void aClassDirectoryBuiltAfterTheIndexIsLookedAt() throws IOException {
    Path classes = tmp.resolve("build/classes");
    JarIndex index = JarIndex.of(new URL[] {new URL(classes.toUri() + "/")}, new JarEntries());
    assertFalse(index.mayHave("a/B.class"));
    Files.createDirectories(classes.resolve("a"));
    Files.writeString(classes.resolve("a/B.class"), "");
    assertTrue(index.mayHave("a/B.class"));
  }

  @Test
  void jarsNamedInAManifestClassPathAreIndexed() throws IOException {
    jar(tmp.resolve("lib/named.jar"), new String[] {"n/Named.class"});
    Files.createDirectories(tmp.resolve("lib/named-classes/m"));
    Files.writeString(tmp.resolve("lib/named-classes/m/M.class"), "");
    JarIndex index = index(jar(tmp.resolve("lib/main.jar"), new String[] {"a/B.class"}, "Class-Path", "named.jar named-classes/ missing.jar"));
    assertTrue(index.mayHave("n/Named.class"));
    assertTrue(index.mayHave("m/M.class"));
    assertFalse(index.mayHave("n/Other.class"));
  }

  @Test
  void aVersionedEntryOfAMultiReleaseJarIsFoundUnderItsName() throws IOException {
    JarIndex index = index(jar(tmp.resolve("mr.jar"), new String[] {"META-INF/versions/11/v/OnlyVersioned.class"}, "Multi-Release", "true"));
    assertTrue(index.mayHave("v/OnlyVersioned.class"));
    assertTrue(index.mayHave("META-INF/versions/11/v/OnlyVersioned.class"));
  }

  @Test
  void aFileThatIsNoJarIsSkipped() throws IOException {
    Path pom = tmp.resolve("parent.pom");
    Files.writeString(pom, "<project/>");
    JarIndex index = index(pom, jar(tmp.resolve("a.jar"), new String[] {"a/B.class"}), tmp.resolve("missing.jar"));
    assertTrue(index.mayHave("a/B.class"));
    assertFalse(index.mayHave("a/C.class"));
  }

  @Test
  void aUrlThatIsNoFileIsNotIndexed() throws IOException {
    JarIndex index = JarIndex.of(new URL[] {jar(tmp.resolve("a.jar"), new String[] {"a/B.class"}).toUri().toURL(), new URL("http://localhost/lib.jar")}, new JarEntries());
    assertTrue(index.mayHave("a/C.class"), "anything may be behind a URL that is not a file");
  }
}
