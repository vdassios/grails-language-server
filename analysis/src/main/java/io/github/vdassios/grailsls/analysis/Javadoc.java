package io.github.vdassios.grailsls.analysis;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Markdown for a documentation comment read from source text: its description, without the block tags. */
final class Javadoc {
  private static final Pattern INLINE_TAG = Pattern.compile("\\{@(\\w+)\\s*([^}]*)\\}");

  private Javadoc() {}

  /** Null when the comment (with its delimiters) has no description. */
  static String markdown(String comment) {
    String body = comment.substring(3, comment.endsWith("*/") ? comment.length() - 2 : comment.length());
    StringBuilder description = new StringBuilder();
    for (String line : body.split("\n")) {
      line = line.replaceFirst("^\\s*\\*? ?", "");
      if (line.matches("\\s*@\\w.*")) {
        break; // the block tags: @param, @return
      }
      description.append(line.replaceFirst("\\s+$", "")).append('\n');
    }
    // inline tags may span lines, so before the lines are converted
    Matcher matcher = INLINE_TAG.matcher(description);
    StringBuffer inlined = new StringBuffer();
    while (matcher.find()) {
      matcher.appendReplacement(inlined, Matcher.quoteReplacement(inline(matcher.group(1), matcher.group(2).trim())));
    }
    matcher.appendTail(inlined);
    StringBuilder markdown = new StringBuilder();
    for (String line : inlined.toString().split("\n")) {
      markdown.append(GroovydocUtils.reformatLine(line)).append('\n');
    }
    String result = markdown.toString().replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replaceAll("\n{3,}", "\n\n").trim();
    return result.isEmpty() ? null : result;
  }

  private static String inline(String tag, String content) {
    switch (tag) {
      case "code":
        return content.isEmpty() ? "" : "`" + content + "`";
      case "link":
      case "linkplain":
        // {@link Type#member(int, int) label}: the label, else the reference
        int space = content.indexOf(' ');
        int end = space >= 0 && content.indexOf('(') >= 0 && content.indexOf('(') < space ? content.indexOf(')') + 1 : space;
        if (end > 0 && !content.substring(end).trim().isEmpty()) {
          return content.substring(end).trim();
        }
        String reference = content.replaceFirst("^#", "").replace('#', '.');
        return reference.isEmpty() ? "" : "`" + reference + "`";
      case "inheritDoc":
        return "";
      default:
        return content;
    }
  }
}
