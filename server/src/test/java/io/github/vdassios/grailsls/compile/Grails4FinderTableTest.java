package io.github.vdassios.grailsls.compile;

/** {@link FinderTableTest} against the GORM of the Grails 4 fixture (GORM 7.0, Groovy 2.5). */
class Grails4FinderTableTest extends FinderTableTest {
  @Override
  String[] fixture() {
    return new String[] { "grails4", "GLS_TEST_JDK11" };
  }
}
