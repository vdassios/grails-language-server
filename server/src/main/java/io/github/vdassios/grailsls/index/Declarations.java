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
 * directory. Top-level means declared at column 0, or outside every brace; a type indented under another is nested in it.
 *
 * <p>Strings and comments are blanked first ({@link SourceText}), so source embedded in a string declares nothing.
 *
 * <p>Must be exact: the resolver claims a file for every name listed here, and a name the file does not declare makes
 * the compile fail with "expected to find the class".
 */
public final class Declarations {
  private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);
  private static final Pattern TYPE = Pattern.compile(
      "^([ \\t]*)(?:(?:public|protected|private|abstract|final|static|strictfp|sealed|non-sealed)\\s+|@(?!interface\\b)[\\w.]+(?:\\([^)\\n]*\\))?\\s+)*"
          + "(?:class|interface|enum|trait|record|@interface)\\s+([A-Za-z_$][\\w$]*)",
      Pattern.MULTILINE);

  private Declarations() {}

  /** Binary names of the types declared in {@code text}. */
  public static List<String> of(String text) {
    String code = SourceText.code(text);
    Matcher pkg = PACKAGE.matcher(code);
    String prefix = pkg.find() ? pkg.group(1) + "." : "";
    List<String> names = new ArrayList<>();
    Deque<int[]> indents = new ArrayDeque<>(); // parallel to outers
    Deque<String> outers = new ArrayDeque<>();
    int depth = 0; // open braces minus closed ones before scanned
    int scanned = 0;
    Matcher type = TYPE.matcher(code);
    while (type.find()) {
      for (; scanned < type.start(); scanned++) {
        char c = code.charAt(scanned);
        depth += c == '{' ? 1 : c == '}' ? -1 : 0;
      }
      int indent = type.group(1).length();
      String name;
      if (indent == 0 || depth == 0) {
        // at column 0, or indented outside every block (" final class A", "/* note */ class A")
        indents.clear();
        outers.clear();
        name = prefix + type.group(2);
      } else {
        while (!indents.isEmpty() && indents.peek()[0] >= indent) {
          indents.pop();
          outers.pop();
        }
        if (outers.isEmpty()) {
          continue; // inside a block of no type found: not a declaration we can name
        }
        name = outers.peek() + "$" + type.group(2);
      }
      names.add(name);
      indents.push(new int[] {indent});
      outers.push(name);
    }
    return names;
  }
}
