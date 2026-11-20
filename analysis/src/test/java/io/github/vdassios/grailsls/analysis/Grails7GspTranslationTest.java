package io.github.vdassios.grailsls.analysis;

import org.junit.jupiter.api.Tag;

/** {@link GspTranslationTest} with the Grails 7 fixture's page parser (Groovy 4). Grails 7 needs Java 17: run by {@code testJdk17}. */
@Tag("jdk17")
class Grails7GspTranslationTest extends GspTranslationTest {
  @Override
  String classPath() {
    return "grails7.gsp";
  }
}
