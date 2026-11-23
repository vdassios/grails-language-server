package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One read of a jar's entries serves the jar index, import completion and the page lookups alike. */
class JarEntriesTest {
  @TempDir
  Path tmp;

  @Test
  void oneReadGivesEveryUserWhatItNeeds() throws IOException {
    Path file = tmp.resolve("plugin.jar");
    try (OutputStream out = Files.newOutputStream(file); JarOutputStream jar = new JarOutputStream(out)) {
      for (String name : List.of("a/", "a/B.class", "a/B$1.class", "a/b/FormTagLib.class", "a/b/FormTagLib$_closure1.class", "META-INF/assets/app.js",
          "META-INF/assets/img/", "gsp/views.properties")) {
        jar.putNextEntry(new JarEntry(name));
        if (name.equals("gsp/views.properties")) {
          jar.write("/WEB-INF/grails-app/views/login/auth.gsp=gsp_plugin_loginauth_gsp\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        jar.closeEntry();
      }
    }
    JarEntries entries = new JarEntries();
    JarEntries.Jar read = entries.of(file);
    assertSame(read, entries.of(file), "read once");

    assertEquals(Map.of("a", List.of("B"), "a.b", List.of("FormTagLib")), read.classes, "top-level classes by package");
    assertEquals(List.of("a.b.FormTagLib"), read.taglibs);
    assertEquals(List.of("assets/app.js", "views/login/auth.gsp"), read.pluginFiles);
    assertEquals(List.of(), read.classPath);
    for (String name : List.of("a/", "a", "a/B.class", "a/B$1.class", "gsp/views.properties")) {
      assertTrue(Arrays.binarySearch(read.names, name.hashCode()) >= 0, name);
    }
  }

  @Test
  void aFileThatIsNoJarHasNoEntries() throws IOException {
    Path pom = Files.writeString(tmp.resolve("lib.pom"), "<project/>");
    JarEntries.Jar read = new JarEntries().of(pom);
    assertEquals(0, read.names.length);
    assertEquals(Map.of(), read.classes);
    assertEquals(List.of(), read.taglibs);
    assertEquals(List.of(), read.pluginFiles);
  }
}
