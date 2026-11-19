package io.github.vdassios.grailsls.analysis;

import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Range;

/**
 * A GSP page translated to Groovy by Grails's own page parser, {@code org.grails.gsp.compiler.GroovyPageParser}, from
 * the project's class path, so the page compiles as Grails compiles it: expressions, scriptlets and expression-valued
 * attributes become code of a {@code GroovyPage} subclass's {@code run()}, verbatim.
 *
 * <p>
 * Positions: the parser gives a line map (each generated line's page line, often the line where the tag holding the
 * code ends). Columns come from the code itself: each piece of page code ({@link GspScanner#code}) is found verbatim
 * in the generated text, at the occurrence whose line maps nearest to the piece's lines. Offsets inside such a piece
 * map both ways; anything else maps to a page line only.
 *
 * <p>
 * One adapter per Grails major (plan C8): the class has the same name from Grails 3 to 8 ({@code org.grails:grails-gsp}
 * up to 6, {@code org.apache.grails.views:grails-gsp-core} from 7); Grails 3 and 4 construct it from
 * {@code (name, uri, filename, source)}, Grails 5 to 8 take a {@code grails.config.ConfigMap} as well (null: the
 * defaults). A class path without the parser has no translation.
 */
final class GspTranslation {
  static final String PARSER = "org.grails.gsp.compiler.GroovyPageParser";

  /** The generated Groovy, or null when the parser rejected the page ({@link #error}). */
  final String groovy;
  /** The parser's message for a page it rejects (a tag not closed, a bad directive), and its 0-based page line. */
  final String error;
  final int errorLine;
  /** Per generated line (0-based), its 0-based page line; -1 when unknown. */
  private final int[] pageLines;
  /** {@code {pageStart, groovyStart, length}} of each piece of page code found in the generated text. */
  private final List<int[]> pieces = new ArrayList<>();
  private final Lines page;
  private final Lines generated;

  private GspTranslation(String text, String groovy, int[] matrix, String error, int errorLine) {
    this.groovy = groovy;
    this.error = error;
    this.errorLine = errorLine;
    this.page = new Lines(text);
    this.generated = new Lines(groovy == null ? "" : groovy);
    this.pageLines = new int[generated.count()];
    for (int i = 0; i < pageLines.length; i++) {
      pageLines[i] = matrix != null && i < matrix.length && matrix[i] > 0 ? Math.min(matrix[i] - 1, page.count() - 1) : -1;
    }
    if (groovy != null) {
      match(text);
    }
  }

  /** The page translated with the parser on {@code loader}; null when the loader has no GSP parser. */
  static GspTranslation of(ClassLoader loader, String path, String text) {
    Class<?> parser;
    try {
      parser = Class.forName(PARSER, true, loader);
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
    Object instance;
    try {
      instance = construct(parser, loader, className(path), path, text);
      Writer out = new StringWriter();
      parser.getMethod("generateGsp", Writer.class).invoke(instance, out);
      int[] matrix = (int[]) parser.getMethod("getLineNumberMatrix").invoke(instance);
      return new GspTranslation(text, out.toString(), matrix, null, -1);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      return new GspTranslation(text, null, null, String.valueOf(cause.getMessage()).trim(), lineOf(cause));
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      return null;
    }
  }

  /** The parser for a page: Grails 5 to 8's constructor with a (null) {@code ConfigMap}, else Grails 3 and 4's. */
  private static Object construct(Class<?> parser, ClassLoader loader, String name, String path, String text) throws ReflectiveOperationException {
    Class<?> configMap;
    try {
      configMap = Class.forName("grails.config.ConfigMap", false, loader);
    } catch (ClassNotFoundException e) {
      configMap = null;
    }
    if (configMap != null) {
      try {
        Constructor<?> modern = parser.getConstructor(String.class, String.class, String.class, String.class, configMap);
        return modern.newInstance(name, path, path, text, null);
      } catch (NoSuchMethodException e) {
        // Grails 3 or 4
      }
    }
    return parser.getConstructor(String.class, String.class, String.class, String.class).newInstance(name, path, path, text);
  }

  /** A class name for the page, from its file name: the class is compiled on its own, so it need not be unique. */
  private static String className(String path) {
    String file = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    StringBuilder name = new StringBuilder("gsp_");
    for (char c : file.toCharArray()) {
      name.append(Character.isJavaIdentifierPart(c) ? c : '_');
    }
    return name.toString();
  }

  /** The 0-based page line of a parser error ({@code GrailsTagException.getLineNumber()}, 1-based), else 0. */
  private static int lineOf(Throwable error) {
    try {
      Method line = error.getClass().getMethod("getLineNumber");
      Object value = line.invoke(error);
      return value instanceof Integer && (Integer) value > 0 ? (Integer) value - 1 : 0;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return 0;
    }
  }

  /** Finds each piece of page code in the generated text: the unclaimed occurrence whose line maps nearest. */
  private void match(String text) {
    List<int[]> claimed = new ArrayList<>();
    for (int[] region : GspScanner.code(text)) {
      String code = text.substring(region[0], region[1]);
      int first = page.line(region[0]);
      int last = page.line(region[1]);
      int best = -1;
      int distance = Integer.MAX_VALUE;
      for (int at = groovy.indexOf(code); at >= 0; at = groovy.indexOf(code, at + 1)) {
        if (overlaps(claimed, at, code.length()) || !standsAlone(code, at)) {
          continue;
        }
        int mapped = pageLines[generated.line(at)];
        int d = mapped < 0 ? Integer.MAX_VALUE - 1 : mapped < first ? first - mapped : mapped > last ? mapped - last : 0;
        if (d < distance) {
          best = at;
          distance = d;
        }
      }
      if (best >= 0) {
        claimed.add(new int[] { best, code.length() });
        pieces.add(new int[] { region[0], best, code.length() });
      }
    }
  }

  /** Not part of a longer name: {@code ${t}} is not the {@code t} of {@code printHtmlPart}. */
  private boolean standsAlone(String code, int at) {
    int end = at + code.length();
    boolean before = at > 0 && Character.isJavaIdentifierPart(code.charAt(0)) && Character.isJavaIdentifierPart(groovy.charAt(at - 1));
    boolean after = end < groovy.length() && Character.isJavaIdentifierPart(code.charAt(code.length() - 1)) && Character.isJavaIdentifierPart(groovy.charAt(end));
    return !before && !after;
  }

  private static boolean overlaps(List<int[]> claimed, int at, int length) {
    for (int[] c : claimed) {
      if (at < c[0] + c[1] && c[0] < at + length) {
        return true;
      }
    }
    return false;
  }

  /** The generated position of a page position inside page code, as {@code {line, column}}; null outside code. */
  int[] toGroovy(int line, int column) {
    int offset = page.offset(line, column);
    for (int[] piece : pieces) {
      if (offset >= piece[0] && offset <= piece[0] + piece[2]) {
        return generated.position(piece[1] + offset - piece[0]);
      }
    }
    return null;
  }

  /** The page position of a generated position inside page code, as {@code {line, column}}; null outside code. */
  int[] toPage(int line, int column) {
    int offset = generated.offset(line, column);
    for (int[] piece : pieces) {
      if (offset >= piece[1] && offset <= piece[1] + piece[2]) {
        return page.position(piece[0] + offset - piece[1]);
      }
    }
    return null;
  }

  /**
   * A generated range on the page: exact inside page code, else the page line the parser maps its start to (from its
   * first non-blank character to its end); null when the line is unknown.
   */
  Range toPage(Range range) {
    int[] start = toPage(range.startLine, range.startColumn);
    int[] end = toPage(range.endLine, range.endColumn);
    if (start != null) {
      return end != null ? new Range(start[0], start[1], end[0], end[1]) : new Range(start[0], start[1], start[0], start[1] + 1);
    }
    int line = range.startLine >= 0 && range.startLine < pageLines.length ? pageLines[range.startLine] : -1;
    return line < 0 ? null : page.lineRange(line);
  }

  /** A diagnostic of the generated text on the page; one with no page line goes to the page's first line. */
  Diagnostic toPage(Diagnostic diagnostic) {
    Range range = toPage(new Range(diagnostic.startLine, diagnostic.startColumn, diagnostic.endLine, diagnostic.endColumn));
    if (range == null) {
      range = page.lineRange(0);
    }
    return new Diagnostic(diagnostic.path, range.startLine, range.startColumn, range.endLine, range.endColumn, diagnostic.severity, diagnostic.message);
  }

  /** The parser's error as a diagnostic on its line. */
  Diagnostic errorDiagnostic(String path) {
    Range range = page.lineRange(Math.min(errorLine, page.count() - 1));
    return new Diagnostic(path, range.startLine, range.startColumn, range.endLine, range.endColumn, Diagnostic.ERROR, error);
  }

  /** Line starts of a text, for offset and position conversion. */
  private static final class Lines {
    private final String text;
    private final int[] starts;

    Lines(String text) {
      this.text = text;
      List<Integer> found = new ArrayList<>();
      found.add(0);
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '\n') {
          found.add(i + 1);
        }
      }
      this.starts = found.stream().mapToInt(Integer::intValue).toArray();
    }

    int count() {
      return starts.length;
    }

    int offset(int line, int column) {
      if (line < 0) {
        return 0;
      }
      if (line >= starts.length) {
        return text.length();
      }
      return Math.min(starts[line] + Math.max(column, 0), lineEnd(line));
    }

    private int lineEnd(int line) {
      return line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
    }

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

    int[] position(int offset) {
      int line = line(offset);
      return new int[] { line, offset - starts[line] };
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
}
