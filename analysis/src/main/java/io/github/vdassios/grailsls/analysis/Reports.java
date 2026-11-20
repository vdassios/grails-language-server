package io.github.vdassios.grailsls.analysis;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Failures of the analysis layer, passed to the server: shown to the user when a feature is off, only logged when a
 * workaround is off and the features still work. Each key is reported once per analyzer, so once per module loader.
 */
final class Reports {
  private final BiConsumer<Boolean, String> sink;
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  /** @param sink takes {@code true} and the message to show to the user, {@code false} and one only to log */
  Reports(BiConsumer<Boolean, String> sink) {
    this.sink = sink;
  }

  /** A feature is off: the user is told. */
  void show(String key, String message) {
    if (reported.add(key)) {
      sink.accept(true, message);
    }
  }

  /** A workaround is off, the features still work: logged only. */
  void log(String key, String message) {
    if (reported.add(key)) {
      sink.accept(false, message);
    }
  }
}
