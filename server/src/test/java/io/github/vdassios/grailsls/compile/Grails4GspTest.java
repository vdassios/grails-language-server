package io.github.vdassios.grailsls.compile;

/** {@link GspTest} on the Grails 4 fixture (Groovy 2.5). */
class Grails4GspTest extends GspTest {
  @Override
  App app() {
    return new App("grails4", "GLS_TEST_JDK11", "book", "library", "titles", "taglib/fixture/library/BookTagLib.groovy");
  }
}
