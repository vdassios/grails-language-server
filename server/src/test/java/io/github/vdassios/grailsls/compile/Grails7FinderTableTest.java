package io.github.vdassios.grailsls.compile;

import org.junit.jupiter.api.Tag;

/** {@link FinderTableTest} against the GORM of the Grails 7 fixture (Groovy 4). Its classes need Java 17: run by {@code testJdk17}. */
@Tag("jdk17")
class Grails7FinderTableTest extends FinderTableTest {
  @Override
  String[] fixture() {
    return new String[] { "grails7", "GLS_TEST_JDK17" };
  }
}
