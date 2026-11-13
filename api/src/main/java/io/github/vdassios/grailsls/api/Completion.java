package io.github.vdassios.grailsls.api;

import java.util.List;

/** A completion candidate. Its signature and documentation come from {@link Analyzer#resolveCompletion}. */
public final class Completion {
  public enum Kind {
    CLASS, INTERFACE, ENUM, METHOD, FIELD, VARIABLE, PACKAGE
  }

  public final String label;
  public final Kind kind;
  /** The package of a class; null for other items until resolved. */
  public final String detail;
  /** Text the client sorts by instead of the label, or null. */
  public final String sortText;
  /** The range {@link #label} replaces (the whole name in an import), or null to complete the word at the position. */
  public final Range replace;
  /** Edits besides the completion itself: the import of a class not imported yet. */
  public final List<TextEdit> additionalEdits;
  /** Key for {@link Analyzer#resolveCompletion}, or -1 when there is nothing to resolve. */
  public final int id;

  public Completion(String label, Kind kind, String detail, String sortText, Range replace, List<TextEdit> additionalEdits, int id) {
    this.label = label;
    this.kind = kind;
    this.detail = detail;
    this.sortText = sortText;
    this.replace = replace;
    this.additionalEdits = List.copyOf(additionalEdits);
    this.id = id;
  }

  @Override
  public String toString() {
    return kind + " " + label;
  }
}
