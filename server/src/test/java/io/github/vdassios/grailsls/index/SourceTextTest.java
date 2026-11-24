package io.github.vdassios.grailsls.index;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SourceTextTest {
  /** Comments and string contents become spaces; quotes, line breaks and so every offset stay. */
  @Test
  void blanksCommentsAndStringContentsInPlace() {
    String text = "def a = 'x{' // it's\n/* \"q\" */ def b = \"\"\"\n${c + '}'} d\n\"\"\" + \"e\\\"f\"\n";
    String code = SourceText.code(text);
    assertEquals(text.length(), code.length());
    assertEquals("def a = '  '" + " ".repeat(8) + "\n" + " ".repeat(10) + "def b = \"\"\"\n            \n\"\"\" + \"    \"\n", code);
  }

  /** A string not closed on its line ends there; a comment not closed ends the text. */
  @Test
  void unclosedStringsAndCommentsEnd() {
    assertEquals("x = '  \ny {}\n    \n ", SourceText.code("x = 'ab\ny {}\n/* a\nb"));
  }

  /** A slash where a value may start opens a slashy string; after a value it divides. */
  @Test
  void slashyStringsAreBlankedAndDivisionIsCode() {
    assertEquals("def half = total / 2 / count; ~/    /; f(/ /); return /   /",
        SourceText.code("def half = total / 2 / count; ~/a\\/*/; f(/x/); return /\\d+/"));
    assertEquals("m = s =~ /         /\nclass A {}", SourceText.code("m = s =~ /'${a}\\/*x/\nclass A {}"), "no string or comment opens in it");
    assertEquals("~/            /\n}", SourceText.code("~/.*\\\\/.*\\\\/.*/\n}"), "a backslash escapes a slash only");
  }
}
