package io.github.vdassios.grailsls.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

class SourceTextTest {
  private static final String JAVA = String.join("\n",
      "package shop;",                                                  // 0
      "",
      "/** A cart. Not the {@link Basket}. */",
      "@SuppressWarnings({\"unchecked\"})",
      "public class Cart<T extends Comparable<T>> {",                  // 4
      "  // void add(String commented) {}",
      "  private static final String NAME = name(\"a } b\", 1);",      // 6
      "  private final List<T> items = create(items(), 2);",           // 7
      "",
      "  /** Makes an empty cart. */",
      "  public Cart() {}",                                             // 10
      "",
      "  Cart(int size) { this.items = new Cart(size).items(); }",      // 12
      "",
      "  /** Adds one. */",
      "  @Deprecated",
      "  public <U> Map<String, U> add(T item) { return add(item, 1); }", // 16
      "",
      "  /**",
      "   * Adds some.",
      "   */",
      "  public Map<String, T> add(T item, int count) {",              // 21
      "    int name = 3;",
      "    return null;",
      "  }",
      "",
      "  public void add(String label, Map<String, List<T>> byLabel) {}", // 26
      "",
      "  static String name(String a, int b) { return a; }",           // 28
      "",
      "  List<T> items() { return items; }",                            // 30
      "",
      "  /** How it is paid. */",
      "  public enum Payment {",                                        // 33
      "    /** Coins. */",
      "    CASH(1),",                                                   // 35
      "    CARD(2) {",                                                  // 36
      "      int fee() { return 1; }",
      "    };",
      "    final int code;",                                            // 39
      "    Payment(int code) { this.code = code; }",                    // 40
      "    int fee() { return 0; }",                                    // 41
      "  }",
      "",
      "  interface Priced { int price(); }",                            // 44
      "}",
      "");

  private static String at(String text, List<String> classes, String member, boolean method, List<String> types) {
    SourceText.Found found = new SourceText(text).find(classes, member, method, types);
    return found == null ? null : found.range.startLine + ":" + found.range.startColumn + (found.comment == null ? "" : " " + found.comment.replaceAll("\\s+", " "));
  }

  private static String java(List<String> classes, String member, boolean method, String... types) {
    return at(JAVA, classes, member, method, List.of(types));
  }

  @Test
  void aClassIsFoundByItsNamesWithItsComment() {
    assertEquals("4:13 /** A cart. Not the {@link Basket}. */", java(List.of("Cart"), null, false));
    assertEquals("33:14 /** How it is paid. */", java(List.of("Cart", "Payment"), null, false));
    assertEquals("44:12", java(List.of("Cart", "Priced"), null, false));
    assertNull(java(List.of("Basket"), null, false));
    assertNull(java(List.of("Payment"), null, false), "nested, not top-level");
  }

  @Test
  void anOverloadIsPickedByItsParameters() {
    assertEquals("16:28 /** Adds one. */", java(List.of("Cart"), "add", true, "Object"));
    assertEquals("21:24 /** * Adds some. */", java(List.of("Cart"), "add", true, "Object", "int"));
    assertEquals("26:14", java(List.of("Cart"), "add", true, "String", "Map"));
    assertEquals("10:9 /** Makes an empty cart. */", java(List.of("Cart"), "Cart", true));
    assertEquals("12:2", java(List.of("Cart"), "Cart", true, "int"));
  }

  @Test
  void callsCommentsAndLiteralsAreNotDeclarations() {
    assertEquals("28:16", java(List.of("Cart"), "name", true, "String", "int"), "not the call in NAME's initializer");
    assertEquals("30:10", java(List.of("Cart"), "items", true), "not the calls in initializers and bodies");
    assertEquals("4:13", java(List.of("Cart"), "create", true, "Object", "int"), "only called: the class, without its comment");
  }

  @Test
  void fieldsAndEnumConstantsAreFoundInTheirOwnClass() {
    assertEquals("6:30", java(List.of("Cart"), "NAME", false));
    assertEquals("7:24", java(List.of("Cart"), "items", false));
    assertEquals("35:4 /** Coins. */", java(List.of("Cart", "Payment"), "CASH", false));
    assertEquals("36:4", java(List.of("Cart", "Payment"), "CARD", false));
    assertEquals("39:14", java(List.of("Cart", "Payment"), "code", false));
    assertEquals("41:8", java(List.of("Cart", "Payment"), "fee", true), "not the constant body's");
    assertEquals("4:13", java(List.of("Cart"), "code", false), "the nested class's field is not the outer's");
  }

  @Test
  void groovyDeclarationsAreFoundToo() {
    String groovy = String.join("\n",
        "package shop",
        "",
        "trait Renders {",
        "  String layout",                                               // 3
        "  def title = 'it''s {'",                                       // 4
        "  private static final PATTERN = ~/a'b/",                       // 5
        "",
        "  /** Renders a view. */",
        "  void render(Map args, Closure body = null) {",                // 8
        "    render(args)",
        "  }",
        "  def render(String text) { \"${text} }\" }",                   // 11
        "  static <T> T first(List<T> all) { all[0] }",                  // 12
        "}",
        "");
    assertEquals("2:6", at(groovy, List.of("Renders"), null, false, List.of()));
    assertEquals("3:9", at(groovy, List.of("Renders"), "layout", false, List.of()));
    assertEquals("4:6", at(groovy, List.of("Renders"), "title", false, List.of()));
    assertEquals("8:7 /** Renders a view. */", at(groovy, List.of("Renders"), "render", true, List.of("Map", "Closure")));
    assertEquals("8:7 /** Renders a view. */", at(groovy, List.of("Renders"), "render", true, List.of("Map")), "a default argument's overload");
    assertEquals("11:6", at(groovy, List.of("Renders"), "render", true, List.of("String")));
    assertEquals("12:15", at(groovy, List.of("Renders"), "first", true, List.of("List")));
  }

  @Test
  void aListLiteralsItemsAndASlashyStringHideNoDeclaration() {
    String groovy = String.join("\n",
        "class Lists {",
        "  def order = [",
        "    'a',",
        "    total,",
        "    2",
        "  ]",
        "  def path = /src\\/*.groovy/",
        "  int total",                                                    // 7
        "  /** Counts. */",
        "  int count",                                                    // 9
        "}",
        "");
    assertEquals("7:6", at(groovy, List.of("Lists"), "total", false, List.of()), "not the item of order's list");
    assertEquals("9:6 /** Counts. */", at(groovy, List.of("Lists"), "count", false, List.of()), "no comment opens in the slashy string");
    assertEquals("def half = total / 2 / count; ~      ; f(   ); return      ", SourceText.code("def half = total / 2 / count; ~/a\\/*/; f(/x/); return /\\d+/"));
  }
}
