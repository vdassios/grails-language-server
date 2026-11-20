package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.github.vdassios.grailsls.api.Range;

/** Offsets and positions of a text's lines. */
class LinesTest {
  private static final String TEXT = "ab\n\n  cd  \nlast";
  private final Lines lines = new Lines(TEXT);

  @Test
  void offsetsOfPositions() {
    assertEquals(4, lines.count());
    assertEquals(0, lines.offset(0, 0));
    assertEquals(TEXT.indexOf("cd"), lines.offset(2, 2));
    assertEquals(TEXT.indexOf("\n\n"), lines.offset(0, 9), "past the line's end: its end");
    assertEquals(TEXT.length(), lines.offset(3, 9), "past the last line's end: the text's end");
    assertEquals(TEXT.length(), lines.offset(7, 0), "past the last line: the text's end");
    assertEquals(0, lines.offset(-1, 3));
    assertEquals(TEXT.indexOf("last"), lines.offset(3, -2));
  }

  @Test
  void positionsOfOffsets() {
    assertArrayEquals(new int[] { 0, 2 }, lines.position(2), "a line break is on its line");
    assertArrayEquals(new int[] { 1, 0 }, lines.position(3));
    assertArrayEquals(new int[] { 3, 4 }, lines.position(TEXT.length()));
    assertEquals(2, lines.line(TEXT.indexOf("cd")));
    Range cd = lines.range(TEXT.indexOf("cd"), TEXT.indexOf("last"));
    assertEquals(new Range(2, 2, 3, 0).toString(), cd.toString());
  }

  @Test
  void textBeforeAPositionAndLineRanges() {
    assertEquals("  c", lines.before(2, 3));
    assertEquals("", lines.before(1, 0));
    assertEquals("last", lines.before(9, 0));
    assertEquals(new Range(2, 2, 2, 4).toString(), lines.lineRange(2).toString(), "without the white space around");
    assertEquals(new Range(1, 0, 1, 0).toString(), lines.lineRange(1).toString(), "blank");
    assertEquals(1, new Lines("").count());
    assertArrayEquals(new int[] { 0, 0 }, new Lines("").position(0));
  }
}
