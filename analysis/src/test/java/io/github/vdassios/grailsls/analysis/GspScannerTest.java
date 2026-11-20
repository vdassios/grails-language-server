package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** The page scanner: tags with their attributes, the code regions of a page, an expression not closed. */
class GspScannerTest {
  private static List<String> tags(String text) {
    return GspScanner.scan(text).stream().map(tag -> (tag.closing ? "/" : "") + tag.namespace + ":" + tag.name + tag.attributes.stream()
        .map(a -> " " + a.name + (a.value == null ? "" : "=" + a.value)).collect(Collectors.joining())).collect(Collectors.toList());
  }

  /** The code regions' text, as Grails 4 to 7 find them ({@code \${} is an expression). */
  private static List<String> code(String text) {
    return code(text, false);
  }

  private static List<String> code(String text, boolean escapes) {
    return GspScanner.code(text, escapes).stream().map(region -> text.substring(region[0], region[1])).collect(Collectors.toList());
  }

  @Test
  void tagsWithTheirAttributes() {
    String text = "<p><g:link action=\"show\" id='${b.id}' absolute>x</g:link><ns:x-y a.b=\"1\"/></p>";
    assertEquals(List.of("g:link action=show id=${b.id} absolute", "/g:link", "ns:x-y a.b=1"), tags(text));
    GspScanner.Tag link = GspScanner.scan(text).get(0);
    assertEquals(text.indexOf("<g:link"), link.start);
    assertEquals(text.indexOf("link"), link.nameStart);
    assertEquals(text.indexOf("g:link"), link.namespaceStart());
    assertEquals(text.indexOf(">x"), link.end - 1);
    GspScanner.Attribute id = link.attribute("id");
    assertEquals(text.indexOf("${b.id}"), id.valueStart);
    assertEquals(text.indexOf("' absolute"), id.valueEnd);
    assertEquals(text.indexOf("id="), id.nameStart);
    assertEquals(-1, link.attribute("absolute").valueStart);
    assertNull(link.attribute("nope"));
  }

  @Test
  void codeAndCommentsHideTags() {
    assertEquals(List.of("g:if test=${a > b ? '<g:x>' : \"}\"}"), tags("<g:if test=\"${a > b ? '<g:x>' : \"}\"}\">"));
    assertEquals(List.of(), tags("${'<g:a>'} <% '<g:b>' %> %{ '<g:c>' }% <%-- <g:d> --%> %{-- <g:e> --}%"));
    assertEquals(List.of("g:f"), tags("\\${<g:f>}"), "an escaped expression is text");
  }

  @Test
  void onlyMetaOfThePlainHtmlTags() {
    assertEquals(List.of(":meta name=layout content=main"), tags("<html><meta name=\"layout\" content=\"main\"><p>x</p></html>"));
  }

  @Test
  void aTagBeingTypedEndsAtTheNextTagOrTheText() {
    String text = "<g:link action=\"show\"\n<g:if test=\"${x}\">";
    List<GspScanner.Tag> tags = GspScanner.scan(text);
    assertEquals(List.of("g:link action=show", "g:if test=${x}"), tags(text));
    assertEquals(text.indexOf("<g:if"), tags.get(0).end);
    assertEquals(text.length(), tags.get(1).end);
    String open = "<p><g:each in=\"${x}\" ";
    GspScanner.Tag each = GspScanner.scan(open).get(0);
    assertEquals(open.length(), each.end);
    assertEquals("each", GspScanner.at(List.of(each), open.length()).name);
  }

  @Test
  void theTagAtAnOffset() {
    String text = "<p><g:a x=\"1\">body</g:a></p>";
    List<GspScanner.Tag> tags = GspScanner.scan(text);
    assertNull(GspScanner.at(tags, text.indexOf("<g:a")), "on the <");
    assertEquals("a", GspScanner.at(tags, text.indexOf("x=")).name);
    assertEquals("a", GspScanner.at(tags, text.indexOf("body")).name, "right after the >");
    assertNull(GspScanner.at(tags, text.indexOf("ody")));
    assertTrue(GspScanner.at(tags, text.indexOf("</g:a") + 1).closing);
  }

  @Test
  void codeRegionsOfEveryKindTrimmed() {
    String text = "<p title=\"a ${ x.y } b\">${z}</p>\n<% def a = 1 %>\n<%= a %>\n%{\n  a++\n}%\n<g:if test=\"${a}\">x</g:if>";
    assertEquals(List.of("x.y", "z", "def a = 1", "a", "a++", "a"), code(text));
    int[] first = GspScanner.code(text, false).get(0);
    assertEquals(text.indexOf("x.y"), first[0]);
    assertEquals(text.indexOf("x.y") + 3, first[1]);
  }

  @Test
  void commentsDirectivesDeclarationsAndEmptyBodiesHoldNoCode() {
    assertEquals(List.of(), code("<%-- ${a} --%> %{-- ${b} --}% <%@ page import=\"x.Y\" %> <%! int c %> <%!int d%> ${} ${ } <% %> %{ }% <g:x a=\"[ ]\"/>"));
  }

  @Test
  void anExpressionNotClosedRunsToTheEnd() {
    String text = "<p>${a}</p>\n${book.\n</p>\n";
    assertEquals(List.of("a", "book.\n</p>"), code(text));
    assertEquals(text.indexOf("${book"), GspScanner.unclosedExpression(text, false));
    assertEquals(-1, GspScanner.unclosedExpression("${a} ${'}'} ${[b: {}]}", false), "strings and nested braces");
    assertFalse(GspScanner.unclosedExpression("${\"}\"", false) < 0, "the } in a string closes nothing");
    assertEquals(1, GspScanner.unclosedExpression("\\${a", false));
    assertEquals(-1, GspScanner.unclosedExpression("\\${a", true), "escaped: text");
  }

  /** Grails's parser writes an attribute value that is {@code [...]} as a whole out as Groovy: the code between the brackets. */
  @Test
  void aListValueOfAGspTagIsCode() {
    String text = "<g:render template=\"row\" model=\"[ book: book ]\"/>\n<g:link params='[id: b.id]' title=\"[x] y\">z</g:link>";
    assertEquals(List.of("book: book", "id: b.id"), code(text));
    int[] model = GspScanner.code(text, false).get(0);
    assertEquals(text.indexOf("[ book"), model[2], "opens at the [");
    assertEquals(List.of("1, 2", "a: ${b}"), code("<g:each in=\"[1, 2]\"><g:x m=\"[a: ${b}]\"/></g:each>"), "the whole list, any ${ } in it too");
    assertEquals(List.of("a"), code("<g:x m=\" [1]\" n=\"[1] \" o=\"${a}\"/>"), "only a value that starts and ends with a bracket");
    assertEquals(List.of(), code("<a data-x=\"[1]\">x</a> <meta content=\"[2]\">"), "an HTML tag's value is text");
  }

  /** Inside a GSP tag Grails's scanner looks for {@code ${} only: scriptlets there are text. */
  @Test
  void insideAGspTagOnlyExpressionsAreCode() {
    assertEquals(List.of("c", "d"), code("<g:if test=\"<% a %> %{ b }% ${c}\">${d}</g:if>"));
    assertEquals(List.of("a", "b"), code("<p title=\"<% a %> %{ b }%\">"), "in an HTML tag they are code");
    assertEquals(List.of(), code("</g:if x=\"${a}\">"), "an end tag holds none");
  }

  /** A namespace as Grails takes one: a letter, then letters, digits or {@code _}; any other tag is HTML. */
  @Test
  void aTagNamespaceIsAWord() {
    assertEquals(List.of("g_2:x"), tags("<g_2:x/><my-ns:x/><2g:x/><a.b:x/>"));
    assertEquals(List.of("a", "c"), code("<my-ns:x a=\"<% a %>\" b=\"[b]\" c=\"${c}\"/>"), "HTML: scriptlets are code, a list is text");
  }

  /** Grails 8 takes {@code \${} outside a GSP tag for text; up to Grails 7 the {@code \} is text and the expression code. */
  @Test
  void anEscapedExpressionIsTextOnGrails8Only() {
    String text = "<p>\\${a}</p><g:if test=\"\\${b}\">\\${c}</g:if>";
    assertEquals(List.of("a", "b", "c"), code(text, false));
    assertEquals(List.of("b"), code(text, true), "inside a GSP tag it is still an expression");
  }
}
