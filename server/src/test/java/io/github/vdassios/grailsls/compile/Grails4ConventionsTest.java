package io.github.vdassios.grailsls.compile;

import java.util.List;

/** {@link ConventionsTest} on the Grails 4 fixture (Groovy 2.5). */
class Grails4ConventionsTest extends ConventionsTest {
  @Override
  App app() {
    return new App("grails4", "GLS_TEST_JDK11", "fixture.library", "book", List.of("legacy", "part/shelf", "summary"), List.of("author"));
  }
}
