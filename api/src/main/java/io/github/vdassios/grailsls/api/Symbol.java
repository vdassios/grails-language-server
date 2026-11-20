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
  /**
   * False for a class the source does not declare by name: a script's class or an anonymous class. True for every
   * other class and every member.
   */
  public final boolean named;

  public Symbol(String name, Kind kind, String container, Range range) {
    this(name, kind, container, range, true);
  }

  public Symbol(String name, Kind kind, String container, Range range, boolean named) {
    this.name = name;
    this.kind = kind;
    this.container = container;
    this.range = range;
    this.named = named;
  }

  @Override
  public String toString() {
    return kind + " " + (container == null ? "" : container + ".") + name + " " + range;
  }
}
