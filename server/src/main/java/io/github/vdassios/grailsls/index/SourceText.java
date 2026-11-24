package io.github.vdassios.grailsls.index;

import java.util.Set;

/**
 * Groovy or Java source with what is not code blanked out, read left to right in one pass so that a quote in a comment
 * opens no string and a {@code /*} in a string opens no comment.
 */
public final class SourceText {
  /** Keywords after which a {@code /} starts a slashy string, not a division. */
  private static final Set<String> BEFORE_A_VALUE = Set.of("return", "case", "in", "assert", "else", "throw", "yield");

  private final String text;
  private final char[] out;
  /** The last character of code read that is not white space (a string literal counts as {@code "}), and its offset. */
  private char last;
  private int lastAt = -1;

  private SourceText(String text) {
    this.text = text;
    this.out = text.toCharArray();
  }

  /**
   * {@code text} with every comment, and the contents of every string literal ({@code '''}, {@code """}, {@code '},
   * {@code "} and slashy {@code /}, interpolations included), replaced by spaces. Quotes and line breaks stay, so every
   * offset, line and column still points at the same code. A {@code /} opens a slashy string where a value may start
   * (see {@link #slashyMayStart()}) and divides after a value. Dollar-slashy strings ({@code $/a/$}) are read as code.
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
        i = literal(string(i, text.substring(i, i + 3), c == '"', true));
      } else if (c == '\'' || c == '"') {
        i = literal(string(i, String.valueOf(c), c == '"', false));
      } else if (c == '/' && slashyMayStart()) {
        i = literal(string(i, "/", true, true));
      } else if (interpolation && c == '}' && depth == 0) {
        return i;
      } else {
        if (interpolation) {
          depth += c == '{' ? 1 : c == '}' ? -1 : 0;
        }
        if (!Character.isWhitespace(c)) {
          last = c;
          lastAt = i;
        }
        i++;
      }
    }
    return i;
  }

  /** Notes a string literal ending at {@code end} as the last code read; returns {@code end}. */
  private int literal(int end) {
    last = '"';
    lastAt = end - 1;
    return end;
  }

  /**
   * Whether a {@code /} read now starts a slashy string: at the start, after an operator or an opening bracket, or after
   * a keyword that a value follows; not after a value (a name, a literal, a closing bracket), where it divides.
   */
  private boolean slashyMayStart() {
    if (last == 0) {
      return true;
    }
    if (Character.isJavaIdentifierPart(last)) {
      int start = lastAt;
      while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
        start--;
      }
      return BEFORE_A_VALUE.contains(text.substring(start, lastAt + 1));
    }
    return "=([{,:;~!&|?+-*%<>^".indexOf(last) >= 0;
  }

  /** Blanks the contents of the string literal opened by {@code quote} at {@code start}; returns the offset after it. */
  private int string(int start, String quote, boolean interpolates, boolean multiLine) {
    int i = start + quote.length();
    while (i < text.length()) {
      char c = text.charAt(i);
      // in a slashy string a backslash escapes a slash only: /a\\/ ends at its second slash
      if (c == '\\' && (!quote.equals("/") || text.startsWith("/", i + 1))) {
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
