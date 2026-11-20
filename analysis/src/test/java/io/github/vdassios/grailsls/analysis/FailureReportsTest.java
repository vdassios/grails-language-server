package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.SourceLookup;

/** Failures that turn a feature off reach the user once per analyzer (so per module loader); degradations are logged once. */
class FailureReportsTest {
  @TempDir
  Path tmp;

  private final List<String> shown = new CopyOnWriteArrayList<>();
  private final List<String> logged = new CopyOnWriteArrayList<>();
  private final BiConsumer<Boolean, String> reporter = (show, message) -> (show ? shown : logged).add(message);

  private static SourceLookup lookup(Map<String, String> taglibClasses) {
    return new SourceLookup() {
      @Override
      public String fileOf(String className) {
        return null;
      }

      @Override
      public String openText(String path) {
        return null;
      }

      @Override
      public Map<String, String> taglibClasses() {
        return taglibClasses;
      }
    };
  }

  private CompileResult compile(GroovyAnalyzer analyzer, String path, String text, SourceLookup lookup) {
    return analyzer.compile(new CompileRequest(tmp.resolve(path).toString(), text, lookup, () -> false));
  }

  /** A page parser of an unknown shape: pages get their tag hints but are not compiled, and the user is told once. */
  @Test
  void aPageParserThatCannotBeUsedIsShownOnce() throws Exception {
    Path java = Files.createDirectories(tmp.resolve("java/org/grails/gsp/compiler"));
    Files.writeString(java.resolve("GroovyPageParser.java"), "package org.grails.gsp.compiler;\npublic class GroovyPageParser {}\n");
    Path classes = tmp.resolve("classes");
    assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), java.resolve("GroovyPageParser.java").toString()));
    try (URLClassLoader loader = new URLClassLoader(new URL[] { classes.toUri().toURL() }, getClass().getClassLoader())) {
      GroovyAnalyzer analyzer = new GroovyAnalyzer(loader, reporter);
      try {
        compile(analyzer, "views/a.gsp", "<p>${1 + 1}</p>\n", lookup(Map.of()));
        compile(analyzer, "views/b.gsp", "<p>${2 + 2}</p>\n", lookup(Map.of()));
      } finally {
        analyzer.close();
      }
    }
    assertEquals(1, shown.size(), shown.toString());
    assertTrue(shown.get(0).startsWith("GSP pages are not compiled, only their tags are checked: Grails's page parser (org.grails.gsp.compiler.GroovyPageParser) cannot be used: "),
        shown.get(0));
    assertTrue(shown.get(0).contains("NoSuchMethodException"), shown.get(0));
    assertEquals(List.of(), logged);
  }

  /** No page parser on the class path (not a web module) is not a failure. */
  @Test
  void noPageParserIsNotReported() {
    GroovyAnalyzer analyzer = new GroovyAnalyzer(getClass().getClassLoader(), reporter);
    try {
      compile(analyzer, "views/a.gsp", "<p>${1 + 1}</p>\n", lookup(Map.of()));
    } finally {
      analyzer.close();
    }
    assertEquals(List.of(), shown);
    assertEquals(List.of(), logged);
  }

  /** A class file this Groovy's ASM rejects (a newer class file version) and a jar that cannot be opened: one message. */
  @Test
  void unreadableTaglibClassFilesAreShownOnce() throws Exception {
    Path jar = tmp.resolve("taglibs.jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
      zip.putNextEntry(new ZipEntry("fixture/NewTagLib.class"));
      OutputStream out = zip;
      out.write(new byte[] { (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 0, 0, 0x7F, 0x7F, 0, 0 });
      zip.closeEntry();
    }
    SourceLookup lookup = lookup(Map.of("fixture.NewTagLib", jar.toString(), "fixture.GoneTagLib", tmp.resolve("gone.jar").toString()));
    GroovyAnalyzer analyzer = new GroovyAnalyzer(getClass().getClassLoader(), reporter);
    try {
      compile(analyzer, "views/a.gsp", "<g:if test=\"${true}\">x</g:if>\n", lookup);
      compile(analyzer, "views/b.gsp", "<g:if test=\"${true}\">x</g:if>\n", lookup);
    } finally {
      analyzer.close();
    }
    assertEquals(1, shown.size(), shown.toString());
    assertTrue(shown.get(0).startsWith("the tags of 2 taglib classes are unknown: "), shown.get(0));
    assertTrue(shown.get(0).contains("fixture.GoneTagLib") || shown.get(0).contains("fixture.NewTagLib"), shown.get(0));
    assertEquals(List.of(), logged);
  }

  /** A listed class present without its field: logged once; a class not in the project, or a field of later versions only: nothing. */
  @Test
  void aCacheFieldMissingFromItsClassIsLoggedOnce() {
    TransformCaches caches = new TransformCaches(getClass().getClassLoader(),
        new String[][] { { "java.lang.String", "NO_SUCH_CACHE" }, { "org.example.Absent", "CACHE" }, { "java.lang.String", "LATER_CACHE", "a later version" },
            { "java.lang.Integer", "MAX_VALUE" } }, new Reports(reporter));
    caches.clear();
    caches.clear();
    assertEquals(List.of(), shown);
    assertEquals(2, logged.size(), logged.toString());
    assertTrue(logged.get(0).startsWith("java.lang.String.NO_SUCH_CACHE is missing: "), logged.get(0));
    assertTrue(logged.get(1).startsWith("java.lang.Integer.MAX_VALUE has a shape the server does not know (java.lang.NoSuchFieldException: cache): "), logged.get(1));
  }

  /** Every field the workarounds need is in the Groovy the tests run on: nothing logged. */
  @Test
  void nothingIsLoggedOnASupportedGroovy() {
    GroovyAnalyzer analyzer = new GroovyAnalyzer(getClass().getClassLoader(), reporter);
    try {
      compile(analyzer, "A.groovy", "class A { String name }\n", lookup(Map.of()));
    } finally {
      analyzer.close();
    }
    assertEquals(List.of(), shown);
    assertEquals(List.of(), logged);
  }
}
