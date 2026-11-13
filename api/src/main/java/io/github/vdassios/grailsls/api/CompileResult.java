package io.github.vdassios.grailsls.api;

import java.util.List;

/** Outcome of one compile. */
public final class CompileResult {
  /** Diagnostics of every source in the compile; {@link Diagnostic#path} tells them apart. */
  public final List<Diagnostic> diagnostics;
  /** The compile stopped because the request was cancelled; {@link #diagnostics} is then empty. */
  public final boolean cancelled;
  /** Sources in the compile: the document and the files pulled in from source. */
  public final int sources;
  public final long millis;

  public CompileResult(List<Diagnostic> diagnostics, boolean cancelled, int sources, long millis) {
    this.diagnostics = List.copyOf(diagnostics);
    this.cancelled = cancelled;
    this.sources = sources;
    this.millis = millis;
  }
}
