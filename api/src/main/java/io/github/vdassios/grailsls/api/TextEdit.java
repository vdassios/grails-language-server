package io.github.vdassios.grailsls.api;

/** Text that replaces a range of a document; an empty range inserts. */
public final class TextEdit {
  public final Range range;
  public final String newText;

  public TextEdit(Range range, String newText) {
    this.range = range;
    this.newText = newText;
  }

  @Override
  public String toString() {
    return range + " " + newText;
  }
}
