package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The name of a GORM dynamic finder, {@code findByTitleLikeAndAuthorIsNotNull}, read against the property names of
 * its domain class. GORM resolves these names at run time; no class has such a method.
 *
 * <pre>
 * finder     := prefix condition (connector condition)*
 * prefix     := countBy | findBy | findAllBy | findOrCreateBy | findOrSaveBy | listOrderBy
 * condition  := Property [Not] [comparator]
 * connector  := And | Or          (one kind per finder)
 * </pre>
 *
 * No Groovy type is used here: offsets are columns in the name.
 */
public final class FinderName {
  /** In the order tried: a prefix that starts another comes after it. */
  static final List<String> PREFIXES = List.of("countBy", "findAllBy", "findBy", "findOrCreateBy", "findOrSaveBy", "listOrderBy");
  /** Longest first, so {@code GreaterThanEquals} is not read as {@code GreaterThan}. */
  static final List<String> COMPARATORS = List.of("GreaterThanEquals", "LessThanEquals", "GreaterThan", "IsNotEmpty", "IsNotNull", "NotEqual", "LessThan", "Between",
      "InRange", "IsEmpty", "InList", "IsNull", "Equal", "Ilike", "Rlike", "Like");
  static final List<String> CONNECTORS = List.of("And", "Or");

  /** One {@code Property [Not] [comparator]}. */
  public static final class Condition {
    /** The property as the domain class names it; as written (first letter in lower case) when it has none. */
    public final String property;
    /** False when the domain class has no such property. */
    public final boolean known;
    public final boolean negated;
    /** Null for plain equality. */
    public final String comparator;
    /** Columns of the whole condition in the name. */
    public final int start;
    public final int end;

    Condition(String property, boolean known, boolean negated, String comparator, int start, int end) {
      this.property = property;
      this.known = known;
      this.negated = negated;
      this.comparator = comparator;
      this.start = start;
      this.end = end;
    }

    /** How many arguments the condition takes. */
    public int arguments() {
      if (comparator == null) {
        return 1;
      }
      switch (comparator) {
        case "IsNull":
        case "IsNotNull":
        case "IsEmpty":
        case "IsNotEmpty":
          return 0;
        case "Between":
          return 2;
        default:
          return 1;
      }
    }

    /** The condition in words, with {@code ?} for each argument: {@code title like ?}. */
    public String words() {
      String test;
      switch (comparator == null ? "Equal" : comparator) {
        case "NotEqual":
          test = "!= ?";
          break;
        case "InList":
          test = "in ?";
          break;
        case "InRange":
          test = "in range ?";
          break;
        case "Between":
          test = "between ? and ?";
          break;
        case "Like":
          test = "like ?";
          break;
        case "Ilike":
          test = "ilike ?";
          break;
        case "Rlike":
          test = "rlike ?";
          break;
        case "GreaterThanEquals":
          test = ">= ?";
          break;
        case "LessThanEquals":
          test = "<= ?";
          break;
        case "GreaterThan":
          test = "> ?";
          break;
        case "LessThan":
          test = "< ?";
          break;
        case "IsNull":
          test = "is null";
          break;
        case "IsNotNull":
          test = "is not null";
          break;
        case "IsEmpty":
          test = "is empty";
          break;
        case "IsNotEmpty":
          test = "is not empty";
          break;
        default:
          test = "== ?";
      }
      return negated ? "not (" + property + " " + test + ")" : property + " " + test;
    }
  }

  /** Something GORM would reject at run time. */
  public static final class Problem {
    public final String message;
    /** Columns in the name. */
    public final int start;
    public final int end;

    Problem(String message, int start, int end) {
      this.message = message;
      this.start = start;
      this.end = end;
    }

    @Override
    public String toString() {
      return message + " [" + start + "," + end + ")";
    }
  }

  public final String name;
  public final String prefix;
  public final List<Condition> conditions;
  /** {@code And} or {@code Or}; null with one condition. */
  public final String connector;
  public final List<Problem> problems;

  private FinderName(String name, String prefix, List<Condition> conditions, String connector, List<Problem> problems) {
    this.name = name;
    this.prefix = prefix;
    this.conditions = List.copyOf(conditions);
    this.connector = connector;
    this.problems = List.copyOf(problems);
  }

  /** The prefix a method name starts with, when a capital letter follows it; else null: not a finder. */
  public static String prefixOf(String name) {
    for (String prefix : PREFIXES) {
      if (name.startsWith(prefix) && name.length() > prefix.length() && Character.isUpperCase(name.charAt(prefix.length()))) {
        return prefix;
      }
    }
    return null;
  }

  /** Reads a method name as a finder over these properties; null when it has no finder prefix. */
  public static FinderName parse(String name, Collection<String> properties) {
    String prefix = prefixOf(name);
    if (prefix == null) {
      return null;
    }
    List<Condition> conditions = new ArrayList<>();
    List<String> connectors = new ArrayList<>();
    if (!known(name, prefix.length(), properties, conditions, connectors)) {
      conditions.clear();
      connectors.clear();
      asGormSplits(name, prefix.length(), properties, conditions, connectors);
    }
    List<Problem> problems = new ArrayList<>();
    for (Condition condition : conditions) {
      if (!condition.known) {
        problems.add(new Problem("No property '" + condition.property + "' for this finder", condition.start, condition.end));
      }
    }
    if (new LinkedHashSet<>(connectors).size() > 1) {
      problems.add(new Problem("A finder cannot mix And and Or", prefix.length(), name.length()));
    }
    if (prefix.equals("listOrderBy")) {
      if (conditions.size() != 1 || conditions.get(0).negated || conditions.get(0).comparator != null) {
        problems.add(new Problem("listOrderBy takes one property and no comparator", prefix.length(), name.length()));
      }
    } else if (prefix.startsWith("findOr")) {
      boolean plain = conditions.stream().noneMatch(c -> c.negated || (c.comparator != null && !c.comparator.equals("Equal")));
      if (!plain || connectors.contains("Or")) {
        problems.add(new Problem(prefix + " takes only equality conditions joined by And", prefix.length(), name.length()));
      }
    }
    return new FinderName(name, prefix, conditions, connectors.isEmpty() ? null : connectors.get(0), problems);
  }

  /** Conditions from {@code at} to the end of the name, every property a known one; false when there is no such reading. */
  private static boolean known(String name, int at, Collection<String> properties, List<Condition> conditions, List<String> connectors) {
    // the longest property first: of "author" and "authorName", the second
    List<String> candidates = new ArrayList<>();
    for (String property : properties) {
      if (!property.isEmpty() && (name.startsWith(property, at) || name.startsWith(capitalized(property), at))) {
        candidates.add(property);
      }
    }
    candidates.sort((a, b) -> b.length() - a.length());
    for (String property : candidates) {
      int afterProperty = at + property.length();
      for (int not = 0; not <= 1; not++) {
        if (not == 1 && !name.startsWith("Not", afterProperty)) {
          continue;
        }
        int afterNot = afterProperty + 3 * not;
        List<String> comparators = new ArrayList<>(COMPARATORS);
        comparators.add(null);
        for (String comparator : comparators) {
          if (comparator != null && !name.startsWith(comparator, afterNot)) {
            continue;
          }
          int end = afterNot + (comparator == null ? 0 : comparator.length());
          Condition condition = new Condition(property, true, not == 1, comparator, at, end);
          if (end == name.length()) {
            conditions.add(condition);
            return true;
          }
          for (String connector : CONNECTORS) {
            int next = end + connector.length();
            if (name.startsWith(connector, end) && next < name.length() && Character.isUpperCase(name.charAt(next))) {
              int size = conditions.size();
              conditions.add(condition);
              connectors.add(connector);
              if (known(name, next, properties, conditions, connectors)) {
                return true;
              }
              conditions.subList(size, conditions.size()).clear();
              connectors.subList(size, connectors.size()).clear();
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * The reading GORM itself makes, without looking at the properties: split at each {@code And} before a capital, or
   * else at each {@code Or}; then a comparator and {@code Not} at the end of each part. What is left is the property.
   */
  private static void asGormSplits(String name, int from, Collection<String> properties, List<Condition> conditions, List<String> connectors) {
    for (String connector : CONNECTORS) {
      List<Integer> cuts = new ArrayList<>();
      for (int i = name.indexOf(connector, from + 1); i >= 0; i = name.indexOf(connector, i + 1)) {
        int next = i + connector.length();
        if (next < name.length() && Character.isUpperCase(name.charAt(next))) {
          cuts.add(i);
        }
      }
      if (cuts.isEmpty() && connector.equals("And")) {
        continue;
      }
      int start = from;
      for (int cut : cuts) {
        conditions.add(part(name, start, cut, properties));
        connectors.add(connector);
        start = cut + connector.length();
      }
      conditions.add(part(name, start, name.length(), properties));
      return;
    }
  }

  private static Condition part(String name, int start, int end, Collection<String> properties) {
    String text = name.substring(start, end);
    String comparator = null;
    for (String candidate : COMPARATORS) {
      if (text.endsWith(candidate) && text.length() > candidate.length()) {
        comparator = candidate;
        text = text.substring(0, text.length() - candidate.length());
        break;
      }
    }
    boolean negated = text.endsWith("Not") && text.length() > 3;
    if (negated) {
      text = text.substring(0, text.length() - 3);
    }
    String property = properties.contains(text) ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    return new Condition(property, properties.contains(property), negated, comparator, start, end);
  }

  /** The condition whose columns hold {@code column}, or null: the prefix, or a connector. */
  public Condition conditionAt(int column) {
    for (Condition condition : conditions) {
      if (column >= condition.start && column < condition.end) {
        return condition;
      }
    }
    return null;
  }

  /** The arguments the finder takes, without the optional map of query settings at the end. */
  public int arguments() {
    return prefix.equals("listOrderBy") ? 0 : conditions.stream().mapToInt(Condition::arguments).sum();
  }

  /** The query in words: {@code title like ? and author is not null}. */
  public String words() {
    if (prefix.equals("listOrderBy")) {
      return "all, ordered by " + (conditions.isEmpty() ? "?" : conditions.get(0).property);
    }
    List<String> parts = new ArrayList<>();
    for (Condition condition : conditions) {
      parts.add(condition.words());
    }
    return String.join(connector == null ? "" : " " + connector.toLowerCase() + " ", parts);
  }

  /**
   * Names one step longer than what is typed, for completion: the prefixes; after a prefix or a connector, each
   * property; after a property, {@code Not}, the comparators and the connectors. A candidate starts with
   * {@code typed}, so a half-typed step ({@code findByTi}) completes too.
   */
  public static List<String> continuations(String typed, Collection<String> properties) {
    Set<String> found = new LinkedHashSet<>();
    for (String prefix : PREFIXES) {
      if (prefix.startsWith(typed) && !prefix.equals(typed)) {
        found.add(prefix);
      }
    }
    String prefix = null;
    for (String candidate : PREFIXES) {
      if (typed.startsWith(candidate)) {
        prefix = candidate;
      }
    }
    if (prefix == null) {
      return new ArrayList<>(found);
    }
    boolean plain = prefix.startsWith("findOr") || prefix.equals("listOrderBy");
    // every cut of the typed name after which a step may begin
    for (int cut = prefix.length(); cut <= typed.length(); cut++) {
      String head = typed.substring(0, cut);
      List<String> steps = new ArrayList<>();
      if (cut == prefix.length() || CONNECTORS.stream().anyMatch(c -> head.endsWith(c) && complete(head.substring(0, head.length() - c.length()), properties))) {
        if (cut == prefix.length() || !prefix.equals("listOrderBy")) {
          properties.forEach(property -> steps.add(capitalized(property)));
        }
      } else if (complete(head, properties) && !prefix.equals("listOrderBy")) {
        FinderName finder = parse(head, properties);
        Condition last = finder.conditions.get(finder.conditions.size() - 1);
        if (!plain && last.comparator == null) {
          if (!last.negated) {
            steps.add("Not");
          }
          steps.addAll(COMPARATORS);
        }
        for (String connector : CONNECTORS) {
          if ((finder.connector == null || finder.connector.equals(connector)) && !(plain && connector.equals("Or"))) {
            steps.add(connector);
          }
        }
      }
      for (String step : steps) {
        String candidate = head + step;
        if (candidate.startsWith(typed) && candidate.length() > typed.length()) {
          found.add(candidate);
        }
      }
    }
    return new ArrayList<>(found);
  }

  /** True for a finder name GORM accepts. */
  private static boolean complete(String name, Collection<String> properties) {
    FinderName finder = parse(name, properties);
    return finder != null && finder.problems.isEmpty();
  }

  static String capitalized(String name) {
    return Character.toUpperCase(name.charAt(0)) + name.substring(1);
  }
}
