package io.github.vdassios.grailsls.api;

import java.util.concurrent.locks.ReadWriteLock;

/**
 * The parser state that every module with the same Groovy jar shares. Groovy 3 and 4 keep their parser's prediction
 * cache (DFA) in its ATN, a static of the parser, so each module loader would hold its own copy (tens of MB each). The
 * server loads the ANTLR runtime shaded into that Groovy jar once, in a loader that implements this; every module
 * loader of the jar takes ANTLR's classes from it, and the analysis layer points each module's parser at one ATN.
 */
public interface SharedParser {
  /** The parser ATN of every module using this runtime: the first one offered, returned to every later caller. */
  Object atn(Object offered);

  /** Held for reading while a module parses, for writing while the cache is emptied. */
  ReadWriteLock lock();
}
