package io.github.vdassios.grailsls.api;

/** What resolving a completion adds: its signature and documentation. */
public final class CompletionDetail {
  /** Signature, e.g. {@code public Book save(Map param1)}. */
  public final String detail;
  /** Markdown, or null when the declaration has no documentation. */
  public final String documentation;

  public CompletionDetail(String detail, String documentation) {
    this.detail = detail;
    this.documentation = documentation;
  }
}
