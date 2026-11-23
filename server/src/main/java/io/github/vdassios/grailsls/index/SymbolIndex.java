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

  /** Symbols indexed so far. */
  public int size() {
    return files.values().stream().mapToInt(List::size).sum();
  }

  /**
   * At most this many symbols answer an empty query: a client that lists everything (the first request of a symbol
   * picker) would otherwise get every member of a large project.
   */
  public static final int EMPTY_QUERY_LIMIT = 1000;

  /**
   * Symbols whose name contains {@code query}, ignoring case. An empty query gets {@link #EMPTY_QUERY_LIMIT} symbols
   * at most, classes before members.
   */
  public List<Found> search(String query) {
    if (query.isEmpty()) {
      List<Found> types = new ArrayList<>();
      List<Found> members = new ArrayList<>();
      files.forEach((file, symbols) -> {
        for (Symbol symbol : symbols) {
          boolean type = symbol.kind == Symbol.Kind.CLASS || symbol.kind == Symbol.Kind.INTERFACE || symbol.kind == Symbol.Kind.ENUM;
          List<Found> list = type ? types : members;
          if (list.size() < EMPTY_QUERY_LIMIT) {
            list.add(new Found(file, symbol));
          }
        }
      });
      types.addAll(members);
      return types.size() > EMPTY_QUERY_LIMIT ? new ArrayList<>(types.subList(0, EMPTY_QUERY_LIMIT)) : types;
    }
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

  /** Top-level classes declared in files below {@code roots} (every file indexed is source). */
  public List<Symbol> types(List<Path> roots) {
    List<Symbol> types = new ArrayList<>();
    files.forEach((file, symbols) -> {
      if (roots.stream().noneMatch(file::startsWith)) {
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
