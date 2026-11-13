package io.github.vdassios.grailsls.api;

/** A span of a file. Lines and columns are 0-based; the end is exclusive. */
public final class Range {
  public final int startLine;
  public final int startColumn;
  public final int endLine;
  public final int endColumn;

  public Range(int startLine, int startColumn, int endLine, int endColumn) {
    this.startLine = startLine;
    this.startColumn = startColumn;
    this.endLine = endLine;
    this.endColumn = endColumn;
  }

  @Override
  public String toString() {
    return (startLine + 1) + ":" + (startColumn + 1) + "-" + (endLine + 1) + ":" + (endColumn + 1);
  }
}
