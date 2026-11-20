package io.github.vdassios.grailsls.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.vdassios.grailsls.project.Fixtures;
import io.github.vdassios.grailsls.project.ModuleRecord;
import io.github.vdassios.grailsls.project.ProjectModel;

/**
 * The finder table ({@code analysis/src/test/resources/finder-names.txt}, which {@code FinderNameTest} holds the
 * server's reading to) against GORM itself: each name goes through GORM's own {@code DynamicFinder}, loaded from the
 * fixture's class path, so the table records GORM's verdict. A name the table accepts must read in GORM as the table
 * says (properties, comparators, connector, arguments); a name it rejects must fail in GORM; a name it does not read as
 * a finder must match none of the six finders.
 */
@Timeout(900)
abstract class FinderTableTest {
  /** The fixture and the variable naming the JDK that builds it. */
  abstract String[] fixture();

  /** The six prefixes, in the order GORM's enhancer tries its finders ({@code GormEnhancer.getFinders}). */
  private static final List<String> PREFIXES = List.of("findOrCreateBy", "findOrSaveBy", "findBy", "findAllBy", "countBy");

  private List<String> properties;
  private Method buildMatchSpec;
  private Pattern[] operatorPatterns;
  private Pattern listOrderBy;
  private Method decapitalizeFirstChar;
  private Class<?> equal;

  @Test
  void theTableRecordsGormsVerdict() throws Exception {
    ProjectModel model = Fixtures.built(fixture()[0], fixture()[1]);
    ModuleRecord.SourceSetRecord main = model.module(":app").sourceSet("main");
    List<URL> urls = new ArrayList<>();
    for (String entry : main.compileClasspath) {
      urls.add(Path.of(entry).toUri().toURL());
    }
    List<String> expected = new ArrayList<>();
    List<String> actual = new ArrayList<>();
    try (URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader())) {
      Class<?> dynamicFinder = Class.forName("org.grails.datastore.gorm.finders.DynamicFinder", true, loader);
      buildMatchSpec = dynamicFinder.getMethod("buildMatchSpec", String.class, String.class, int.class);
      Field operators = dynamicFinder.getDeclaredField("defaultOperationPatterns");
      operators.setAccessible(true);
      operatorPatterns = (Pattern[]) operators.get(null);
      Field pattern = Class.forName("org.grails.datastore.gorm.finders.ListOrderByFinder", true, loader).getDeclaredField("METHOD_PATTERN");
      pattern.setAccessible(true);
      listOrderBy = (Pattern) pattern.get(null);
      decapitalizeFirstChar = Class.forName("org.grails.datastore.mapping.reflect.NameUtils", true, loader).getMethod("decapitalizeFirstChar", String.class);
      equal = Class.forName("org.grails.datastore.gorm.finders.MethodExpression$Equal", true, loader);
      Path table = Path.of(System.getProperty("gls.finderNames"));
      for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
        if (line.startsWith("# Dynamic finder names read against the properties: ")) {
          properties = List.of(line.substring(line.indexOf(':') + 1).trim().split(",\\s*"));
        }
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] cells = line.split("\\|", -1);
        String name = cells[0].trim();
        String verdict;
        if (cells[1].isBlank()) {
          verdict = "no finder";
        } else if (!cells[4].isBlank()) {
          verdict = "rejected";
        } else {
          verdict = cells[1].trim() + " | " + cells[2].trim().replaceAll("\\?", "") + " | " + cells[3].trim();
        }
        expected.add(name + " -> " + verdict);
        actual.add(name + " -> " + gorm(name));
      }
    }
    assertTrue(expected.size() > 30, "rows read");
    assertEquals(String.join("\n", expected), String.join("\n", actual));
  }

  /** GORM's verdict on a name, in the table's terms ({@code prefix | conditions | arguments}), or why it fails. */
  private String gorm(String name) throws Exception {
    for (String prefix : PREFIXES) {
      Object spec;
      try {
        spec = buildMatchSpec.invoke(null, prefix, name, Integer.MAX_VALUE);
      } catch (InvocationTargetException e) {
        // "No property name specified in clause": a condition with nothing before its comparator or connector
        if (e.getCause() instanceof IllegalArgumentException) {
          return "rejected";
        }
        throw e;
      }
      if (spec != null) {
        return reading(prefix, spec);
      }
    }
    Matcher list = listOrderBy.matcher(name);
    if (list.find() && list.start() == 0) {
      // ListOrderByFinder takes the rest of the name as one property
      String property = (String) decapitalizeFirstChar.invoke(null, list.group(2));
      return properties.contains(property) ? "listOrderBy | " + property + " | 0" : "rejected";
    }
    return "no finder";
  }

  private String reading(String prefix, Object spec) throws Exception {
    String sequence = (String) spec.getClass().getMethod("getQueryExpression").invoke(spec);
    String operator = null;
    for (int i = 0; i < operatorPatterns.length && operator == null; i++) {
      if (operatorPatterns[i].matcher(sequence).find()) {
        operator = i == 0 ? "And" : "Or";
      }
    }
    List<String> conditions = new ArrayList<>();
    boolean known = true;
    boolean plain = true;
    for (Object expression : (List<?>) spec.getClass().getMethod("getMethodCallExpressions").invoke(spec)) {
      String property = (String) expression.getClass().getMethod("getPropertyName").invoke(expression);
      known &= properties.contains(property);
      // DynamicFinder wraps a negated expression in an anonymous class that keeps the expression it negates
      boolean negated = expression.getClass().isAnonymousClass();
      Object test = expression;
      if (negated) {
        Field inner = expression.getClass().getDeclaredField("val$finalMe");
        inner.setAccessible(true);
        test = inner.get(expression);
      }
      plain &= !negated && equal.isInstance(test);
      String comparator = test.getClass().getSimpleName();
      conditions.add(property + (negated ? "!" : "") + (comparator.equals("Equal") ? "" : ":" + comparator));
    }
    // FindOrCreateByFinder and FindOrSaveByFinder throw on Or, and on any condition but equality when nothing matches
    if (!known || prefix.startsWith("findOr") && (!plain || "Or".equals(operator))) {
      return "rejected";
    }
    int arguments = (int) spec.getClass().getMethod("getRequiredArguments").invoke(spec);
    return prefix + " | " + String.join(" " + operator + " ", conditions) + " | " + arguments;
  }
}
