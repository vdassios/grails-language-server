package io.github.vdassios.grailsls.index;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The types a Groovy or Java file declares, from its text alone (no compiler): binary names, {@code pkg.Name} for
 * top-level types and {@code pkg.Outer$Inner} for nested ones. The package comes from the {@code package} line, not the
 * directory. Top-level means declared at column 0; a type indented under another is nested in it.
 *
 * <p>Must be exact: the resolver claims a file for every name listed here, and a name the file does not declare makes
 * the compile fail with "expected to find the class".
 */
public final class Declarations {
  private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
  private static final Pattern LINE_COMMENT = Pattern.compile("//[^\\n]*");
  private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
  private static final Pattern TYPE = Pattern.compile(
      "^([ \\t]*)(?:(?:public|protected|private|abstract|final|static|strictfp|sealed|non-sealed)\\s+|@(?!interface\\b)[\\w.]+(?:\\([^)\\n]*\\))?\\s+)*"
          + "(?:class|interface|enum|trait|record|@interface)\\s+([A-Za-z_$][\\w$]*)",
      Pattern.MULTILINE);

  private Declarations() {}

  /** Binary names of the types declared in {@code text}. */
  public static List<String> of(String text) {
    String code = LINE_COMMENT.matcher(withoutBlockComments(text)).replaceAll("");
    Matcher pkg = PACKAGE.matcher(code);
    String prefix = pkg.find() ? pkg.group(1) + "." : "";
    List<String> names = new ArrayList<>();
    Deque<int[]> indents = new ArrayDeque<>(); // parallel to outers
    Deque<String> outers = new ArrayDeque<>();
    Matcher type = TYPE.matcher(code);
    while (type.find()) {
      int indent = type.group(1).length();
      while (!indents.isEmpty() && indents.peek()[0] >= indent) {
        indents.pop();
        outers.pop();
      }
      String name;
      if (indent == 0) {
        name = prefix + type.group(2);
      } else if (!outers.isEmpty()) {
        name = outers.peek() + "$" + type.group(2);
      } else {
        continue; // indented without an enclosing type: not a declaration we can name
      }
      names.add(name);
      indents.push(new int[] {indent});
      outers.push(name);
    }
    return names;
  }

  /** Block comments removed, their line breaks kept. */
  private static String withoutBlockComments(String text) {
    Matcher matcher = BLOCK_COMMENT.matcher(text);
    StringBuilder out = new StringBuilder();
    int last = 0;
    while (matcher.find()) {
      out.append(text, last, matcher.start());
      matcher.group().chars().filter(c -> c == '\n').forEach(c -> out.append('\n'));
      last = matcher.end();
    }
    return out.append(text, last, text.length()).toString();
  }
}
