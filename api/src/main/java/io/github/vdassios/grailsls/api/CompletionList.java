package io.github.vdassios.grailsls.api;

import java.util.List;

/** The completion candidates at a position. */
public final class CompletionList {
  public static final CompletionList EMPTY = new CompletionList(List.of(), false);

  public final List<Completion> items;
  /** True when the list was cut short: typing more asks again. */
  public final boolean incomplete;

  public CompletionList(List<Completion> items, boolean incomplete) {
    this.items = List.copyOf(items);
    this.incomplete = incomplete;
  }
}
