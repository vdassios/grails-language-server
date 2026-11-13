package io.github.vdassios.grailsls.index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.vdassios.grailsls.api.Symbol;

/**
 * The classes and members every Groovy source file declares, per file, from a parse of each file (no compile). Filled
 * in the background after the project resolves and kept current file by file. For workspace symbols and for
 * completion of classes not imported yet.
 */
public final class SymbolIndex {
  private final Map<Path, List<Symbol>> files = new ConcurrentHashMap<>();

  public void put(Path file, List<Symbol> symbols) {
    files.put(file, List.copyOf(symbols));
  }

  public void remove(Path file) {
    files.remove(file);
  }

  /** Files indexed so far. */
  public int files() {
    return files.size();
  }

  /** Symbols whose name contains {@code query}, ignoring case; every symbol for an empty query. */
  public List<Found> search(String query) {
    String lowerCase = query.toLowerCase(Locale.ROOT);
    List<Found> found = new ArrayList<>();
    files.forEach((file, symbols) -> {
      for (Symbol symbol : symbols) {
        if (symbol.name.toLowerCase(Locale.ROOT).contains(lowerCase)) {
          found.add(new Found(file, symbol));
        }
      }
    });
    return found;
  }

  /** Top-level classes declared in files below {@code roots} that are source (see {@link DeclarationIndex#isSource}). */
  public List<Symbol> types(List<Path> roots) {
    List<Symbol> types = new ArrayList<>();
    files.forEach((file, symbols) -> {
      if (roots.stream().noneMatch(root -> file.startsWith(root) && DeclarationIndex.isSource(root, file))) {
        return;
      }
      for (Symbol symbol : symbols) {
        if (symbol.container == null && symbol.name.indexOf('$') < 0) {
          types.add(symbol);
        }
      }
    });
    return types;
  }

  /** A symbol and its file. */
  public static final class Found {
    public final Path file;
    public final Symbol symbol;

    Found(Path file, Symbol symbol) {
      this.file = file;
      this.symbol = symbol;
    }
  }
}
