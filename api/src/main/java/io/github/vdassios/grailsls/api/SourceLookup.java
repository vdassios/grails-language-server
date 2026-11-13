package io.github.vdassios.grailsls.api;

import java.util.List;

/** Where the classes a compile needs are declared. Owned by the server: its declaration index and open documents. */
public interface SourceLookup {
  /** File declaring the class with this binary name ({@code pkg.Outer$Inner}), or null when no source declares it. */
  String fileOf(String className);

  /** Current text of the document open at this path, or null to read the file. */
  String openText(String path);

  /**
   * Top-level classes declared in the sources this lookup answers from, for completion of classes not imported yet
   * ({@link Symbol#name} is the qualified name). May be incomplete while the server is still indexing.
   */
  default List<Symbol> types() {
    return List.of();
  }
}
