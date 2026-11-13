package io.github.vdassios.grailsls.api;

import java.util.List;

/** A class node after the compile: what the project's AST transformations made of it. */
public final class CompiledClass {
  public final String name;
  /** Source file it was compiled from, or null when it came from a jar or class directory. */
  public final String sourcePath;
  /** Interfaces it implements, its own and those of its superclasses, traits included. */
  public final List<String> interfaces;
  /** Names of its methods, declared and added by transformations, without those of superclasses. */
  public final List<String> methods;

  public CompiledClass(String name, String sourcePath, List<String> interfaces, List<String> methods) {
    this.name = name;
    this.sourcePath = sourcePath;
    this.interfaces = List.copyOf(interfaces);
    this.methods = List.copyOf(methods);
  }
}
