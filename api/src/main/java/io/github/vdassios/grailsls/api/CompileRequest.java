package io.github.vdassios.grailsls.api;

import java.util.function.BooleanSupplier;

/** One document to compile. */
public final class CompileRequest {
  /** Absolute file path; Grails decides artefact kinds from it. */
  public final String path;
  public final String text;
  public final SourceLookup lookup;
  /** Checked between compiler phases; when true, the compile stops and reports {@link CompileResult#cancelled}. */
  public final BooleanSupplier cancelled;

  public CompileRequest(String path, String text, SourceLookup lookup, BooleanSupplier cancelled) {
    this.path = path;
    this.text = text;
    this.lookup = lookup;
    this.cancelled = cancelled;
  }
}
