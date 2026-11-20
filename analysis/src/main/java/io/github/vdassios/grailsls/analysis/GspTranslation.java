package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;

/**
 * A GSP page translated to Groovy by Grails's own page parser, {@code org.grails.gsp.compiler.GroovyPageParser}, from
 * the project's class path, so the page compiles as Grails compiles it: expressions, scriptlets and expression-valued
 * attributes become code of a {@code GroovyPage} subclass's {@code run()}.
 *
 * <p>
 * Positions: the parser is given a copy of the page in which each piece of page code ({@link GspScanner#code}) starts
 * with a unique comment, {@code /*gls<n>*}{@code /}. Grails copies a piece with its comment wherever it puts the piece,
 * however it reorders the code around it, so after the comments are taken out again each piece's place in the
 * generated text is known (a {@link Piece}), and the text is exactly what Grails generates for the page itself.
 * Offsets inside a piece map both ways; elsewhere a generated position maps to the page line of the nearest piece.
 *
 * <p>
 * The parser class has the same name from Grails 3 to 8 ({@code org.grails:grails-gsp} up to 6,
 * {@code org.apache.grails.views:grails-gsp-core} from 7), and is read reflectively: up to Grails 5 it is constructed
 * from {@code (name, uri, filename, source)}, from Grails 6 with a {@code grails.config.ConfigMap} as well (null: the
 * defaults). {@code generateGsp(Writer)} runs it in precompile mode, so expressions come out as written, never in the
 * {@code evaluate(...)} wrapper of development mode. A class path without the parser has no translation.
 */
final class GspTranslation {
  static final String PARSER = "org.grails.gsp.compiler.GroovyPageParser";

  /** The page's text. */
  final String text;
  /** The generated Groovy, or null when the parser rejected the page ({@link #error}). */
  final String groovy;
  /** The parser's message for a page it rejects (a tag not closed, a bad directive), and its 0-based page line. */
  final String error;
  final int errorLine;
  /** The generated line (0-based) holding the added declarations; -1 when none were added. */
  final int declarationLine;
  /** Where each declared page variable is defined ({@link PageVariables}). */
  final Map<String, Location> definitions;
  /** Each piece of page code found in the generated text, in generated order. */
  private final List<Piece> pieces;
  private final Lines page;
  private final Lines generated;

  private GspTranslation(String text, String groovy, List<Piece> pieces, String error, int errorLine, int declarationLine, Map<String, Location> definitions) {
    this.text = text;
    this.groovy = groovy;
    this.pieces = pieces;
    this.error = error;
    this.errorLine = errorLine;
    this.declarationLine = declarationLine;
    this.definitions = definitions;
    this.page = new Lines(text);
    this.generated = new Lines(groovy == null ? "" : groovy);
  }

  /**
   * The page translated with the parser on {@code loader}, with {@code declarations} ({@link #implicitObjects},
   * {@link PageVariables}) added to the generated class; null when the loader has no GSP parser, or a parser this
   * class cannot use (shown to the user through {@code reports}, once).
   */
  static GspTranslation of(ClassLoader loader, String path, String text, String declarations, Map<String, Location> definitions, Reports reports) {
    Class<?> parser;
    try {
      parser = Class.forName(PARSER, true, loader);
    } catch (ClassNotFoundException e) {
      return null; // not a web module
    } catch (LinkageError e) {
      unusable(reports, e);
      return null;
    }
    boolean escapes = escapes(loader);
    Marks marks = new Marks(text, escapes);
    try {
      Object instance = construct(parser, loader, className(path), path, marks.text);
      Writer out = new StringWriter();
      parser.getMethod("generateGsp", Writer.class).invoke(instance, out);
      List<Piece> pieces = new ArrayList<>();
      String groovy = marks.unmark(out.toString(), pieces);
      // on the line of the class's init(), after run() and so after every piece: no piece and no generated line moves
      int init = groovy.lastIndexOf(INIT);
      int line = -1;
      if (init >= 0 && !declarations.isEmpty()) {
        line = new Lines(groovy).line(init);
        groovy = groovy.substring(0, init) + declarations + groovy.substring(init);
      }
      return new GspTranslation(text, groovy, pieces, null, -1, line, definitions);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause();
      // the copy has the page's lines; a message quoting a tag's attributes quotes them with the marks
      String message = marks.unmark(String.valueOf(cause.getMessage()).trim(), new ArrayList<>());
      int line = lineOf(cause);
      // the parser gives the line where the token before the ${ starts, often lines above it
      int unclosed = message.endsWith("Unclosed GSP expression") ? GspScanner.unclosedExpression(text, escapes) : -1;
      if (unclosed >= 0) {
        int at = new Lines(text).line(unclosed);
        message = message.replaceFirst(":" + (line + 1) + "] ", ":" + (at + 1) + "] ");
        line = at;
      }
      return new GspTranslation(text, null, List.of(), message, line, -1, Map.of());
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      unusable(reports, e);
      return null;
    }
  }

  private static final String SCANNER = "org/grails/gsp/compiler/GroovyPageScanner.class";

  /**
   * Whether the loader's GSP scanner takes {@code \${} for text: Grails 8's has {@code isEscapedGExpression}, up to
   * Grails 7 none does. Read from the class file, which names its methods, so that it needs no JVM that loads the
   * class.
   */
  static boolean escapes(ClassLoader loader) {
    try (InputStream in = loader.getResourceAsStream(SCANNER)) {
      return in != null && new String(in.readAllBytes(), StandardCharsets.ISO_8859_1).contains("isEscapedGExpression");
    } catch (IOException e) {
      return false;
    }
  }

  /** The parser is there but cannot be used: a Grails version whose parser has another shape, or a bug here. */
  private static void unusable(Reports reports, Throwable e) {
    reports.show("gsp parser", "GSP pages are not compiled, only their tags are checked: Grails's page parser (" + PARSER + ") cannot be used: " + e);
  }

  /**
   * The objects Grails binds for every page, as typed property declarations on one line: Grails's
   * {@code WebRequestTemplateVariableBinding} takes each from the request's {@code GrailsWebRequest}, so each is typed
   * by that class's getter on the project's class path ({@code javax} or {@code jakarta} servlet types alike).
   * {@code pageScope} is the page's binding; {@code out} is already a getter of the page. Empty without Grails's web
   * classes.
   */
  static String implicitObjects(ClassLoader loader) {
    StringBuilder declarations = new StringBuilder();
    try {
      Class<?> request = Class.forName(WEB_REQUEST, false, loader);
      declare(declarations, request, "webRequest");
      for (String[] getter : REQUEST_GETTERS) {
        Class<?> type = request;
        for (int i = 1; i < getter.length && type != null; i++) {
          type = returnType(type, getter[i]);
        }
        declare(declarations, type, getter[0]);
      }
    } catch (ClassNotFoundException | LinkageError e) {
      // not a web module: no request objects
    }
    try {
      declare(declarations, Class.forName("org.grails.gsp.GroovyPageBinding", false, loader), "pageScope");
    } catch (ClassNotFoundException | LinkageError e) {
      // no page binding: pageScope stays untyped
    }
    return declarations.toString();
  }

  private static final String WEB_REQUEST = "org.grails.web.servlet.mvc.GrailsWebRequest";
  /** The generated class's {@code init()}, emitted after {@code run()} by Grails 3 to 8. */
  private static final String INIT = "protected void init() {";
  /** Name, then the getters that lead from the {@code GrailsWebRequest} to it. */
  private static final String[][] REQUEST_GETTERS = {
      { "request", "getCurrentRequest" }, { "response", "getCurrentResponse" }, { "session", "getSession" }, { "flash", "getFlashScope" },
      { "application", "getServletContext" }, { "params", "getParams" }, { "actionName", "getActionName" }, { "controllerName", "getControllerName" },
      { "applicationContext", "getAttributes", "getApplicationContext" }, { "grailsApplication", "getAttributes", "getGrailsApplication" } };

  private static Class<?> returnType(Class<?> type, String getter) {
    try {
      return type.getMethod(getter).getReturnType();
    } catch (NoSuchMethodException | LinkageError e) {
      return null;
    }
  }

  private static void declare(StringBuilder declarations, Class<?> type, String name) {
    if (type != null && !type.isPrimitive() && type.getCanonicalName() != null) {
      declarations.append(type.getCanonicalName()).append(' ').append(name).append("; ");
    }
  }

  /** The parser for a page: Grails 6 to 8's constructor with a (null) {@code ConfigMap}, else that of Grails 3 to 5. */
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
        // Grails 5: ConfigMap exists, the parser does not take it
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

  /** The generated position of a page position inside page code, as {@code {line, column}}; null outside code. */
  int[] toGroovy(int line, int column) {
    int offset = page.offset(line, column);
    for (Piece piece : pieces) {
      if (offset >= piece.page && offset <= piece.pageEnd()) {
        return generated.position(piece.toGroovy(offset));
      }
    }
    return null;
  }

  /** The page position of a generated position inside page code, as {@code {line, column}}; null outside code. */
  int[] toPage(int line, int column) {
    int offset = generated.offset(line, column);
    for (Piece piece : pieces) {
      if (offset >= piece.groovy && offset <= piece.groovy + piece.length()) {
        return page.position(piece.toPage(offset));
      }
    }
    return null;
  }

  /**
   * A generated range on the page: exact inside page code, else the page line of the piece of code nearest its start
   * (from the line's first non-blank character to its end); null on the added declarations' line or a page without
   * code.
   */
  Range toPage(Range range) {
    int[] start = toPage(range.startLine, range.startColumn);
    int[] end = toPage(range.endLine, range.endColumn);
    if (start != null) {
      return end != null ? new Range(start[0], start[1], end[0], end[1]) : new Range(start[0], start[1], start[0], start[1] + 1);
    }
    if (range.startLine == declarationLine) {
      return null;
    }
    int offset = generated.offset(range.startLine, range.startColumn);
    int line = -1;
    int distance = Integer.MAX_VALUE;
    for (Piece piece : pieces) {
      boolean before = offset < piece.groovy;
      int d = before ? piece.groovy - offset : offset - piece.groovy - piece.length();
      if (d < distance) {
        distance = d;
        line = page.line(before ? piece.page : piece.pageEnd());
      }
    }
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

  /**
   * A piece of page code in the generated text. Grails copies most code verbatim; {@code %{ }%} code it rewrites
   * ({@link Marks#rewrite}), so each generated character keeps the page offset it came from.
   */
  private static final class Piece {
    /** Offset of the code on the page, and in the generated text. */
    final int page;
    final int groovy;
    /** Per generated character, its offset from {@code page}; one more entry, the code's length on the page. */
    final int[] sources;

    Piece(int page, int groovy, int[] sources) {
      this.page = page;
      this.groovy = groovy;
      this.sources = sources;
    }

    int length() {
      return sources.length - 1;
    }

    int pageEnd() {
      return page + sources[sources.length - 1];
    }

    /** A page offset inside the piece in the generated text: the first character from it or after it. */
    int toGroovy(int offset) {
      int i = 0;
      while (i < length() && sources[i] < offset - page) {
        i++;
      }
      return groovy + i;
    }

    int toPage(int offset) {
      return page + sources[offset - groovy];
    }
  }

  /**
   * The page with each piece of code marked by a comment at its start ({@code /*gls<n>*}{@code /}, a prefix the page
   * does not hold), which keeps every line where it is and takes the same path through the parser as the code alone:
   * Grails never looks at what an expression or scriptlet starts with, and the brackets of a {@code [...]} attribute
   * value it does look at stay outside the piece. The one place it looks at the end, a {@code ${a?}} it compiles as
   * {@code (a)?}, is left out of the piece.
   */
  private static final class Marks {
    final String text;
    private final String page;
    private final String prefix;
    private final List<int[]> code;

    Marks(String page, boolean escapes) {
      this.page = page;
      String prefix = "/*gls";
      while (page.contains(prefix)) {
        prefix += "_";
      }
      this.prefix = prefix;
      this.code = GspScanner.code(page, escapes);
      StringBuilder marked = new StringBuilder(page.length() + code.size() * (prefix.length() + 4));
      int copied = 0;
      for (int n = 0; n < code.size(); n++) {
        int[] region = code.get(n);
        if (page.startsWith("${", region[2]) && region[1] - region[0] > 1 && page.charAt(region[1] - 1) == '?') {
          region[1]--;
        }
        marked.append(page, copied, region[0]).append(prefix).append(n).append("*/");
        copied = region[0];
      }
      this.text = marked.append(page, copied, page.length()).toString();
    }

    /** The generated text without the marks; each piece found verbatim (or as Grails rewrites it) at a mark is added. */
    String unmark(String generated, List<Piece> pieces) {
      StringBuilder out = new StringBuilder(generated.length());
      List<int[]> found = new ArrayList<>();
      int copied = 0;
      for (int at = generated.indexOf(prefix); at >= 0; at = generated.indexOf(prefix, at + 1)) {
        int digits = at + prefix.length();
        int end = digits;
        while (end < generated.length() && Character.isDigit(generated.charAt(end))) {
          end++;
        }
        if (end == digits || !generated.startsWith("*/", end)) {
          continue;
        }
        out.append(generated, copied, at);
        found.add(new int[] { Integer.parseInt(generated.substring(digits, end)), out.length() });
        copied = end + 2;
        at = end + 1;
      }
      String unmarked = out.append(generated, copied, generated.length()).toString();
      for (int[] mark : found) {
        int[] region = mark[0] < code.size() ? code.get(mark[0]) : null;
        if (region == null) {
          continue;
        }
        String source = page.substring(region[0], region[1]);
        StringBuilder rewritten = new StringBuilder();
        int[] sources = page.startsWith("%{", region[2]) ? rewrite(source, rewritten) : identity(source, rewritten);
        if (unmarked.startsWith(rewritten.toString(), mark[1])) {
          pieces.add(new Piece(region[0], mark[1], sources));
        }
      }
      return unmarked;
    }

    private static int[] identity(String code, StringBuilder out) {
      out.append(code);
      int[] sources = new int[code.length() + 1];
      for (int i = 0; i < sources.length; i++) {
        sources[i] = i;
      }
      return sources;
    }

    /**
     * {@code %{ }%} code as Grails writes it ({@code GroovyPageParser.write}, the same from Grails 3 to 8): each run of
     * white space as one space, {@code &semi;}, {@code &amp;}, {@code &lt;} and {@code &gt;} as their characters,
     * {@code <br>} and {@code <hr>} as a line break. (It also looks for paragraph and table-row breaks at a
     * {@code <}, with patterns that start with {@code /p>}, {@code /td>}, {@code tr} or {@code td}: never there.) The
     * code's offset of each written character.
     */
    static int[] rewrite(String code, StringBuilder out) {
      int[] sources = new int[code.length() + 1];
      for (int i = 0; i < code.length(); i++) {
        char c = code.charAt(i);
        int from = i;
        String replacement = null;
        if (Character.isWhitespace(c)) {
          int next = i + 1;
          while (next < code.length() && Character.isWhitespace(code.charAt(next))) {
            next++;
          }
          if (next < code.length()) {
            replacement = " ";
            i = next - 1;
          } else {
            i = next; // white space to the end: Grails writes its first character
          }
        } else if (c == '&') {
          for (String[] entity : ENTITIES) {
            if (code.regionMatches(true, i, entity[0], 0, entity[0].length())) {
              replacement = entity[1];
              i += entity[0].length() - 1;
              break;
            }
          }
        } else if (c == '<' && (code.regionMatches(true, i, "<br>", 0, 4) || code.regionMatches(true, i, "<hr>", 0, 4))) {
          replacement = "\n";
          i += 3;
        }
        sources[out.length()] = from;
        out.append(replacement != null ? replacement : String.valueOf(c));
      }
      sources[out.length()] = code.length();
      return Arrays.copyOf(sources, out.length() + 1);
    }

    private static final String[][] ENTITIES = { { "&semi;", ";" }, { "&amp;", "&" }, { "&lt;", "<" }, { "&gt;", ">" } };
  }
}
