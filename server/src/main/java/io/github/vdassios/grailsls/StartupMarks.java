package io.github.vdassios.grailsls;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Startup stages, each logged once with the time since {@code initialize}: {@code startup: first diagnostics after
 * 2140 ms}. This is how the C6 budget (first diagnostics within 3 s of a warm start) is read from an editor's log. A
 * stage reached again later (a reload) is not logged.
 */
final class StartupMarks {
  static final String MODEL_RESOLVED = "model resolved";
  static final String DECLARATIONS_INDEXED = "declarations indexed";
  static final String SYMBOLS_INDEXED = "symbols indexed";
  static final String FIRST_DIAGNOSTICS = "first diagnostics";

  private final long start = System.nanoTime();
  private final Set<String> reached = ConcurrentHashMap.newKeySet();
  private final Consumer<String> log;

  StartupMarks(Consumer<String> log) {
    this.log = log;
  }

  void mark(String stage) {
    if (reached.add(stage)) {
      log.accept("startup: " + stage + " after " + (System.nanoTime() - start) / 1_000_000 + " ms");
    }
  }
}
