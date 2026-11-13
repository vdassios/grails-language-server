package io.github.vdassios.grailsls.compile;

/** {@link ArtefactMembersTest} on the Grails 4 fixture (Groovy 2.5, JDK 11). */
class Grails4ArtefactsTest extends ArtefactMembersTest {
  @Override
  App app() {
    return new App("grails4", "GLS_TEST_JDK11", "grails-app/controllers/fixture/library/BookController.groovy", "grails-app/domain/fixture/library/Book.groovy",
        "grails-app/services/fixture/library/BookService.groovy", "grails-app/taglib/fixture/library/BookTagLib.groovy", "fixture.library.Book",
        "fixture.library.BookService", "bookService", "titled", 14);
  }
}
