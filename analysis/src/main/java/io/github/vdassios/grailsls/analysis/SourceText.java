package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.vdassios.grailsls.api.Range;

/**
 * Finds a declaration in Java or Groovy source text by its names, for classes known only from a class file: no parser
 * reads both languages here (the module has the project's Groovy, which parses no Java). Comments and string literals
 * are blanked, then a class is a keyword and its name at the right brace depth, and a member a name directly in the
 * class body. Among overloads, the one with the same number of parameters whose text names most of the wanted types.
 *
 * <p>Not found: members declared in a parameter list (record components), and anything after a Groovy slashy string
 * with an unbalanced brace.
 */
final class SourceText {
  /** A declaration's name, and the documentation comment before it (as written, with its delimiters) or null. */
  static final class Found {
    final Range range;
    final String comment;

    Found(Range range, String comment) {
      this.range = range;
      this.comment = comment;
    }
  }

  private static final Set<String> NOT_BEFORE_A_DECLARATION = Set.of("new", "return", "throw", "else", "case", "assert", "in", "instanceof");

  private final String text;
  /** The text with comments and literals blanked: same offsets. */
  private final String code;
  /**
   * Nesting of braces, and of parentheses and brackets, before each character of {@link #code}: a list literal's items,
   * like a call's arguments, are not declarations.
   */
  private final int[] braces;
  private final int[] parens;
  /** Start and end offsets of the documentation comments. */
  private final List<int[]> comments = new ArrayList<>();

  SourceText(String text) {
    this.text = text;
    this.code = blank(text, comments);
    this.braces = new int[code.length() + 1];
    this.parens = new int[code.length() + 1];
    int brace = 0;
    int paren = 0;
    for (int i = 0; i < code.length(); i++) {
      braces[i] = brace;
      parens[i] = paren;
      char c = code.charAt(i);
      brace += c == '{' ? 1 : c == '}' && brace > 0 ? -1 : 0;
      paren += c == '(' || c == '[' ? 1 : (c == ')' || c == ']') && paren > 0 ? -1 : 0;
    }
    braces[code.length()] = brace;
    parens[code.length()] = paren;
  }

  /**
   * The class named by {@code classes} (simple names, outermost first), or its member when {@code member} is not null:
   * a method or constructor (named as its class) with these parameter types (simple names), else a field, property or
   * enum constant. A member not found gives its class without a comment; null when the class is not found either.
   */
  Found find(List<String> classes, String member, boolean method, List<String> parameterTypes) {
    int from = 0;
    int to = code.length();
    int depth = 0;
    int name = -1;
    for (String simpleName : classes) {
      Matcher matcher = Pattern.compile("\\b(?:class|interface|enum|trait|record)\\s+(" + Pattern.quote(simpleName) + ")(?![\\w$])").matcher(code);
      matcher.region(from, to);
      name = -1;
      while (matcher.find()) {
        if (braces[matcher.start()] == depth && parens[matcher.start()] == 0) {
          name = matcher.start(1);
          break;
        }
      }
      int open = name < 0 ? -1 : next('{', name, to, depth);
      if (open < 0) {
        return null;
      }
      int close = next('}', open + 1, to, depth + 1);
      from = open + 1;
      to = close < 0 ? to : close;
      depth++;
    }
    if (name < 0) {
      return null;
    }
    String className = classes.get(classes.size() - 1);
    int declared = member == null ? -1 : method ? method(member, member.equals(className), parameterTypes, from, to, depth) : field(member, from, to, depth);
    return declared < 0 ? new Found(range(name, className.length()), member == null ? comment(name) : null)
        : new Found(range(declared, member.length()), comment(declared));
  }

  private int method(String name, boolean constructor, List<String> parameterTypes, int from, int to, int depth) {
    Matcher matcher = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*\\(").matcher(code);
    matcher.region(from, to);
    int best = -1;
    int bestScore = Integer.MIN_VALUE;
    while (matcher.find()) {
      int start = matcher.start();
      if (braces[start] != depth || parens[start] != 0 || !declares(start, from, constructor)) {
        continue;
      }
      int open = matcher.end() - 1;
      int close = next(')', open + 1, to, parens[open] + 1, true);
      List<String> parameters = parameters(open + 1, close < 0 ? to : close);
      // a Groovy parameter with a default value may be left out: the class file has an overload without it
      long required = parameters.stream().filter(parameter -> parameter.indexOf('=') < 0).count();
      boolean fits = required <= parameterTypes.size() && parameterTypes.size() <= parameters.size();
      int score = fits ? 1000 : -Math.abs(parameters.size() - parameterTypes.size()) * 10;
      for (int i = 0; i < Math.min(parameters.size(), parameterTypes.size()); i++) {
        if (Pattern.compile("(?<![\\w$])" + Pattern.quote(parameterTypes.get(i)) + "(?![\\w$])").matcher(parameters.get(i)).find()) {
          score++;
        }
      }
      if (score > bestScore) {
        best = start;
        bestScore = score;
      }
    }
    return best;
  }

  private int field(String name, int from, int to, int depth) {
    Matcher matcher = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])").matcher(code);
    matcher.region(from, to);
    while (matcher.find()) {
      int start = matcher.start();
      if (braces[start] != depth || parens[start] != 0 || !declares(start, from, true)) {
        continue;
      }
      int after = matcher.end();
      while (after < to && (code.charAt(after) == ' ' || code.charAt(after) == '\t')) {
        after++;
      }
      char next = after < to ? code.charAt(after) : '\n';
      char before = before(start, from);
      // an enum constant may have arguments or a body; a field's name is followed by neither
      boolean constant = before == 0 || before == '{' || before == ',' || before == ';' || before == ')';
      if (next == '=' || next == ';' || next == ',' || next == '\n' || next == '\r' || next == '}' || (constant && (next == '(' || next == '{'))) {
        return start;
      }
    }
    return -1;
  }

  /**
   * Whether the name at an offset can be one being declared: after a type or a modifier, or (constructors, enum
   * constants) at the start of a member. Not after an operator, a dot or a keyword that starts an expression.
   */
  private boolean declares(int offset, int from, boolean memberStart) {
    char before = before(offset, from);
    if (before == 0 || before == '{' || before == '}' || before == ';' || before == ')' || before == ',') {
      return memberStart;
    }
    if (before == '>' || before == ']') {
      return true;
    }
    if (!Character.isJavaIdentifierPart(before)) {
      return false;
    }
    int end = offset;
    while (end > from && Character.isWhitespace(code.charAt(end - 1))) {
      end--;
    }
    int start = end;
    while (start > from && Character.isJavaIdentifierPart(code.charAt(start - 1))) {
      start--;
    }
    return !NOT_BEFORE_A_DECLARATION.contains(code.substring(start, end));
  }

  /** The last character before an offset that is not white space; 0 at the start. */
  private char before(int offset, int from) {
    for (int i = offset - 1; i >= from; i--) {
      if (!Character.isWhitespace(code.charAt(i))) {
        return code.charAt(i);
      }
    }
    return 0;
  }

  /** The text of each parameter between two offsets: split at the commas outside type arguments and nested brackets. */
  private List<String> parameters(int from, int to) {
    List<String> parameters = new ArrayList<>();
    if (code.substring(from, to).trim().isEmpty()) {
      return parameters;
    }
    int angles = 0;
    int start = from;
    for (int i = from; i < to; i++) {
      char c = code.charAt(i);
      angles += c == '<' ? 1 : c == '>' && angles > 0 ? -1 : 0;
      if (c == ',' && angles == 0 && parens[i] == parens[from] && braces[i] == braces[from]) {
        parameters.add(code.substring(start, i));
        start = i + 1;
      }
    }
    parameters.add(code.substring(start, to));
    return parameters;
  }

  private int next(char wanted, int from, int to, int braceDepth) {
    return next(wanted, from, to, braceDepth, false);
  }

  /** The first {@code wanted} from an offset at a nesting of braces, or of parentheses. */
  private int next(char wanted, int from, int to, int depth, boolean ofParens) {
    for (int i = from; i < to; i++) {
      if (code.charAt(i) == wanted && (ofParens ? parens : braces)[i] == depth) {
        return i;
      }
    }
    return -1;
  }

  /** The documentation comment that ends before a declaration's name with only its annotations, modifiers and type between. */
  private String comment(int name) {
    int[] last = null;
    for (int[] comment : comments) {
      if (comment[1] > name) {
        break;
      }
      last = comment;
    }
    if (last == null) {
      return null;
    }
    // another declaration in between (an earlier enum constant, a field) makes it that one's comment
    int angles = 0;
    for (int i = last[1]; i < name; i++) {
      char c = code.charAt(i);
      angles += c == '<' ? 1 : c == '>' && angles > 0 ? -1 : 0;
      if ((c == ';' || c == '{' || c == '}' || (c == ',' && angles == 0)) && parens[i] == parens[name]) {
        return null;
      }
    }
    return text.substring(last[0], last[1]);
  }

  private Range range(int offset, int length) {
    return new Lines(text).range(offset, offset + length);
  }

  /**
   * Groovy or Java source with comments and string and character literals (slashy ones too) replaced by spaces, line ends kept: every
   * offset, line and column still points at the same code, and a name in a string or a comment is gone.
   */
  static String code(String source) {
    return blank(source, null);
  }

  /** Keywords after which a {@code /} starts a slashy string, not a division. */
  private static final Set<String> BEFORE_A_VALUE = Set.of("return", "case", "in", "assert", "else", "throw", "yield");

  /**
   * Whether a {@code /} after {@code last} (at {@code lastAt}) starts a slashy string: at the start, after an operator
   * or an opening bracket, or after a keyword that a value follows; not after a value (a name, a literal, a closing
   * bracket), where it divides.
   */
  private static boolean slashyMayStart(String source, char last, int lastAt) {
    if (last == 0) {
      return true;
    }
    if (Character.isJavaIdentifierPart(last)) {
      int start = lastAt;
      while (start > 0 && Character.isJavaIdentifierPart(source.charAt(start - 1))) {
        start--;
      }
      return BEFORE_A_VALUE.contains(source.substring(start, lastAt + 1));
    }
    return "=([{,:;~!&|?+-*%<>^".indexOf(last) >= 0;
  }

  /** Blanks comments and string and character literals, keeping line ends; notes the documentation comments, unless null. */
  private static String blank(String source, List<int[]> comments) {
    char[] chars = source.toCharArray();
    int n = chars.length;
    int i = 0;
    // the last character of code before i that is not white space (a literal counts as '"'), and its offset
    char last = 0;
    int lastAt = -1;
    while (i < n) {
      char c = chars[i];
      int end;
      boolean comment = source.startsWith("//", i) || source.startsWith("/*", i);
      if (source.startsWith("//", i)) {
        end = source.indexOf('\n', i);
        end = end < 0 ? n : end;
      } else if (source.startsWith("/*", i)) {
        end = source.indexOf("*/", i + 2);
        end = end < 0 ? n : end + 2;
        if (comments != null && source.startsWith("/**", i) && end - i > 4) {
          comments.add(new int[] { i, end });
        }
      } else if (source.startsWith("\"\"\"", i) || source.startsWith("'''", i)) {
        end = source.indexOf(source.substring(i, i + 3), i + 3);
        end = end < 0 ? n : end + 3;
      } else if (c == '"' || c == '\'') {
        // ends at the line's end at the latest: an apostrophe in a Groovy slashy string must not hide what follows
        end = i + 1;
        while (end < n && chars[end] != c && chars[end] != '\n') {
          end += chars[end] == '\\' ? 2 : 1;
        }
        end = Math.min(end < n && chars[end] == c ? end + 1 : end, n);
      } else if (c == '/' && slashyMayStart(source, last, lastAt)) {
        end = i + 1;
        while (end < n && chars[end] != '/') {
          end += chars[end] == '\\' ? 2 : 1;
        }
        end = Math.min(end + 1, n);
      } else {
        if (!Character.isWhitespace(c)) {
          last = c;
          lastAt = i;
        }
        i++;
        continue;
      }
      if (!comment) {
        last = '"';
        lastAt = end - 1;
      }
      for (int k = i; k < end; k++) {
        if (chars[k] != '\n' && chars[k] != '\r') {
          chars[k] = ' ';
        }
      }
      i = end;
    }
    return new String(chars);
  }
}
