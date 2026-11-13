package io.github.vdassios.grailsls.analysis;

import java.util.Comparator;

import org.codehaus.groovy.ast.ASTNode;

import io.github.vdassios.grailsls.api.Range;

/** Positions of AST nodes as 0-based {@link Range}s. Groovy counts from 1 and uses -1 for unknown. */
public final class NodeRanges {
  /** Orders ranges by start, then by end. */
  public static final Comparator<Range> BY_START = Comparator.<Range>comparingInt(r -> r.startLine).thenComparingInt(r -> r.startColumn);
  public static final Comparator<Range> BY_END = Comparator.<Range>comparingInt(r -> r.endLine).thenComparingInt(r -> r.endColumn);

  private NodeRanges() {}

  /** The node's span, or null when it has no position (generated nodes, classes from jars). */
  public static Range of(ASTNode node) {
    if (node.getLineNumber() == -1) {
      return null;
    }
    int line = zeroBased(node.getLineNumber());
    int column = zeroBased(node.getColumnNumber());
    if (node.getLastLineNumber() == -1) {
      return new Range(line, column, line, column);
    }
    return new Range(line, column, zeroBased(node.getLastLineNumber()), zeroBased(node.getLastColumnNumber()));
  }

  /** Whether the position lies in the range, both ends included. */
  public static boolean contains(Range range, int line, int column) {
    return compare(line, column, range.startLine, range.startColumn) >= 0 && compare(line, column, range.endLine, range.endColumn) <= 0;
  }

  private static int compare(int line, int column, int otherLine, int otherColumn) {
    return line != otherLine ? Integer.compare(line, otherLine) : Integer.compare(column, otherColumn);
  }

  private static int zeroBased(int groovy) {
    return Math.max(groovy - 1, 0);
  }
}
