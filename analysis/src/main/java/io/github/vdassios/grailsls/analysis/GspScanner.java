package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Finds the GSP tags of a page ({@code <ns:name attr="v">}, {@code </ns:name>}, {@code <ns:name/>}): their namespace,
 * name and attributes, by offset; a namespace is a letter, then letters, digits or {@code _}, as Grails's scanner takes
 * one (any other tag is HTML). Expressions ({@code ${ }}), scriptlets ({@code <% %>}, {@code %{ }%}) and comments
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
  /** Whether {@code \${} outside a GSP tag is text, as on Grails 8 ({@link #code}). */
  private final boolean escapes;
  private final List<Tag> tags = new ArrayList<>();

  private GspScanner(String text, boolean escapes) {
    this.text = text;
    this.escapes = escapes;
  }

  /** The page's tags; {@code \${} outside a tag is taken for text, as Grails 8 takes it. */
  static List<Tag> scan(String text) {
    GspScanner scanner = new GspScanner(text, true);
    scanner.run();
    return scanner.tags;
  }

  /**
   * The Groovy code of a page, the code regions Grails's {@code GroovyPageScanner} and {@code GroovyPageParser} find,
   * as {@code {start, end, open}} offsets in text order: the body of each expression ({@code ${ }}) and scriptlet
   * ({@code <% %>}, {@code <%= %>}, {@code %{ }%}), without the surrounding white space, which the GSP compiler drops,
   * and where the construct opens ({@code open}, the offset of its {@code ${}, {@code <%} or {@code %{}). Inside a GSP
   * tag only {@code ${ }} is code, and an attribute value that is {@code [...]} as a whole, which Grails writes out as
   * Groovy ({@code model="[book: book]"}): the code between the brackets, {@code open} at the {@code [}. Comments,
   * directives ({@code <%@ %>}) and declarations ({@code <%! %>}, which Grails rejects) hold none; an empty body is
   * left out. {@code escapes}: {@code \${} outside a GSP tag is text, not an expression (Grails 8; up to Grails 7 the
   * {@code \} is text and the expression code).
   */
  static List<int[]> code(String text, boolean escapes) {
    GspScanner scanner = new GspScanner(text, escapes);
    List<int[]> regions = new ArrayList<>();
    int i = 0;
    while (i < text.length()) {
      if (text.charAt(i) == '<' && !text.startsWith("<%", i)) {
        int found = scanner.tags.size();
        int after = scanner.tag(i);
        Tag tag = scanner.tags.size() > found ? scanner.tags.get(found) : null;
        if (tag != null && !tag.namespace.isEmpty()) {
          if (!tag.closing) {
            scanner.tagCode(tag, regions);
          }
          i = Math.max(after, i + 1);
        } else {
          i++; // HTML, <meta> too: Grails reads on inside it
        }
        continue;
      }
      int skipped = scanner.skipGsp(i, escapes);
      if (skipped == i) {
        i++;
        continue;
      }
      if (text.startsWith("${", i)) {
        scanner.add(regions, i + 2, text.charAt(skipped - 1) == '}' ? skipped - 1 : skipped, i);
      } else if (text.startsWith("<%", i) && !text.startsWith("<%--", i) && !text.startsWith("<%@", i) && !text.startsWith("<%!", i)) {
        scanner.add(regions, text.startsWith("<%=", i) ? i + 3 : i + 2, text.startsWith("%>", skipped - 2) ? skipped - 2 : skipped, i);
      } else if (text.startsWith("%{", i) && !text.startsWith("%{--", i)) {
        scanner.add(regions, i + 2, text.startsWith("}%", skipped - 2) ? skipped - 2 : skipped, i);
      }
      i = skipped;
    }
    return regions;
  }

  /**
   * The code of a GSP start tag, as Grails's scanner reads it there: each {@code ${ }} (never escaped), and the inside
   * of each attribute value Grails's parser writes out as it is ({@code populateMapWithAttributes}: the value between
   * the quotes starts with {@code [} and ends with {@code ]}), with any {@code ${ }} in it.
   */
  private void tagCode(Tag tag, List<int[]> regions) {
    int i = tag.nameEnd;
    while (i < tag.end) {
      Attribute list = null;
      for (Attribute attribute : tag.attributes) {
        if (attribute.valueStart == i && attribute.value.length() >= 2 && attribute.value.startsWith("[") && attribute.value.endsWith("]")) {
          list = attribute;
        }
      }
      if (list != null) {
        add(regions, list.valueStart + 1, list.valueEnd - 1, list.valueStart);
        i = list.valueEnd;
      } else if (text.startsWith("${", i)) {
        int skipped = expressionEnd(i + 2);
        add(regions, i + 2, text.charAt(skipped - 1) == '}' ? skipped - 1 : skipped, i);
        i = skipped;
      } else {
        i++;
      }
    }
  }

  /** Adds a region without its surrounding white space, unless that leaves it empty. */
  private void add(List<int[]> regions, int start, int end, int open) {
    while (start < end && Character.isWhitespace(text.charAt(start))) {
      start++;
    }
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) {
      end--;
    }
    if (end > start) {
      regions.add(new int[] { start, end, open });
    }
  }

  /** The offset of the first {@code ${} that no {@code }} closes, else -1; {@code escapes} as for {@link #code}. */
  static int unclosedExpression(String text, boolean escapes) {
    GspScanner scanner = new GspScanner(text, escapes);
    int i = 0;
    while (i < text.length()) {
      if (text.startsWith("${", i) && scanner.expressionClose(i + 2) < 0) {
        return i;
      }
      int skipped = scanner.skipGsp(i, escapes);
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
      int skipped = skipGsp(i, escapes);
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

  /**
   * After the GSP construct starting at {@code i} (expression, scriptlet, comment), or {@code i} when none starts there;
   * with {@code escape}, {@code \${} is text.
   */
  private int skipGsp(int i, boolean escape) {
    if (escape && text.startsWith("\\${", i)) {
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
    if (i > nsStart && i < text.length() && text.charAt(i) == ':' && NAMESPACE.matcher(text.substring(nsStart, i)).matches()) {
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
      int skipped = skipGsp(i, false);
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
      int skipped = skipGsp(i, false);
      i = skipped != i ? skipped : i + 1;
    }
    int valueEnd = Math.min(i, text.length());
    attributes.add(new Attribute(name, start, valueStart, valueEnd, text.substring(valueStart, valueEnd)));
    return Math.min(i + 1, text.length());
  }

  /** A namespace as Grails's scanner takes one ({@code isTagNameSpacePattern}); any other is HTML. */
  private static final Pattern NAMESPACE = Pattern.compile("\\p{Alpha}\\w*");

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
