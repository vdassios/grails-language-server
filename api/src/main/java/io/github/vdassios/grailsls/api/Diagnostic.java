package io.github.vdassios.grailsls.api;

/** A compiler message. Lines and columns are 0-based; the end is exclusive. */
public final class Diagnostic {
  public static final int ERROR = 1;
  public static final int WARNING = 2;

  /** File the message is about; the compiled document's path when the compiler names none. */
  public final String path;
  public final int startLine;
  public final int startColumn;
  public final int endLine;
  public final int endColumn;
  /** {@link #ERROR} or {@link #WARNING}. */
  public final int severity;
  public final String message;

  public Diagnostic(String path, int startLine, int startColumn, int endLine, int endColumn, int severity, String message) {
    this.path = path;
    this.startLine = startLine;
    this.startColumn = startColumn;
    this.endLine = endLine;
    this.endColumn = endColumn;
    this.severity = severity;
    this.message = message;
  }

  @Override
  public String toString() {
    return path + ":" + (startLine + 1) + ":" + (startColumn + 1) + ": " + message;
  }
}
