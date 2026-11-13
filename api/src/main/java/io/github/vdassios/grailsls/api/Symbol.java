package io.github.vdassios.grailsls.api;

/** A declaration in a document: a class or one of its members. */
public final class Symbol {
  public enum Kind {
    CLASS, INTERFACE, ENUM, METHOD, FIELD, VARIABLE
  }

  public final String name;
  public final Kind kind;
  /** Name of the enclosing class, or null for a top-level class. */
  public final String container;
  public final Range range;

  public Symbol(String name, Kind kind, String container, Range range) {
    this.name = name;
    this.kind = kind;
    this.container = container;
    this.range = range;
  }

  @Override
  public String toString() {
    return kind + " " + (container == null ? "" : container + ".") + name + " " + range;
  }
}
