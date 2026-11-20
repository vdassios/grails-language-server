package io.github.vdassios.grailsls.analysis;

import java.util.Arrays;

import io.github.vdassios.grailsls.api.Range;

/** The line starts of a text, for conversion between offsets and 0-based {@code {line, column}} positions. */
final class Lines {
  private final String text;
  private final int[] starts;

  Lines(String text) {
    this.text = text;
    int[] found = new int[16];
    int count = 1;
    for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
      if (count == found.length) {
        found = Arrays.copyOf(found, count * 2);
      }
      found[count++] = i + 1;
    }
    this.starts = Arrays.copyOf(found, count);
  }

  /** The number of lines: one more than the number of line breaks. */
  int count() {
    return starts.length;
  }

  /**
   * The offset of a position: a column past the end of its line is the end of the line, a line past the last is the
   * end of the text, a negative line or column is the start of the text or of the line.
   */
  int offset(int line, int column) {
    if (line < 0) {
      return 0;
    }
    if (line >= starts.length) {
      return text.length();
    }
    return Math.min(starts[line] + Math.max(column, 0), lineEnd(line));
  }

  /** The offset of a line's end: its line break, or the end of the text. */
  private int lineEnd(int line) {
    return line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
  }

  /** The line of an offset. */
  int line(int offset) {
    int low = 0;
    int high = starts.length - 1;
    while (low < high) {
      int middle = (low + high + 1) >>> 1;
      if (starts[middle] <= offset) {
        low = middle;
      } else {
        high = middle - 1;
      }
    }
    return low;
  }

  /** The position of an offset, as {@code {line, column}}. */
  int[] position(int offset) {
    int line = line(offset);
    return new int[] { line, offset - starts[line] };
  }

  /** The range between two offsets. */
  Range range(int start, int end) {
    int[] from = position(start);
    int[] to = position(end);
    return new Range(from[0], from[1], to[0], to[1]);
  }

  /** The text of a position's line before it. */
  String before(int line, int column) {
    int offset = offset(line, column);
    return text.substring(starts[line(offset)], offset);
  }

  /** A line from its first non-blank character to its end (its start when blank). */
  Range lineRange(int line) {
    int start = starts[line];
    int end = lineEnd(line);
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    int first = start;
    while (first < end && Character.isWhitespace(text.charAt(first))) {
      first++;
    }
    return new Range(line, first - start, line, end - start);
  }
}
