package io.github.vdassios.grailsls.compile;

/** {@link GormTest} on the Grails 4 fixture (Groovy 2.5). */
class Grails4GormTest extends GormTest {
  @Override
  App app() {
    return new App("grails4", "GLS_TEST_JDK11", "fixture.library", "Book", "title", "isbn", "Author", "name", "books");
  }
}
