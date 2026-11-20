package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import io.github.vdassios.grailsls.api.CompileRequest;
import io.github.vdassios.grailsls.api.CompileResult;
import io.github.vdassios.grailsls.api.Completion;
import io.github.vdassios.grailsls.api.CompletionDetail;
import io.github.vdassios.grailsls.api.CompletionList;
import io.github.vdassios.grailsls.api.Diagnostic;
import io.github.vdassios.grailsls.api.Location;
import io.github.vdassios.grailsls.api.Range;
import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * GSP pages, without compiling them: completion of tag names after {@code <ns:} and of attribute names inside a tag,
 * hover and definition of tags, and hints for an unknown tag in a namespace some taglib declares and for a missing
 * attribute its taglib documents as {@code REQUIRED}. Attribute values Grails resolves by convention (plan B3) go
 * through {@link Conventions}: definition, value completion, and hints for a template, view, layout or asset that does
 * not exist. A page's "compile" only reads its tags.
 */
final class GspViews {
  private static final Set<String> ASSET_TAGS = Set.of("stylesheet", "javascript", "image", "assetPath", "link");
  private static final String VIEWS = "/grails-app/views/";

  private final Taglibs taglibs;
  /** Conventions for a lookup and a page path. */
  private final BiFunction<SourceLookup, String, Conventions> conventions;
  private final Map<String, Page> pages = new ConcurrentHashMap<>();

  GspViews(Taglibs taglibs, BiFunction<SourceLookup, String, Conventions> conventions) {
    this.taglibs = taglibs;
    this.conventions = conventions;
  }

  static boolean isGsp(String path) {
    return path.endsWith(".gsp");
  }

  /** A page's scan, as last "compiled". */
  private static final class Page {
    final String text;
    final SourceLookup lookup;
    final List<GspScanner.Tag> tags;
    final Lines lines;

    Page(GspScanner.Scan scan, SourceLookup lookup) {
      this.text = scan.text;
      this.lookup = lookup;
      this.tags = scan.tags;
      this.lines = new Lines(scan.text);
    }
  }

  /** The tag hints of a page; {@code scan} is of the request's text. */
  CompileResult compile(CompileRequest request, GspScanner.Scan scan) {
    long start = System.nanoTime();
    Page page = new Page(scan, request.lookup);
    pages.put(request.path, page);
    List<Diagnostic> hints = new ArrayList<>();
    Taglibs.Index index = taglibs.index(request.lookup);
    Map<String, Map<String, Taglibs.Tag>> all = index.tags;
    Conventions files = conventions.apply(request.lookup, request.path);
    String controller = viewController(viewDir(request.path));
    for (GspScanner.Tag tag : page.tags) {
      if (tag.attribute("plugin") != null || tag.attribute("contextPath") != null) {
        // the file is looked up in a plugin or below another path: not modelled, so no hint
        continue;
      }
      for (GspScanner.Attribute attribute : tag.attributes) {
        Conventions.Reference reference = reference(tag, attribute, attribute.value, controller);
        String missing = reference == null || reference.kind == Conventions.Kind.CONTROLLER || reference.kind == Conventions.Kind.ACTION ? null : files.missing(reference);
        if (missing != null) {
          hints.add(hint(request.path, page.lines.range(attribute.valueStart, attribute.valueEnd), missing));
        }
      }
    }
    for (GspScanner.Tag tag : page.tags) {
      Map<String, Taglibs.Tag> namespace = all.get(tag.namespace);
      if (tag.closing || namespace == null || tag.name.isEmpty()) {
        continue;
      }
      Taglibs.Tag known = namespace.get(tag.name);
      if (known == null) {
        // a namespace whose taglib inherits from a class that cannot be read may declare it after all
        if (index.complete(tag.namespace)) {
          hints.add(hint(request.path, page.lines.range(tag.namespaceStart(), tag.nameEnd), "Unknown tag <" + tag.namespace + ":" + tag.name + ">: no taglib of namespace '" + tag.namespace + "' declares it"));
        }
        continue;
      }
      for (Taglibs.Attribute attribute : known.docs().attributes) {
        if (attribute.required && tag.attribute(attribute.name) == null) {
          hints.add(hint(request.path, page.lines.range(tag.namespaceStart(), tag.nameEnd), "<" + tag.namespace + ":" + tag.name + "> needs the attribute '" + attribute.name + "'"));
        }
      }
    }
    return new CompileResult(hints, false, 0, (System.nanoTime() - start) / 1_000_000);
  }

  /** The page's directory below {@code grails-app/views} ({@code ""} for that directory), or null when it is not below one. */
  static String viewDir(String path) {
    String normal = path.replace('\\', '/');
    int at = normal.lastIndexOf(VIEWS);
    if (at < 0) {
      return null;
    }
    String below = normal.substring(at + VIEWS.length());
    int slash = below.lastIndexOf('/');
    return slash < 0 ? "" : below.substring(0, slash);
  }

  /**
   * The logical name of the controller a page's relative names start from: Grails resolves them against the controller
   * of the request, whose views are {@code views/<controller>/} (a page in a directory below that is rendered by the same
   * controller). Null for a page directly in {@code views/}.
   */
  private static String viewController(String dir) {
    return dir == null || dir.isEmpty() ? null : dir.contains("/") ? dir.substring(0, dir.indexOf('/')) : dir;
  }

  /**
   * The convention reference an attribute's value (or the part of it given) is, or null: {@code g:render template},
   * {@code g:include view}, {@code g:applyLayout name}, {@code <meta name="layout" content>}, {@code src} and
   * {@code href} of {@code asset:} tags, {@code controller} and {@code action} of any {@code g:} tag (other
   * namespaces use the names for their own ends). A value holding an expression or a URL is none.
   */
  private static Conventions.Reference reference(GspScanner.Tag tag, GspScanner.Attribute attribute, String value, String controller) {
    if (tag.closing || value == null || value.contains("${") || value.contains("<%") || value.contains("%{")) {
      return null;
    }
    String key = attribute.name;
    switch (tag.namespace) {
      case "":
        GspScanner.Attribute name = tag.attribute("name");
        return key.equals("content") && name != null && "layout".equals(name.value) ? new Conventions.Reference(Conventions.Kind.LAYOUT, value, null, null) : null;
      case "asset":
        return ASSET_TAGS.contains(tag.name) && (key.equals("src") || key.equals("href")) && !value.contains("//") ? new Conventions.Reference(Conventions.Kind.ASSET, value, null, tag.name) : null;
      case "g":
        break;
      default:
        return null;
    }
    GspScanner.Attribute attributeController = tag.attribute("controller");
    // the controller attribute's value, "" when it is not a literal, null when there is none
    String named = attributeController == null ? null
        : attributeController.value == null || attributeController.value.contains("${") || attributeController.value.contains("<%") ? "" : attributeController.value;
    switch (key) {
      case "template":
        return tag.name.equals("render") ? new Conventions.Reference(Conventions.Kind.TEMPLATE, value, null, controller) : null;
      case "view":
        return tag.name.equals("include") ? new Conventions.Reference(Conventions.Kind.VIEW, value, null, named == null ? controller : named.isEmpty() ? null : named) : null;
      case "name":
        return tag.name.equals("applyLayout") ? new Conventions.Reference(Conventions.Kind.LAYOUT, value, null, null) : null;
      case "controller":
        return new Conventions.Reference(Conventions.Kind.CONTROLLER, value, null, null);
      case "action":
        return new Conventions.Reference(Conventions.Kind.ACTION, value, null, named == null ? controller : named.isEmpty() ? null : named);
      default:
        return null;
    }
  }

  /** The tag attribute whose value holds an offset, or null. */
  private static GspScanner.Attribute valueAt(GspScanner.Tag tag, int offset) {
    for (GspScanner.Attribute attribute : tag.attributes) {
      if (attribute.valueStart >= 0 && offset >= attribute.valueStart && offset <= attribute.valueEnd) {
        return attribute;
      }
    }
    return null;
  }

  private static Diagnostic hint(String path, Range range, String message) {
    return new Diagnostic(path, range.startLine, range.startColumn, range.endLine, range.endColumn, Diagnostic.HINT, message);
  }

  /** The tag whose namespace or name is at an offset, and the taglib tag it is; null when there is none. */
  private Taglibs.Tag tagNamed(Page page, GspScanner.Tag tag, int offset) {
    if (tag == null || offset < tag.namespaceStart() || offset > tag.nameEnd) {
      return null;
    }
    Map<String, Taglibs.Tag> namespace = taglibs.all(page.lookup).get(tag.namespace);
    return namespace == null ? null : namespace.get(tag.name);
  }

  Location definition(String path, int line, int column) {
    Page page = pages.get(path);
    if (page == null) {
      return null;
    }
    int offset = page.lines.offset(line, column);
    GspScanner.Tag tag = GspScanner.at(page.tags, offset);
    GspScanner.Attribute attribute = tag == null ? null : valueAt(tag, offset);
    if (attribute != null) {
      Conventions.Reference reference = reference(tag, attribute, attribute.value, viewController(viewDir(path)));
      return reference == null ? null : conventions.apply(page.lookup, path).definition(reference, null);
    }
    Taglibs.Tag known = tagNamed(page, tag, offset);
    return known == null ? null : known.docs().location;
  }

  String hover(String path, int line, int column) {
    Page page = pages.get(path);
    if (page == null) {
      return null;
    }
    int offset = page.lines.offset(line, column);
    GspScanner.Tag tag = GspScanner.at(page.tags, offset);
    Taglibs.Tag known = tagNamed(page, tag, offset);
    if (known != null) {
      return markdown(known);
    }
    if (tag == null) {
      return null;
    }
    for (GspScanner.Attribute attribute : tag.attributes) {
      if (offset >= attribute.nameStart && offset <= attribute.nameEnd()) {
        Map<String, Taglibs.Tag> namespace = taglibs.all(page.lookup).get(tag.namespace);
        Taglibs.Tag owner = namespace == null ? null : namespace.get(tag.name);
        Taglibs.Attribute documented = owner == null ? null : owner.docs().attribute(attribute.name);
        return documented == null ? null : markdown(owner, documented);
      }
    }
    return null;
  }

  private static String header(Taglibs.Tag tag) {
    return "```gsp\n<" + tag.namespace + ":" + tag.name + ">\n```\n" + (tag.owner == null ? "GSP tag" : "`" + tag.owner + "`");
  }

  static String markdown(Taglibs.Tag tag) {
    Taglibs.Docs docs = tag.docs();
    StringBuilder text = new StringBuilder(header(tag));
    if (docs.description != null) {
      text.append("\n\n").append(docs.description);
    }
    if (!docs.attributes.isEmpty()) {
      text.append("\n\nAttributes:");
      for (Taglibs.Attribute attribute : docs.attributes) {
        text.append("\n- ").append(line(attribute));
      }
    }
    return text.toString();
  }

  private static String markdown(Taglibs.Tag tag, Taglibs.Attribute attribute) {
    return header(tag) + "\n\n" + line(attribute);
  }

  private static String line(Taglibs.Attribute attribute) {
    return "`" + attribute.name + "`" + (attribute.required ? " (required)" : "") + (attribute.doc == null ? "" : ": " + attribute.doc);
  }

  /**
   * Tag, attribute and attribute value completion; {@code scan} is of the request's text. The documentation of each
   * item with an id is added to {@code details}, at its id.
   */
  CompletionList completion(CompileRequest request, GspScanner.Scan scan, int line, int column, List<CompletionDetail> details) {
    Page page = new Page(scan, request.lookup);
    int offset = page.lines.offset(line, column);
    GspScanner.Tag tag = GspScanner.at(page.tags, offset);
    if (tag == null || tag.closing && offset > tag.nameEnd) {
      return CompletionList.EMPTY;
    }
    Map<String, Map<String, Taglibs.Tag>> all = taglibs.all(request.lookup);
    List<Completion> items = new ArrayList<>();
    if (offset >= tag.namespaceStart() && offset < tag.nameStart) {
      Range replace = page.lines.range(tag.namespaceStart(), tag.nameStart - 1);
      for (String namespace : all.keySet()) {
        items.add(new Completion(namespace, Completion.Kind.PACKAGE, "namespace", null, replace, List.of(), -1));
      }
    } else if (offset >= tag.nameStart && offset <= tag.nameEnd) {
      Map<String, Taglibs.Tag> namespace = all.get(tag.namespace);
      Range replace = page.lines.range(tag.nameStart, tag.nameEnd);
      for (Taglibs.Tag known : namespace == null ? List.<Taglibs.Tag>of() : namespace.values()) {
        items.add(new Completion(known.name, Completion.Kind.METHOD, known.simpleOwner() == null ? "GSP tag" : known.simpleOwner(), null, replace, List.of(), details.size()));
        details.add(new CompletionDetail(known.owner == null ? "GSP tag" : known.owner, markdown(known)));
      }
    } else if (!tag.closing && valueAt(tag, offset) != null) {
      GspScanner.Attribute attribute = valueAt(tag, offset);
      String typed = page.text.substring(attribute.valueStart, offset);
      Conventions.Reference reference = reference(tag, attribute, typed, viewController(viewDir(request.path)));
      if (reference != null) {
        items.addAll(conventions.apply(request.lookup, request.path).candidates(reference, null, typed, page.lines.range(attribute.valueStart, offset)));
      }
    } else if (!tag.closing) {
      Map<String, Taglibs.Tag> namespace = all.get(tag.namespace);
      Taglibs.Tag known = namespace == null ? null : namespace.get(tag.name);
      if (known != null) {
        int start = offset;
        while (start > tag.nameEnd && isNameChar(page.text.charAt(start - 1))) {
          start--;
        }
        int end = offset;
        while (end < page.text.length() && isNameChar(page.text.charAt(end))) {
          end++;
        }
        Set<String> given = new HashSet<>();
        for (GspScanner.Attribute attribute : tag.attributes) {
          if (attribute.nameStart != start) {
            given.add(attribute.name);
          }
        }
        Range replace = page.lines.range(start, end);
        for (Taglibs.Attribute attribute : known.docs().attributes) {
          if (!given.contains(attribute.name)) {
            // required attributes first
            String sort = (attribute.required ? "0" : "1") + attribute.name;
            items.add(new Completion(attribute.name, Completion.Kind.FIELD, attribute.required ? "required" : null, sort, replace, List.of(), details.size()));
            details.add(new CompletionDetail(attribute.required ? "required" : null, markdown(known, attribute)));
          }
        }
      }
    }
    return items.isEmpty() ? CompletionList.EMPTY : new CompletionList(items, false);
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
  }

  void forget(String path) {
    pages.remove(path);
  }

  void clear() {
    pages.clear();
  }
}
