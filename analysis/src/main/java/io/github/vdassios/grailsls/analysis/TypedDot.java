package io.github.vdassios.grailsls.analysis;

import java.net.URI;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.codehaus.groovy.ast.ASTNode;
import org.codehaus.groovy.ast.expr.ClassExpression;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.PropertyExpression;
import org.codehaus.groovy.control.SourceUnit;

import io.github.vdassios.grailsls.analysis.GroovyAnalyzer.Kept;
import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.Range;

/**
 * Completion just after a typed dot, answered without a compile. A dot just typed does not parse, so completion would
 * compile a copy of the text with a name typed after it; instead the receiver's members come from the last compile
 * of the document that parsed, when that compile provably holds the receiver a compile of the copy would have:
 * <ul>
 * <li>the texts differ in one place only, on one line, after the receiver, and there in a member access at most
 * (names, dots, parentheses, brackets, commas, quotes and blanks), so nothing before the receiver and no assignment
 * changed;
 * <li>the classes that compile read from other sources are still what a compile would read ({@link Inputs#same});
 * <li>a parse (no compile) of the copy has the member access at the position, and its receiver spans text the two
 * texts share, where the earlier compile has exactly one expression that is that receiver ({@link #isReceiver}).
 * </ul>
 * In any doubt the copy is compiled: a wrong member list is worse than a slow one.
 */
final class TypedDot {
  /**
   * What may differ between the two texts: the dot itself, or a member access deleted after it ({@code toUpperCase()},
   * {@code get('x', 1)}). No assignment ({@code =}), block, statement or line ({@code {}}, {@code ;}, a line break):
   * a variable it could still declare ({@code String s}) comes after the receiver, so it cannot change its type.
   */
  private static final Pattern NO_DECLARATION = Pattern.compile("[\\w$.?*()\\[\\],'\" \\t]*");

  private TypedDot() {}

  /**
   * The receiver of the member access after a dot just typed at a position, in {@code earlier}; null unless it is
   * provably the receiver a compile of the request's text with {@code repair} inserted at the position would have.
   *
   * @param parse parses a text of the document only (no class is resolved), null when it does not parse
   */
  static Expression receiver(Kept earlier, CompileRequest request, int line, int column, String repair, Function<String, SourceUnit> parse) {
    String text = request.text;
    int offset = new Lines(text).offset(line, column);
    if (offset == 0 || text.charAt(offset - 1) != '.') {
      return null;
    }
    String old = earlier.text;
    int max = Math.min(old.length(), text.length());
    int prefix = 0;
    while (prefix < max && old.charAt(prefix) == text.charAt(prefix)) {
      prefix++;
    }
    int suffix = 0;
    while (suffix < max - prefix && old.charAt(old.length() - 1 - suffix) == text.charAt(text.length() - 1 - suffix)) {
      suffix++;
    }
    if (!NO_DECLARATION.matcher(old.substring(prefix, old.length() - suffix)).matches() || !NO_DECLARATION.matcher(text.substring(prefix, text.length() - suffix)).matches()
        || !earlier.inputs.same(request.lookup)) {
      return null;
    }
    Expression receiver = receiverInCopy(text.substring(0, offset) + repair + text.substring(offset), line, column, parse);
    Range span = receiver == null ? null : NodeRanges.of(receiver);
    // before the first difference, a position is the same in both texts
    if (span == null || new Lines(text).offset(span.endLine, span.endColumn) > prefix) {
      return null;
    }
    Expression found = null;
    for (ASTNode node : earlier.ast().getNodes(earlier.uri)) {
      if (node instanceof Expression && node != found && isReceiver((Expression) node, receiver, span)) {
        if (found != null) {
          // two such expressions: which one a compile would take is not known
          return null;
        }
        found = (Expression) node;
      }
    }
    return found;
  }

  /** The receiver of the member access at a position, in a parse of the copy; null when there is none. */
  private static Expression receiverInCopy(String copy, int line, int column, Function<String, SourceUnit> parse) {
    SourceUnit source = parse.apply(copy);
    if (source == null || source.getErrorCollector().hasErrors()) {
      return null;
    }
    ASTNodeVisitor ast = new ASTNodeVisitor();
    ast.visitSourceUnit(source);
    URI uri = source.getSource().getURI();
    ASTNode node = ast.getNodeAtLineAndColumn(uri, line, column);
    ASTNode parent = node == null ? null : ast.getParent(node);
    // as Completions.at reads the access
    return node instanceof PropertyExpression ? ((PropertyExpression) node).getObjectExpression()
        : parent instanceof PropertyExpression ? ((PropertyExpression) parent).getObjectExpression()
        : node instanceof MethodCallExpression ? ((MethodCallExpression) node).getObjectExpression()
        : node instanceof ConstructorCallExpression ? null
        : parent instanceof MethodCallExpression ? ((MethodCallExpression) parent).getObjectExpression() : null;
  }

  /**
   * Whether an expression of the earlier compile is the receiver of the copy, which is only parsed ({@code span} is
   * the receiver's). It ends where the receiver ends, and either is of its class and text, or is the class the
   * receiver's name resolved to ({@code A}, {@code demo.A}), of its span. The start is not compared for the first:
   * Groovy 3 and later start a call or an index that is a receiver at its brackets (in {@code upper().a} the span
   * of {@code upper()} is {@code ()}), and a statement {@code upper()} at its name. The class is: in {@code x(1)}
   * the span of {@code x} is the method's name, in {@code x.} a variable.
   */
  private static boolean isReceiver(Expression compiled, Expression parsed, Range span) {
    Range range = NodeRanges.of(compiled);
    if (range == null || range.endLine != span.endLine || range.endColumn != span.endColumn) {
      return false;
    }
    return compiled instanceof ClassExpression ? range.startLine == span.startLine && range.startColumn == span.startColumn
        : compiled.getClass() == parsed.getClass() && compiled.getText().equals(parsed.getText());
  }
}
