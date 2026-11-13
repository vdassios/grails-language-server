package io.github.vdassios.grailsls.api;

import java.util.List;

/** The overloads of the method called around a position. */
public final class SignatureHelp {
  public final List<Signature> signatures;
  /** Index of the overload that best fits the arguments, or -1. */
  public final int activeSignature;
  /** Index of the argument at the position, or -1. */
  public final int activeParameter;

  public SignatureHelp(List<Signature> signatures, int activeSignature, int activeParameter) {
    this.signatures = List.copyOf(signatures);
    this.activeSignature = activeSignature;
    this.activeParameter = activeParameter;
  }

  /** One overload. */
  public static final class Signature {
    /** E.g. {@code public void render(Map param1)}. */
    public final String label;
    /** One label per parameter, e.g. {@code Map param1}. */
    public final List<String> parameters;
    /** Markdown, or null. */
    public final String documentation;

    public Signature(String label, List<String> parameters, String documentation) {
      this.label = label;
      this.parameters = List.copyOf(parameters);
      this.documentation = documentation;
    }

    @Override
    public String toString() {
      return label;
    }
  }
}
