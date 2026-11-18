package io.github.vdassios.grailsls.compile;

import org.junit.jupiter.api.Tag;

/** {@link ConventionsTest} on the Grails 7 fixture (Groovy 4). Grails 7 needs Java 17: run by {@code testJdk17}. */
@Tag("jdk17")
class Grails7ConventionsTest extends ConventionsTest {
  @Override
  App app() {
    return new App("grails7", "GLS_TEST_JDK17", "fixture.catalog", "item");
  }
}
