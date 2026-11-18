package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * The module loaders of one Groovy jar parse with one prediction cache ({@link AntlrLoader}). The Grails 7 fixture's
 * two modules use Groovy 4.0.33, which needs Java 17: run by {@code testJdk17}.
 */
@Tag("jdk17")
@Timeout(900)
class SharedParserTest {
  private static final String PARSER_ATN_MANAGER = "org.apache.groovy.parser.antlr4.internal.atnmanager.ParserAtnManager";

  @TempDir
  Path tmp;

  /**
   * The cache is the ATN of the module that parsed first ({@code support} here); the others adopt it. Holding that
   * ATN after the close must not keep its module loader alive. The {@code app} loader is not checked: on JDK 17 Grails
   * 7's Groovy 4 (indy) keeps it alive with or without sharing (JDK 17's {@code MethodHandle.asTypeCache}); on JDK 21
   * it goes once soft references are cleared, with or without sharing (measured 2026-11-16).
   */
  @Test
  void contextsOfOneGroovyJarShareOneParserCacheThatKeepsNoLoaderAlive() throws Exception {
    ProjectModel model = Fixtures.built("grails7", "GLS_TEST_JDK17");
    Workspace workspace = new Workspace(model, AnalysisJar.extract(tmp));
    workspace.index();
    Path clock = Path.of(model.module(":support").projectDir, "src/main/groovy/fixture/support/Clock.groovy");
    Path controller = Path.of(model.module(":app").projectDir, "grails-app/controllers/fixture/catalog/ItemController.groovy");
    assertEquals(List.of(), errors(workspace, clock));
    int afterSupport = states(parserAtn(workspace.contextOf(clock).loader()));
    assertEquals(List.of(), errors(workspace, controller));

    assertNotSame(workspace.contextOf(clock).loader(), workspace.contextOf(controller).loader());
    Object atn = parserAtn(workspace.contextOf(clock).loader());
    assertSame(atn, parserAtn(workspace.contextOf(controller).loader()), "one ATN, so one cache, for both module loaders");
    assertTrue(states(atn) > afterSupport, "the controller's parse added to the cache support filled");

    WeakReference<ClassLoader> support = new WeakReference<>(workspace.contextOf(clock).loader());
    workspace.close();
    assertTrue(Gc.collected(support), "the shared cache keeps the module loader it came from alive");
    assertTrue(states(atn) > 0, "still referenced");
  }

  private static List<String> errors(Workspace workspace, Path file) throws Exception {
    CompileContext context = workspace.contextOf(file);
    CompileRequest request = new CompileRequest(file.toString(), Files.readString(file), workspace.lookup(context, p -> null), () -> false);
    return context.compile(request).get(120, TimeUnit.SECONDS).diagnostics.stream().filter(d -> d.severity == Diagnostic.ERROR)
        .map(Diagnostic::toString).collect(Collectors.toList());
  }

  /** The ATN the loader's Groovy creates its parsers with. */
  private static Object parserAtn(ClassLoader loader) throws ReflectiveOperationException {
    Class<?> manager = Class.forName(PARSER_ATN_MANAGER, true, loader);
    return manager.getMethod("getATN").invoke(manager.getField("INSTANCE").get(null));
  }

  private static int states(Object atn) throws ReflectiveOperationException {
    Object decisions = atn.getClass().getField("decisionToDFA").get(atn);
    int count = 0;
    for (int i = 0; i < Array.getLength(decisions); i++) {
      Object dfa = Array.get(decisions, i);
      count += ((Map<?, ?>) dfa.getClass().getField("states").get(dfa)).size();
    }
    return count;
  }
}
