package io.github.vdassios.grailsls.compile;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

final class Gc {
  private Gc() {}

  /** True once the referent is collected; allocates to push the collector. */
  static boolean collected(WeakReference<?> reference) throws InterruptedException {
    List<byte[]> pressure = new ArrayList<>();
    for (int i = 0; i < 50 && reference.get() != null; i++) {
      System.gc();
      pressure.add(new byte[1 << 20]);
      if (pressure.size() > 20) {
        pressure.clear();
      }
      Thread.sleep(50);
    }
    return reference.get() == null;
  }
}
