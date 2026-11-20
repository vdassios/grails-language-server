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

  /** The code regions' text. */
  private static List<String> code(String text) {
    return GspScanner.code(text).stream().map(region -> text.substring(region[0], region[1])).collect(Collectors.toList());
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
    int[] first = GspScanner.code(text).get(0);
    assertEquals(text.indexOf("x.y"), first[0]);
    assertEquals(text.indexOf("x.y") + 3, first[1]);
  }

  @Test
  void commentsDirectivesDeclarationsAndEmptyBodiesHoldNoCode() {
    assertEquals(List.of(), code("<%-- ${a} --%> %{-- ${b} --}% <%@ page import=\"x.Y\" %> <%! int c %> ${} ${ } <% %> %{ }% \\${d}"));
  }

  @Test
  void anExpressionNotClosedRunsToTheEnd() {
    String text = "<p>${a}</p>\n${book.\n</p>\n";
    assertEquals(List.of("a", "book.\n</p>"), code(text));
    assertEquals(text.indexOf("${book"), GspScanner.unclosedExpression(text));
    assertEquals(-1, GspScanner.unclosedExpression("${a} ${'}'} ${[b: {}]}"), "strings and nested braces");
    assertFalse(GspScanner.unclosedExpression("${\"}\"") < 0, "the } in a string closes nothing");
  }
}
