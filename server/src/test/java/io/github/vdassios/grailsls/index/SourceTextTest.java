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
}
