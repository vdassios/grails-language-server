package io.github.vdassios.grailsls.analysis;

/** {@link GspTranslationTest} with the Grails 4 fixture's page parser (Groovy 2.5). */
class Grails4GspTranslationTest extends GspTranslationTest {
  @Override
  String classPath() {
    return "grails4.gsp";
  }
}
