package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.function.Supplier;

/**
 * Drops what project code prints while a compile runs. Grails's transformations print warnings with a sample of the
 * source straight to {@code System.err} (one per command object that is not validateable), which would fill the
 * client's log with the project's code on every keystroke. Other threads print as before.
 */
final class CompilerOutput {
  private static final ThreadLocal<Boolean> MUTED = new ThreadLocal<>();
  private static PrintStream out;
  private static PrintStream err;

  private CompilerOutput() {}

  /** Runs {@code action} with this thread's {@code System.out} and {@code System.err} output dropped. */
  static <T> T muted(Supplier<T> action) {
    install();
    MUTED.set(Boolean.TRUE);
    try {
      return action.get();
    } finally {
      MUTED.remove();
    }
  }

  /** Wraps the current streams, again if someone replaced them since. */
  private static synchronized void install() {
    if (System.out != out) {
      out = new PrintStream(new Muting(System.out), true);
      System.setOut(out);
    }
    if (System.err != err) {
      err = new PrintStream(new Muting(System.err), true);
      System.setErr(err);
    }
  }

  private static final class Muting extends OutputStream {
    private final PrintStream target;

    Muting(PrintStream target) {
      this.target = target;
    }

    @Override
    public void write(int b) {
      if (MUTED.get() == null) {
        target.write(b);
      }
    }

    @Override
    public void write(byte[] b, int off, int len) {
      if (MUTED.get() == null) {
        target.write(b, off, len);
      }
    }

    @Override
    public void flush() throws IOException {
      target.flush();
    }
  }
}
