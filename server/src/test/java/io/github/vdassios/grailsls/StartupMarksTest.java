package io.github.vdassios.grailsls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class StartupMarksTest {
  /** A stage reached again (a reload, the next document's diagnostics) is not a startup stage. */
  @Test
  void eachStageIsLoggedOnce() {
    List<String> logs = new ArrayList<>();
    StartupMarks marks = new StartupMarks(logs::add);
    marks.mark(StartupMarks.MODEL_RESOLVED);
    marks.mark(StartupMarks.FIRST_DIAGNOSTICS);
    marks.mark(StartupMarks.MODEL_RESOLVED);
    marks.mark(StartupMarks.FIRST_DIAGNOSTICS);
    assertEquals(2, logs.size(), logs.toString());
    assertTrue(logs.get(0).matches("startup: model resolved after \\d+ ms"), logs.get(0));
    assertTrue(logs.get(1).startsWith("startup: first diagnostics after "), logs.get(1));
  }
}
