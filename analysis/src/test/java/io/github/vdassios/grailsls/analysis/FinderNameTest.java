package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class FinderNameTest {
  private static final List<String> PROPERTIES = List.of("title", "isbn", "author", "pages", "pHash", "orderNote", "andOr");

  /** Every row of {@code finder-names.txt}: how the name reads, or that it is no finder. */
  @Test
  void namesReadAsTheTableSays() throws Exception {
    List<String> expected = new ArrayList<>();
    List<String> actual = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(FinderNameTest.class.getResourceAsStream("/finder-names.txt"), StandardCharsets.UTF_8))) {
      for (String line = reader.readLine(); line != null; line = reader.readLine()) {
        if (line.isBlank() || line.startsWith("#")) {
          continue;
        }
        String[] cells = line.split("\\|", -1);
        String name = cells[0].trim();
        expected.add(name + " | " + cells[1].trim() + " | " + cells[2].trim() + " | " + cells[3].trim() + " | " + cells[4].trim());
        actual.add(name + " | " + describe(FinderName.parse(name, PROPERTIES)));
      }
    }
    assertTrue(expected.size() > 30, "rows read");
    assertEquals(String.join("\n", expected), String.join("\n", actual));
  }

  private static String describe(FinderName finder) {
    if (finder == null) {
      return " |  |  | ";
    }
    String conditions = finder.conditions.stream()
        .map(c -> c.property + (c.known ? "" : "?") + (c.negated ? "!" : "") + (c.comparator == null ? "" : ":" + c.comparator)).collect(Collectors.toList()).stream()
        .reduce((a, b) -> a + " " + b).orElse("");
    // connectors between conditions, as written
    StringBuilder joined = new StringBuilder();
    for (int i = 0; i < finder.conditions.size(); i++) {
      FinderName.Condition c = finder.conditions.get(i);
      if (i > 0) {
        FinderName.Condition before = finder.conditions.get(i - 1);
        joined.append(' ').append(finder.name, before.end, c.start).append(' ');
      }
      joined.append(c.property).append(c.known ? "" : "?").append(c.negated ? "!" : "").append(c.comparator == null ? "" : ":" + c.comparator);
    }
    assertEquals(conditions.split(" ").length, finder.conditions.size());
    String problems = finder.problems.stream().map(p -> p.message.contains("No property") ? "property" : p.message.contains("mix") ? "mix"
        : p.message.contains("only") ? "only" : "listOrderBy").collect(Collectors.joining(" "));
    return finder.prefix + " | " + joined + " | " + finder.arguments() + " | " + problems;
  }

  @Test
  void aConditionIsFoundByItsColumn() {
    FinderName finder = FinderName.parse("findByTitleLikeAndIsbn", PROPERTIES);
    assertNull(finder.conditionAt(3), "the prefix");
    assertEquals("title", finder.conditionAt(6).property);
    assertEquals("title", finder.conditionAt(14).property, "its comparator");
    assertNull(finder.conditionAt(16), "the connector");
    assertEquals("isbn", finder.conditionAt(18).property);
    assertEquals("isbn", finder.conditionAt(21).property);
  }

  @Test
  void aFinderReadsInWords() {
    assertEquals("title like ? and not (isbn == ?) and author is not null and pages between ? and ?",
        FinderName.parse("findByTitleLikeAndIsbnNotAndAuthorIsNotNullAndPagesBetween", PROPERTIES).words());
    assertEquals("title == ? or pages >= ?", FinderName.parse("findByTitleOrPagesGreaterThanEquals", PROPERTIES).words());
    assertEquals("all, ordered by title", FinderName.parse("listOrderByTitle", PROPERTIES).words());
  }

  @Test
  void completionGoesOneStepAtATime() {
    List<String> two = List.of("title", "isbn");
    assertEquals(FinderName.PREFIXES, FinderName.continuations("", two));
    assertEquals(List.of("findAllBy", "findBy", "findOrCreateBy", "findOrSaveBy"), FinderName.continuations("fi", two));
    assertEquals(List.of("findByTitle", "findByIsbn"), FinderName.continuations("findBy", two));
    assertEquals(List.of("findByTitle"), FinderName.continuations("findByTi", two));
    List<String> afterProperty = FinderName.continuations("findByTitle", two);
    assertTrue(afterProperty.containsAll(List.of("findByTitleNot", "findByTitleLike", "findByTitleIsNull", "findByTitleAnd", "findByTitleOr")), afterProperty.toString());
    assertEquals(List.of("findByTitleLike", "findByTitleLessThanEquals", "findByTitleLessThan"), sorted(FinderName.continuations("findByTitleL", two)));
    assertEquals(List.of("findByTitleLikeAnd", "findByTitleLikeOr"), FinderName.continuations("findByTitleLike", two));
    assertEquals(List.of("findByTitleLikeAndTitle", "findByTitleLikeAndIsbn"), FinderName.continuations("findByTitleLikeAnd", two));
    // one kind of connector per finder
    assertEquals(List.of("findByTitleAndIsbnAnd"), FinderName.continuations("findByTitleAndIsbn", two).stream().filter(n -> n.endsWith("And") || n.endsWith("Or")).collect(Collectors.toList()));
    assertEquals(List.of("findOrSaveByTitleAnd"), FinderName.continuations("findOrSaveByTitle", two));
    assertEquals(List.of("listOrderByTitle", "listOrderByIsbn"), FinderName.continuations("listOrderBy", two));
    assertEquals(List.of(), FinderName.continuations("listOrderByTitle", two));
    assertEquals(List.of(), FinderName.continuations("get", two));
  }

  private static List<String> sorted(List<String> names) {
    List<String> order = List.of("findByTitleLike", "findByTitleLessThanEquals", "findByTitleLessThan");
    return names.stream().sorted((a, b) -> order.indexOf(a) - order.indexOf(b)).collect(Collectors.toList());
  }
}
