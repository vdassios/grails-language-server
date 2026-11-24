package io.github.vdassios.grailsls.analysis;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import io.github.vdassios.grailsls.api.SharedParser;

/**
 * The prediction cache (DFA) of Groovy's ANTLR 4 parser, static per loader. Groovy 3 and 4 empty it after every 64
 * parsed files so that it cannot grow without limit; a compile that pulls in 300 sources then rebuilds it four or
 * five times (measured on a Grails 7 app: 7 s instead of 2.8 s; emptying it once per compile still took 6.6 s). The
 * server turns that off ({@link #keep}) and empties the cache itself, between compiles and only once it holds more
 * states than a limit scaled to the heap, the one Groovy 5 applies on its own (measured: 112,000 states, about
 * 310 MB, for a source set of 658 files). Groovy 2.5 parses with ANTLR 2 and has no such cache.
 *
 * <p>The cache is a static of the parser, so each module loader would hold its own; module loaders of one Groovy jar
 * share it instead (the constructor), which makes the limit one for all of them.
 */
final class ParserCache {
  /** Groovy 3 and 4: parsed files between two clears; 0 or less means never. Read once, when the parser loads. */
  private static final String THRESHOLD = "groovy.antlr4.cache.threshold";

  /** Groovy 5's limit: 32 states per MB of maximum heap, between 20,000 and 500,000. */
  private static int heapScaledLimit() {
    return (int) Math.max(20_000, Math.min(500_000, (Runtime.getRuntime().maxMemory() >> 20) * 32));
  }

  private final int maxStates;
  /** Read while this loader parses, write while the cache is emptied: shared with the loaders sharing the cache. */
  private final ReadWriteLock lock;
  private final Object atn;
  private Field decisionToDfa;
  private Field states;
  private Method clearDfa;

  ParserCache(ClassLoader loader) {
    this(loader, heapScaledLimit());
  }

  /**
   * When the server loaded ANTLR's runtime for several module loaders of one Groovy jar ({@link SharedParser}), this
   * loader's parser is pointed at the ATN of the first of them, so all of them fill and empty one cache.
   */
  ParserCache(ClassLoader loader, int maxStates) {
    this.maxStates = maxStates;
    keep(); // before Groovy's AtnManager loads: it reads the property once
    Object found = null;
    SharedParser shared = null;
    try {
      String manager = "org.apache.groovy.parser.antlr4.internal.atnmanager.AtnManager";
      if (!declares(Class.forName(manager, false, loader), "DFA_CACHE_SIZE_LIMIT")) { // Groovy 5 limits the size itself
        found = Class.forName("org.apache.groovy.parser.antlr4.GroovyParser", true, loader).getField("_ATN").get(null);
        decisionToDfa = found.getClass().getField("decisionToDFA");
        states = decisionToDfa.getType().getComponentType().getField("states");
        clearDfa = found.getClass().getMethod("clearDFA");
        ClassLoader runtime = found.getClass().getClassLoader();
        if (runtime instanceof SharedParser && Long.getLong(THRESHOLD, 0) <= 0) {
          Object common = ((SharedParser) runtime).atn(found);
          if (common == found || predictWith(loader, common)) {
            found = common;
            shared = (SharedParser) runtime;
          }
        }
      }
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      found = null; // no ANTLR 4 parser (Groovy 2.5), or another shape: leave it to Groovy
    }
    atn = found;
    lock = shared != null ? shared.lock() : new ReentrantReadWriteLock();
  }

  /**
   * Makes the loader's parsers predict with another loader's ATN: Groovy creates each parser with the ATN its
   * {@code ParserAtnManager} holds. False when this Groovy keeps it elsewhere; the loader then keeps its own cache.
   */
  private static boolean predictWith(ClassLoader loader, Object atn) {
    try {
      Class<?> manager = Class.forName("org.apache.groovy.parser.antlr4.internal.atnmanager.ParserAtnManager", true, loader);
      Field wrapperField = manager.getDeclaredField("parserAtnWrapper");
      wrapperField.setAccessible(true);
      Object wrapper = wrapperField.get(manager.getField("INSTANCE").get(null));
      Field atnField = wrapper.getClass().getDeclaredField("atn");
      atnField.setAccessible(true);
      atnField.set(wrapper, atn);
      return true;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return false;
    }
  }

  /** Stops Groovy 3 and 4 from emptying the cache by file count. Call before the first parse. */
  static void keep() {
    if (System.getProperty(THRESHOLD) == null) {
      System.setProperty(THRESHOLD, "0");
    }
  }

  /** Runs work that parses: the cache is not emptied meanwhile. */
  <T> T parsing(Supplier<T> work) {
    lock.readLock().lock();
    try {
      return work.get();
    } finally {
      lock.readLock().unlock();
    }
  }

  /**
   * Empties the cache if it is over the limit and no loader sharing it parses (else a later call does). Call between
   * compiles, on the compile thread, outside {@link #parsing}.
   */
  void trim() {
    if (states() > maxStates && lock.writeLock().tryLock()) {
      try {
        clearDfa.invoke(atn);
      } catch (ReflectiveOperationException | RuntimeException e) {
        // another shape in this version: leave it
      } finally {
        lock.writeLock().unlock();
      }
    }
  }

  /** States in the cache; -1 if this Groovy has none the server manages. */
  int states() {
    if (atn == null) {
      return -1;
    }
    try {
      Object decisions = decisionToDfa.get(atn);
      int count = 0;
      for (int i = 0; i < Array.getLength(decisions); i++) {
        count += ((Map<?, ?>) states.get(Array.get(decisions, i))).size();
      }
      return count;
    } catch (ReflectiveOperationException | RuntimeException e) {
      return -1;
    }
  }

  private static boolean declares(Class<?> type, String field) {
    try {
      type.getDeclaredField(field);
      return true;
    } catch (NoSuchFieldException e) {
      return false;
    }
  }
}
