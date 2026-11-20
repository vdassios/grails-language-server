package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.vdassios.grailsls.api.Range;

/**
 * A page translated by Grails's page parser, the one a Grails 4 or Grails 7 project has, run in a class loader of its
 * own as the server runs a project's: the generated text is exactly Grails's, and each piece of page code maps to its
 * place in it and back.
 */
abstract class GspTranslationTest {
  private static final String PATH = "/app/grails-app/views/book/index.gsp";

  /** The system property holding the parser's class path. */
  abstract String classPath();

  private URLClassLoader loader;
  private final List<String> reported = new CopyOnWriteArrayList<>();
  private final Reports reports = new Reports((show, message) -> reported.add(message));

  @BeforeEach
  void open() throws MalformedURLException {
    List<URL> urls = new ArrayList<>();
    for (String jar : System.getProperty(classPath()).split(File.pathSeparator)) {
      urls.add(new File(jar).toURI().toURL());
    }
    loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
  }

  @AfterEach
  void close() throws IOException {
    loader.close();
    assertEquals(List.of(), reported);
  }

  /** A page scanned as the loader's Grails scans it. */
  private GspScanner.Scan scan(String text) {
    return GspScanner.scan(text, GspTranslation.escapes(loader));
  }

  private GspTranslation translate(String text) {
    GspTranslation translation = GspTranslation.of(loader, PATH, scan(text), "", Map.of(), reports);
    assertNotNull(translation);
    return translation;
  }

  /** Grails's own translation of a page, or its error message. */
  private String grails(String text) throws ReflectiveOperationException {
    Class<?> parser = Class.forName(GspTranslation.PARSER, true, loader);
    Object instance;
    try {
      Class<?> configMap = Class.forName("grails.config.ConfigMap", false, loader);
      instance = parser.getConstructor(String.class, String.class, String.class, String.class, configMap).newInstance("gsp_index_gsp", PATH, PATH, text, null);
    } catch (NoSuchMethodException e) {
      instance = parser.getConstructor(String.class, String.class, String.class, String.class).newInstance("gsp_index_gsp", PATH, PATH, text);
    }
    Writer out = new StringWriter();
    try {
      parser.getMethod("generateGsp", Writer.class).invoke(instance, out);
    } catch (InvocationTargetException e) {
      return "error: " + e.getCause().getMessage();
    }
    return out.toString();
  }

  /** Line and column of {@code needle}'s {@code n}th occurrence (from 0) in {@code text}, plus {@code offset}. */
  private static int[] at(String text, String needle, int n, int offset) {
    int index = text.indexOf(needle);
    for (int i = 0; i < n; i++) {
      index = text.indexOf(needle, index + 1);
    }
    assertTrue(index >= 0, needle + " in\n" + text);
    index += offset;
    int line = (int) text.substring(0, index).chars().filter(c -> c == '\n').count();
    return new int[] { line, index - (text.lastIndexOf('\n', index - 1) + 1) };
  }

  private static int offset(String text, int[] position) {
    int start = 0;
    for (int i = 0; i < position[0]; i++) {
      start = text.indexOf('\n', start) + 1;
    }
    return start + position[1];
  }

  /** The generated text from the generated position of a page position. */
  private static String generatedAt(GspTranslation translation, int[] page) {
    int[] generated = translation.toGroovy(page[0], page[1]);
    assertNotNull(generated, "no generated position for " + page[0] + ":" + page[1] + " in\n" + translation.groovy);
    return translation.groovy.substring(offset(translation.groovy, generated));
  }

  /** The page position {@code page} maps to, mapped back. */
  private static void roundTrip(GspTranslation translation, int[] page) {
    int[] generated = translation.toGroovy(page[0], page[1]);
    assertNotNull(generated);
    assertEquals(List.of(page[0], page[1]), toList(translation.toPage(generated[0], generated[1])));
  }

  private static List<Integer> toList(int[] position) {
    return position == null ? null : List.of(position[0], position[1]);
  }

  /** Pages with code of every kind, in the places Grails moves or rewrites it. */
  private static final List<String> PAGES = List.of(
      "<%@ page import=\"java.util.Date\" %>\n<ul>\n<g:each in=\"${[new Date()]}\" var=\"d\" status=\"i\">\n  <li title=\"day ${d.day}\">${d.time} <% out << i %></li>\n</g:each>\n</ul>\n",
      "<g:form resource=\"${this.book}\">${this.book?.version}</g:form>\n",
      "<g:each in=\"${a}\">${it.x}</g:each>\n<g:each in=\"${b}\">${it.x}</g:each>\n",
      "%{\n  def a = 1 &amp;&amp; 2\n  out <<   a<br>out << 3\n}%\n<p>${a}</p>\n",
      "%{-- one\ntwo --}%\n<%-- three\nfour --%>\n<p>\n  ${x.y}\n</p>\n",
      "<p>${(a)} ${a.b?} <%= c %> ${ d }</p>\n<g:if test=\"${e}\">f</g:if><g:else>${g}</g:else>\n",
      "<g:set var=\"t\" value=\"${\"x ${y}\"}\"/>\n<g:link title=\"a ${b} c\" params=\"[d: e]\">${t}</g:link>\n",
      "<g:render template=\"row\" model=\"[ a: b ]\"/>\n<g:each in=\"[1, 2]\" var=\"n\">\\${n}</g:each>\n");

  @Test
  void theGeneratedTextIsGrailsOwn() throws ReflectiveOperationException {
    for (String page : PAGES) {
      // g:each without var names its variable by an identity hash code: _it1234
      assertEquals(grails(page).replaceAll("_it\\d+", "_it"), translate(page).groovy.replaceAll("_it\\d+", "_it"), page);
    }
  }

  @Test
  void anAttributeIsNoPrefixOfALongerExpression() {
    String page = PAGES.get(1);
    GspTranslation translation = translate(page);
    assertTrue(generatedAt(translation, at(page, "version", 0, 0)).startsWith("version)"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "this.book}", 0, 0)).startsWith("this.book)"), translation.groovy);
    assertNotEquals(toList(translation.toGroovy(at(page, "this", 0, 0)[0], at(page, "this", 0, 0)[1])),
        toList(translation.toGroovy(at(page, "this", 1, 0)[0], at(page, "this", 1, 0)[1])));
    roundTrip(translation, at(page, "version", 0, 2));
    roundTrip(translation, at(page, "book}", 0, 1));
  }

  @Test
  void aRepeatedExpressionMapsToEachOfItsPlaces() {
    String page = PAGES.get(2);
    GspTranslation translation = translate(page);
    int[] first = at(page, "it.x", 0, 3);
    int[] second = at(page, "it.x", 1, 3);
    assertNotEquals(toList(translation.toGroovy(first[0], first[1])), toList(translation.toGroovy(second[0], second[1])));
    roundTrip(translation, first);
    roundTrip(translation, second);
  }

  @Test
  void aMultiLineScriptletMapsAsGrailsRewritesIt() {
    String page = PAGES.get(3);
    GspTranslation translation = translate(page);
    assertTrue(generatedAt(translation, at(page, "def a", 0, 0)).startsWith("def a = 1 && 2 out << a\nout << 3"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "2\n", 0, 0)).startsWith("2 out"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "a<br>", 0, 0)).startsWith("a\nout << 3"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "out << 3", 0, 0)).startsWith("out << 3"), translation.groovy);
    roundTrip(translation, at(page, "out <<", 0, 0));
    roundTrip(translation, at(page, "out << 3", 0, 4));
    roundTrip(translation, at(page, "${a}", 0, 2));
  }

  @Test
  void codeOfEveryKindMaps() {
    String page = PAGES.get(5);
    GspTranslation translation = translate(page);
    assertTrue(generatedAt(translation, at(page, "(a)", 0, 0)).startsWith("(a))"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "a.b?", 0, 0)).startsWith("a.b)?"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "c %>", 0, 0)).startsWith("c)"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "d }", 0, 0)).startsWith("d)"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "e}", 0, 0)).startsWith("e)"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "g}", 0, 0)).startsWith("g)"), translation.groovy);
    String attributes = PAGES.get(6);
    GspTranslation gstrings = translate(attributes);
    assertTrue(generatedAt(gstrings, at(attributes, "y}", 0, 0)).startsWith("y}"), gstrings.groovy);
    assertTrue(generatedAt(gstrings, at(attributes, "b}", 0, 0)).startsWith("b}"), gstrings.groovy);
    assertTrue(generatedAt(gstrings, at(attributes, "t}", 0, 0)).startsWith("t)"), gstrings.groovy);
    assertNull(gstrings.toGroovy(at(attributes, "<g:link", 0, 1)[0], at(attributes, "<g:link", 0, 1)[1]), "a tag is no code");
  }

  /** A {@code [...]} attribute value is code: Grails writes it out as it is. */
  @Test
  void listValuesMap() {
    String page = PAGES.get(7);
    GspTranslation translation = translate(page);
    assertTrue(generatedAt(translation, at(page, "a: b", 0, 0)).startsWith("a: b ]"), translation.groovy);
    assertTrue(generatedAt(translation, at(page, "1, 2", 0, 0)).startsWith("1, 2])"), translation.groovy);
    roundTrip(translation, at(page, "b ]", 0, 0));
    roundTrip(translation, at(page, "2]", 0, 0));
    String params = PAGES.get(6);
    assertTrue(generatedAt(translate(params), at(params, "d: e", 0, 0)).startsWith("d: e]"), translate(params).groovy);
  }

  /** Up to Grails 7 {@code \${} is an expression, the {@code \} text. */
  @Test
  void anEscapedExpressionMapsUpToGrails7() {
    String page = PAGES.get(7);
    GspTranslation translation = translate(page);
    assertTrue(generatedAt(translation, at(page, "n}", 0, 0)).startsWith("n)"), translation.groovy);
    roundTrip(translation, at(page, "n}", 0, 0));
  }

  /** Grails 8's scanner, and only it, has {@code isEscapedGExpression}: there {@code \${} is text. */
  @Test
  void onlyGrails8EscapesAnExpression() throws IOException {
    assertFalse(GspTranslation.escapes(loader));
    URL grails8 = new File(System.getProperty("grails8.gsp")).toURI().toURL();
    try (URLClassLoader jar = new URLClassLoader(new URL[] { grails8 }, ClassLoader.getPlatformClassLoader())) {
      assertTrue(GspTranslation.escapes(jar));
    }
    try (URLClassLoader empty = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
      assertFalse(GspTranslation.escapes(empty));
    }
  }

  /** The page line of a generated position just before a piece of code: the line the code is on. */
  private static int lineBefore(GspTranslation translation, int[] page) {
    int[] generated = translation.toGroovy(page[0], page[1]);
    Range range = translation.toPage(new Range(generated[0], generated[1] - 1, generated[0], generated[1] - 1));
    assertNotNull(range);
    return range.startLine;
  }

  @Test
  void outsideCodeTheLineIsThatOfTheNearestCode() {
    String comments = PAGES.get(4);
    assertEquals(5, lineBefore(translate(comments), at(comments, "x.y", 0, 0)), "after comments");
    String scriptlet = PAGES.get(3);
    assertEquals(4, lineBefore(translate(scriptlet), at(scriptlet, "${a}", 0, 2)), "after a multi-line scriptlet");
    String each = PAGES.get(0);
    assertEquals(3, lineBefore(translate(each), at(each, "d.time", 0, 0)));
  }

  @Test
  void anUnclosedExpressionIsAnErrorOnItsLine() {
    GspTranslation translation = translate("<h1>Books</h1>\n<p>\n  ${book.title\n</p>\n");
    assertNull(translation.groovy);
    assertEquals(2, translation.errorLine);
    assertTrue(translation.error.endsWith(":3] Unclosed GSP expression"), translation.error);
  }

  @Test
  void anErrorNamesThePagesOwnText() throws ReflectiveOperationException {
    String page = "<p>\n<g:if test=\"${a}\" bad>x</g:if>\n";
    GspTranslation translation = translate(page);
    assertNull(translation.groovy);
    assertEquals(grails(page), "error: " + translation.error);
    assertEquals(1, translation.errorLine);
  }

  @Test
  void declarationsGoOnTheLineOfInit() {
    String page = PAGES.get(0);
    GspTranslation translation = GspTranslation.of(loader, PATH, scan(page), "java.lang.String extra; ", Map.of(), reports);
    String line = translation.groovy.split("\n", -1)[translation.declarationLine];
    assertTrue(line.startsWith("java.lang.String extra; protected void init() {"), line);
    assertTrue(generatedAt(translation, at(page, "d.time", 0, 0)).startsWith("d.time)"), translation.groovy);
    assertNull(translation.toPage(new Range(translation.declarationLine, 0, translation.declarationLine, 1)), "no page line");
  }

  @Test
  void aClassPathWithoutTheParserHasNoTranslation() throws IOException {
    try (URLClassLoader empty = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
      assertNull(GspTranslation.of(empty, PATH, GspScanner.scan("<p>${a}</p>", false), "", Map.of(), reports));
    }
  }
}
