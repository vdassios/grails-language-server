package io.github.vdassios.grailsls.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.Symbol;

class SymbolIndexTest {
  private static final Range RANGE = new Range(0, 0, 0, 1);

  /** A large project: each file a class with ten members. */
  private static SymbolIndex index(int files) {
    SymbolIndex index = new SymbolIndex();
    for (int i = 0; i < files; i++) {
      List<Symbol> symbols = new ArrayList<>();
      symbols.add(new Symbol("Class" + i, Symbol.Kind.CLASS, null, RANGE));
      for (int m = 0; m < 10; m++) {
        symbols.add(new Symbol("method" + m, Symbol.Kind.METHOD, "Class" + i, RANGE));
      }
      index.put(Path.of("/src/Class" + i + ".groovy"), symbols);
    }
    return index;
  }

  /** An empty query (a client listing everything) gets at most the limit, classes first; a query gets every match. */
  @Test
  void anEmptyQueryGetsAtMostTheLimitClassesFirst() {
    SymbolIndex index = index(500);
    assertEquals(5500, index.size());

    List<SymbolIndex.Found> all = index.search("");
    assertEquals(SymbolIndex.EMPTY_QUERY_LIMIT, all.size());
    assertTrue(all.subList(0, 500).stream().allMatch(found -> found.symbol.kind == Symbol.Kind.CLASS), "classes first");

    assertEquals(500, index.search("method1").size());
    assertEquals(5000, index.search("method").size(), "a query is not capped");
  }

  @Test
  void anEmptyQueryOnASmallIndexGetsEverySymbol() {
    assertEquals(11, index(1).search("").size());
  }
}
