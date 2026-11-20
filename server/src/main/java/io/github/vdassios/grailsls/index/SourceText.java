package io.github.vdassios.grailsls.index;

/**
 * Groovy or Java source with what is not code blanked out, read left to right in one pass so that a quote in a comment
 * opens no string and a {@code /*} in a string opens no comment.
 */
public final class SourceText {
  private final String text;
  private final char[] out;

  private SourceText(String text) {
    this.text = text;
    this.out = text.toCharArray();
  }

  /**
   * {@code text} with every comment, and the contents of every string literal ({@code '''}, {@code """}, {@code '} and
   * {@code "}, interpolations included), replaced by spaces. Quotes and line breaks stay, so every offset, line and
   * column still points at the same code. Slashy strings ({@code /a/}, {@code $/a/$}) are read as code: they cannot be
   * told from division without parsing.
   */
  public static String code(String text) {
    SourceText source = new SourceText(text);
    source.code(0, false);
    return new String(source.out);
  }

  /** Reads code from {@code i}; inside an interpolation, up to its closing brace, whose offset is returned. */
  private int code(int i, boolean interpolation) {
    int depth = 0;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (text.startsWith("//", i)) {
        int end = text.indexOf('\n', i);
        i = blank(i, end < 0 ? text.length() : end);
      } else if (text.startsWith("/*", i)) {
        int end = text.indexOf("*/", i + 2);
        i = blank(i, end < 0 ? text.length() : end + 2);
      } else if (text.startsWith("'''", i) || text.startsWith("\"\"\"", i)) {
        i = string(i, text.substring(i, i + 3), c == '"', true);
      } else if (c == '\'' || c == '"') {
        i = string(i, String.valueOf(c), c == '"', false);
      } else if (interpolation && c == '{') {
        depth++;
        i++;
      } else if (interpolation && c == '}') {
        if (depth == 0) {
          return i;
        }
        depth--;
        i++;
      } else {
        i++;
      }
    }
    return i;
  }

  /** Blanks the contents of the string literal opened by {@code quote} at {@code start}; returns the offset after it. */
  private int string(int start, String quote, boolean interpolates, boolean multiLine) {
    int i = start + quote.length();
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\') {
        i = blank(i, Math.min(i + 2, text.length()));
      } else if (text.startsWith(quote, i)) {
        return i + quote.length();
      } else if (c == '\n' && !multiLine) {
        return i; // not closed on its line: ends there
      } else if (c == '$' && interpolates && text.startsWith("{", i + 1)) {
        int end = code(i + 2, true);
        i = blank(i, Math.min(end + 1, text.length()));
      } else {
        i = blank(i, i + 1);
      }
    }
    return i;
  }

  /** Spaces from {@code start} to {@code end}, line breaks kept; returns {@code end}. */
  private int blank(int start, int end) {
    for (int i = start; i < end; i++) {
      if (out[i] != '\n' && out[i] != '\r') {
        out[i] = ' ';
      }
    }
    return end;
  }
}
