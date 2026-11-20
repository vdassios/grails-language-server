package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name of a GORM dynamic finder, {@code findByTitleLikeAndAuthorIsNotNull}, read as GORM reads it and checked
 * against the property names of its domain class. GORM resolves these names at run time; no class has such a method.
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
  /**
   * Longest first, so {@code GreaterThanEquals} is not read as {@code GreaterThan}. GORM's {@code Equal} is no
   * comparator to write: it is what a condition without one means, and GORM reads {@code TitleEqual} as a property.
   */
  static final List<String> COMPARATORS = List.of("GreaterThanEquals", "LessThanEquals", "GreaterThan", "IsNotEmpty", "IsNotNull", "NotInList", "NotEqual", "LessThan",
      "Between", "InRange", "IsEmpty", "InList", "IsNull", "Ilike", "Rlike", "Like");
  /** In the order GORM tries them. */
  static final List<String> CONNECTORS = List.of("And", "Or");
  /** GORM's test that a name is split at a connector ({@code DynamicFinder.defaultOperationPatterns}). */
  private static final List<Pattern> CONNECTOR_IN_USE = List.of(Pattern.compile("(\\w+)(And)(\\p{Upper})(\\w+)"), Pattern.compile("(\\w+)(Or)(\\p{Upper})(\\w+)"));
  /** GORM's comparators in its own order, after a capital and lower-case letters ({@code DynamicFinder.methodExpressinPattern}). */
  private static final Pattern GORM_COMPARATOR = Pattern.compile("\\p{Upper}[\\p{Lower}\\d]+(Equal|NotEqual|NotInList|InList|InRange|Between|Like|Ilike|Rlike|GreaterThanEquals"
      + "|LessThanEquals|GreaterThan|LessThan|IsNull|IsNotNull|IsEmpty|IsNotEmpty)");

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
      switch (comparator == null ? "" : comparator) {
        case "NotEqual":
          test = "!= ?";
          break;
        case "InList":
          test = "in ?";
          break;
        case "NotInList":
          test = "not in ?";
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

  /**
   * Reads a method name as a finder over these properties, as GORM does; null when it has no finder prefix. When GORM
   * finds no property for a condition, the name is read again by the properties it names, to say why: {@code And}
   * mixed with {@code Or}, or a property whose name holds the connector GORM splits at ({@code orderNote} joined by
   * {@code Or}). Conditions are then those of that reading, so each still leads to its property.
   */
  public static FinderName parse(String name, Collection<String> properties) {
    String prefix = prefixOf(name);
    if (prefix == null) {
      return null;
    }
    List<Condition> conditions = new ArrayList<>();
    List<String> connectors = new ArrayList<>();
    String connector = asGorm(name, prefix, properties, conditions, connectors);
    List<Problem> problems = new ArrayList<>();
    if (conditions.stream().anyMatch(c -> !c.known)) {
      List<Condition> written = new ArrayList<>();
      List<String> writtenConnectors = new ArrayList<>();
      if (known(name, prefix.length(), properties, written, writtenConnectors)) {
        problems.addAll(explained(name, prefix, connector, written, writtenConnectors));
      }
      if (problems.isEmpty()) {
        for (int i = 0; i < conditions.size(); i++) {
          Condition condition = conditions.get(i);
          if (condition.property.isEmpty()) {
            // nothing between two connectors, or before a comparator: the connectors around it, to be seen
            int start = i == 0 ? prefix.length() : conditions.get(i - 1).end;
            int end = i == conditions.size() - 1 ? name.length() : conditions.get(i + 1).start;
            problems.add(new Problem("No property named here for this finder", start, end));
          } else if (!condition.known) {
            problems.add(new Problem("No property '" + condition.property + "' for this finder", condition.start, condition.end));
          }
        }
      } else {
        conditions = written;
        connectors = writtenConnectors;
      }
    }
    if (prefix.startsWith("findOr")) {
      boolean plain = conditions.stream().noneMatch(c -> c.negated || c.comparator != null);
      if (!plain || connectors.contains("Or")) {
        problems.add(new Problem(prefix + " takes only equality conditions joined by And", prefix.length(), name.length()));
      }
    }
    return new FinderName(name, prefix, conditions, connectors.isEmpty() ? null : connectors.get(0), problems);
  }

  /**
   * Why GORM rejects a name that reads as conditions on known properties ({@link #known}); empty when the reading
   * gives no reason.
   *
   * @param connector the connector GORM splits at, or null
   */
  private static List<Problem> explained(String name, String prefix, String connector, List<Condition> conditions, List<String> connectors) {
    List<Problem> problems = new ArrayList<>();
    if (prefix.equals("listOrderBy")) {
      if (conditions.size() != 1 || conditions.get(0).negated || conditions.get(0).comparator != null) {
        problems.add(new Problem("listOrderBy takes one property and no comparator", prefix.length(), name.length()));
      }
      return problems;
    }
    if (new LinkedHashSet<>(connectors).size() > 1) {
      problems.add(new Problem("A finder cannot mix And and Or", prefix.length(), name.length()));
      return problems;
    }
    for (Condition condition : conditions) {
      if (connector != null && name.substring(condition.start, condition.end).contains(connector)) {
        problems.add(new Problem("A finder joined by " + connector + " cannot name " + condition.property + ": GORM reads the " + connector + " in its name as a connector",
            condition.start, condition.end));
      }
    }
    return problems;
  }

  /**
   * GORM's own reading ({@code DynamicFinder.createFinderInvocation}), which looks at no property: when an {@code And}
   * that follows a word character comes before a capital, the rest of the name is split at every {@code And}; else
   * likewise at {@code Or}; else it is one condition. Each part is read by {@link #asGormCondition}. A
   * {@code listOrderBy} name is one property ({@code ListOrderByFinder}).
   *
   * @return the connector split at, or null
   */
  private static String asGorm(String name, String prefix, Collection<String> properties, List<Condition> conditions, List<String> connectors) {
    int from = prefix.length();
    if (prefix.equals("listOrderBy")) {
      String property = decapitalized(name.substring(from));
      conditions.add(new Condition(property, properties.contains(property), false, null, from, name.length()));
      return null;
    }
    for (int c = 0; c < CONNECTORS.size(); c++) {
      String connector = CONNECTORS.get(c);
      if (!CONNECTOR_IN_USE.get(c).matcher(name.substring(from)).find()) {
        continue;
      }
      // String.split: a cut at every occurrence, a leading empty part kept and trailing ones dropped
      List<int[]> parts = new ArrayList<>();
      int start = from;
      for (int at = name.indexOf(connector, from); at >= 0; at = name.indexOf(connector, start)) {
        parts.add(new int[] { start, at });
        start = at + connector.length();
      }
      parts.add(new int[] { start, name.length() });
      while (parts.size() > 1 && parts.get(parts.size() - 1)[0] == parts.get(parts.size() - 1)[1]) {
        parts.remove(parts.size() - 1);
      }
      for (int[] part : parts) {
        if (!conditions.isEmpty()) {
          connectors.add(connector);
        }
        conditions.add(asGormCondition(name, part[0], part[1], properties));
      }
      return connector;
    }
    conditions.add(asGormCondition(name, from, name.length(), properties));
    return null;
  }

  /**
   * One part of a name as GORM reads it ({@code DynamicFinder.findMethodExpression}): the first comparator that
   * follows a capital and lower-case letters or digits, the property before the comparator's first occurrence, then a
   * trailing {@code Not}. A part with no such comparator is all property. The property may be empty.
   */
  private static Condition asGormCondition(String name, int start, int end, Collection<String> properties) {
    String part = name.substring(start, end);
    Matcher matcher = GORM_COMPARATOR.matcher(part);
    String comparator = matcher.find() && !matcher.group(1).equals("Equal") ? matcher.group(1) : null;
    String property = comparator == null ? part : part.substring(0, part.indexOf(comparator));
    boolean negated = property.endsWith("Not");
    if (negated) {
      property = property.substring(0, property.length() - 3);
    }
    property = decapitalized(property);
    return new Condition(property, !property.isEmpty() && properties.contains(property), negated, comparator, start, end);
  }

  /** GORM's {@code NameUtils.decapitalizeFirstChar}: {@code URL} is {@code uRL}. */
  private static String decapitalized(String name) {
    return name.isEmpty() ? name : Character.toLowerCase(name.charAt(0)) + name.substring(1);
  }

  /**
   * Conditions from {@code at} to the end of the name, every property a known one, by the property names written in
   * it; false when there is no such reading.
   */
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
      boolean propertyNext = cut == prefix.length() || CONNECTORS.stream().anyMatch(c -> head.endsWith(c) && complete(head.substring(0, head.length() - c.length()), properties));
      if (propertyNext) {
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
        // not a property GORM would split at the connector before it (orderNote after Or)
        if (candidate.startsWith(typed) && candidate.length() > typed.length() && (!propertyNext || complete(candidate, properties))) {
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
