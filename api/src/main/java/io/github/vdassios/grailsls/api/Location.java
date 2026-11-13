package io.github.vdassios.grailsls.api;

/** A span of a source file. */
public final class Location {
  /** Absolute file path. */
  public final String path;
  public final Range range;

  public Location(String path, Range range) {
    this.path = path;
    this.range = range;
  }

  @Override
  public String toString() {
    return path + ":" + range;
  }
}
