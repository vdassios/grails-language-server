package io.github.vdassios.grailsls.compile;

import org.junit.jupiter.api.Tag;

/** {@link ArtefactMembersTest} on the Grails 7 fixture (Groovy 4). Grails 7 needs Java 17: run by {@code testJdk17}. */
@Tag("jdk17")
class Grails7ArtefactsTest extends ArtefactMembersTest {
  @Override
  App app() {
    return new App("grails7", "GLS_TEST_JDK17", "grails-app/controllers/fixture/catalog/ItemController.groovy", "grails-app/domain/fixture/catalog/Item.groovy",
        "grails-app/services/fixture/catalog/ItemService.groovy", "grails-app/taglib/fixture/catalog/ItemTagLib.groovy", "fixture.catalog.Item",
        "fixture.catalog.ItemService", "itemService", "named", 13);
  }
}
