package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds the GSP tags of a page ({@code <ns:name attr="v">}, {@code </ns:name>}, {@code <ns:name/>}): their namespace,
 * name and attributes, by offset. Expressions ({@code ${ }}), scriptlets ({@code <% %>}, {@code %{ }%}) and comments
 * ({@code <%-- --%>}, {@code %{-- --}%}) are skipped, also inside attribute values. A tag not closed by the end of
 * the text ends there (one being typed). Of plain HTML tags only {@code <meta>} is reported (namespace {@code ""}), for
 * the layout a page names.
 */
final class GspScanner {
  /** A GSP tag. Offsets are into the page's text; {@code end} is after its {@code >}, or the text's end. */
  static final class Tag {
    final String namespace;
    final String name;
    /** Offset of the {@code <}. */
    final int start;
    final int nameStart;
    final int nameEnd;
    final int end;
    final boolean closing;
    final List<Attribute> attributes;

    Tag(String namespace, String name, int start, int nameStart, int nameEnd, int end, boolean closing, List<Attribute> attributes) {
      this.namespace = namespace;
      this.name = name;
      this.start = start;
      this.nameStart = nameStart;
      this.nameEnd = nameEnd;
      this.end = end;
      this.closing = closing;
      this.attributes = attributes;
    }

    /** Offset of the namespace's first character. */
    int namespaceStart() {
      return nameStart - namespace.length() - 1;
    }

    Attribute attribute(String name) {
      for (Attribute attribute : attributes) {
        if (attribute.name.equals(name)) {
          return attribute;
        }
      }
      return null;
    }
  }

  /** An attribute; {@code valueStart} and {@code valueEnd} exclude the quotes, -1 when it has no value. */
  static final class Attribute {
    final String name;
    final int nameStart;
    final int valueStart;
    final int valueEnd;
    final String value;

    Attribute(String name, int nameStart, int valueStart, int valueEnd, String value) {
      this.name = name;
      this.nameStart = nameStart;
      this.valueStart = valueStart;
      this.valueEnd = valueEnd;
      this.value = value;
    }

    int nameEnd() {
      return nameStart + name.length();
    }
  }

  private final String text;
  private final List<Tag> tags = new ArrayList<>();

  private GspScanner(String text) {
    this.text = text;
  }

  static List<Tag> scan(String text) {
    GspScanner scanner = new GspScanner(text);
    scanner.run();
    return scanner.tags;
  }

  /**
   * The Groovy code of a page, as {@code {start, end, open}} offsets in text order: the body of each expression
   * ({@code ${ }}, in attribute values too) and scriptlet ({@code <% %>}, {@code <%= %>}, {@code %{ }%}), without the
   * surrounding white space, which the GSP compiler drops, and where the construct opens ({@code open}, the offset of
   * its {@code ${}, {@code <%} or {@code %{}). Comments, directives ({@code <%@ %>}), declarations ({@code <%! %>},
   * which Grails rejects) and escaped expressions ({@code \${}) hold none; an empty body is left out.
   */
  static List<int[]> code(String text) {
    GspScanner scanner = new GspScanner(text);
    List<int[]> regions = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      int skipped = scanner.skipGsp(i);
      if (skipped == i) {
        i++;
        continue;
      }
      int start = -1;
      int end = -1;
      if (text.startsWith("${", i)) {
        start = i + 2;
        end = text.charAt(skipped - 1) == '}' ? skipped - 1 : skipped;
      } else if (text.startsWith("<%", i) && !text.startsWith("<%--", i) && !text.startsWith("<%@", i) && !text.startsWith("<%!", i)) {
        start = text.startsWith("<%=", i) ? i + 3 : i + 2;
        end = text.startsWith("%>", skipped - 2) ? skipped - 2 : skipped;
      } else if (text.startsWith("%{", i) && !text.startsWith("%{--", i)) {
        start = i + 2;
        end = text.startsWith("}%", skipped - 2) ? skipped - 2 : skipped;
      }
      while (start >= 0 && start < end && Character.isWhitespace(text.charAt(start))) {
        start++;
      }
      while (start >= 0 && end > start && Character.isWhitespace(text.charAt(end - 1))) {
        end--;
      }
      if (start >= 0 && end > start) {
        regions.add(new int[] { start, end, i });
      }
      i = skipped;
    }
    return regions;
  }

  /** The offset of the first {@code ${} that no {@code }} closes, else -1. */
  static int unclosedExpression(String text) {
    GspScanner scanner = new GspScanner(text);
    int i = 0;
    while (i < text.length()) {
      if (text.startsWith("${", i) && scanner.expressionClose(i + 2) < 0) {
        return i;
      }
      int skipped = scanner.skipGsp(i);
      i = skipped == i ? i + 1 : skipped;
    }
    return -1;
  }

  /** The tag whose text holds an offset (its {@code <} up to its end, the end included), or null. */
  static Tag at(List<Tag> tags, int offset) {
    for (Tag tag : tags) {
      if (offset > tag.start && offset <= tag.end) {
        return tag;
      }
    }
    return null;
  }

  private void run() {
    int i = 0;
    while (i < text.length()) {
      int skipped = skipGsp(i);
      if (skipped != i) {
        i = skipped;
        continue;
      }
      if (text.charAt(i) == '<') {
        i = tag(i);
      } else {
        i++;
      }
    }
  }

  /** After the GSP construct starting at {@code i} (expression, scriptlet, comment), or {@code i} when none starts there. */
  private int skipGsp(int i) {
    if (text.startsWith("\\${", i)) {
      return i + 3;
    }
    if (text.startsWith("${", i)) {
      return expressionEnd(i + 2);
    }
    if (text.startsWith("<%--", i)) {
      return after("--%>", i + 4);
    }
    if (text.startsWith("<%", i)) {
      return after("%>", i + 2);
    }
    if (text.startsWith("%{--", i)) {
      return after("--}%", i + 4);
    }
    if (text.startsWith("%{", i)) {
      return after("}%", i + 2);
    }
    return i;
  }

  private int after(String close, int from) {
    int at = text.indexOf(close, from);
    return at < 0 ? text.length() : at + close.length();
  }

  /** After the {@code }} that closes an expression whose body starts at {@code from}, else the end of the text. */
  private int expressionEnd(int from) {
    int close = expressionClose(from);
    return close < 0 ? text.length() : close + 1;
  }

  /** The {@code }} that closes an expression whose body starts at {@code from}: braces balance, strings are skipped; -1 when none. */
  private int expressionClose(int from) {
    int depth = 0;
    int i = from;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\'' || c == '"') {
        i = stringEnd(i, c);
        continue;
      }
      if (c == '{') {
        depth++;
      } else if (c == '}') {
        if (depth == 0) {
          return i;
        }
        depth--;
      }
      i++;
    }
    return -1;
  }

  private int stringEnd(int start, char quote) {
    int i = start + 1;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '\\') {
        i += 2;
        continue;
      }
      if (c == quote || c == '\n' && quote == '\'') {
        return i + 1;
      }
      i++;
    }
    return text.length();
  }

  /** Reads the tag starting at the {@code <} at {@code start}; returns where scanning goes on. */
  private int tag(int start) {
    int i = start + 1;
    boolean closing = i < text.length() && text.charAt(i) == '/';
    if (closing) {
      i++;
    }
    int nsStart = i;
    i = nameEnd(i);
    String namespace;
    int nameStart;
    int nameEnd;
    if (i > nsStart && i < text.length() && text.charAt(i) == ':') {
      namespace = text.substring(nsStart, i);
      nameStart = i + 1;
      nameEnd = nameEnd(nameStart);
    } else if (!closing && text.regionMatches(true, nsStart, "meta", 0, 4) && i == nsStart + 4) {
      namespace = "";
      nameStart = nsStart;
      nameEnd = i;
    } else {
      return start + 1; // neither a GSP tag nor <meta>
    }
    String name = text.substring(nameStart, nameEnd);
    List<Attribute> attributes = new ArrayList<>();
    i = nameEnd;
    while (i < text.length()) {
      char c = text.charAt(i);
      if (c == '>') {
        tags.add(new Tag(namespace, name, start, nameStart, nameEnd, i + 1, closing, attributes));
        return i + 1;
      }
      if (c == '<' && !text.startsWith("<%", i)) {
        break; // an unfinished tag, followed by the next one
      }
      int skipped = skipGsp(i);
      if (skipped != i) {
        i = skipped;
        continue;
      }
      if (isNameChar(c)) {
        i = attribute(i, attributes);
        continue;
      }
      i++;
    }
    tags.add(new Tag(namespace, name, start, nameStart, nameEnd, i, closing, attributes));
    return i;
  }

  /** Reads an attribute starting at {@code start}; returns the offset after it. */
  private int attribute(int start, List<Attribute> attributes) {
    int nameEnd = nameEnd(start);
    String name = text.substring(start, nameEnd);
    int i = nameEnd;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    if (i >= text.length() || text.charAt(i) != '=') {
      attributes.add(new Attribute(name, start, -1, -1, null));
      return nameEnd;
    }
    i++;
    while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
      i++;
    }
    if (i >= text.length() || text.charAt(i) != '"' && text.charAt(i) != '\'') {
      attributes.add(new Attribute(name, start, -1, -1, null));
      return i;
    }
    char quote = text.charAt(i);
    int valueStart = i + 1;
    i = valueStart;
    while (i < text.length() && text.charAt(i) != quote) {
      int skipped = skipGsp(i);
      i = skipped != i ? skipped : i + 1;
    }
    int valueEnd = Math.min(i, text.length());
    attributes.add(new Attribute(name, start, valueStart, valueEnd, text.substring(valueStart, valueEnd)));
    return Math.min(i + 1, text.length());
  }

  private int nameEnd(int from) {
    int i = from;
    while (i < text.length() && isNameChar(text.charAt(i))) {
      i++;
    }
    return i;
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
  }
}
