package io.github.vdassios.grailsls.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
 * attribute its taglib documents as {@code REQUIRED}. A page's "compile" only reads its tags.
 */
final class GspViews {
  private final Taglibs taglibs;
  private final Map<String, Page> pages = new ConcurrentHashMap<>();
  /** The documentation of each item of a page's last completion, by its id. */
  private final Map<String, List<CompletionDetail>> completions = new ConcurrentHashMap<>();

  GspViews(Taglibs taglibs) {
    this.taglibs = taglibs;
  }

  static boolean isGsp(String path) {
    return path.endsWith(".gsp");
  }

  /** A page's text and tags, as last "compiled". */
  private static final class Page {
    final String text;
    final SourceLookup lookup;
    final List<GspScanner.Tag> tags;
    final int[] lineStarts;

    Page(String text, SourceLookup lookup) {
      this.text = text;
      this.lookup = lookup;
      this.tags = GspScanner.scan(text);
      List<Integer> starts = new ArrayList<>();
      starts.add(0);
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '\n') {
          starts.add(i + 1);
        }
      }
      this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
    }

    int offset(int line, int column) {
      if (line >= lineStarts.length) {
        return text.length();
      }
      int end = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : text.length();
      return Math.min(lineStarts[line] + column, end);
    }

    Range range(int start, int end) {
      return new Range(line(start), start - lineStarts[line(start)], line(end), end - lineStarts[line(end)]);
    }

    private int line(int offset) {
      int low = 0;
      int high = lineStarts.length - 1;
      while (low < high) {
        int middle = (low + high + 1) >>> 1;
        if (lineStarts[middle] <= offset) {
          low = middle;
        } else {
          high = middle - 1;
        }
      }
      return low;
    }
  }

  CompileResult compile(CompileRequest request) {
    long start = System.nanoTime();
    Page page = new Page(request.text, request.lookup);
    pages.put(request.path, page);
    completions.remove(request.path);
    List<Diagnostic> hints = new ArrayList<>();
    Map<String, Map<String, Taglibs.Tag>> all = taglibs.all(request.lookup);
    for (GspScanner.Tag tag : page.tags) {
      Map<String, Taglibs.Tag> namespace = all.get(tag.namespace);
      if (tag.closing || namespace == null || tag.name.isEmpty()) {
        continue;
      }
      Taglibs.Tag known = namespace.get(tag.name);
      if (known == null) {
        hints.add(hint(request.path, page.range(tag.namespaceStart(), tag.nameEnd), "Unknown tag <" + tag.namespace + ":" + tag.name + ">: no taglib of namespace '" + tag.namespace + "' declares it"));
        continue;
      }
      for (Taglibs.Attribute attribute : known.docs().attributes) {
        if (attribute.required && tag.attribute(attribute.name) == null) {
          hints.add(hint(request.path, page.range(tag.namespaceStart(), tag.nameEnd), "<" + tag.namespace + ":" + tag.name + "> needs the attribute '" + attribute.name + "'"));
        }
      }
    }
    return new CompileResult(hints, false, 0, (System.nanoTime() - start) / 1_000_000);
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
    int offset = page.offset(line, column);
    Taglibs.Tag known = tagNamed(page, GspScanner.at(page.tags, offset), offset);
    return known == null ? null : known.docs().location;
  }

  String hover(String path, int line, int column) {
    Page page = pages.get(path);
    if (page == null) {
      return null;
    }
    int offset = page.offset(line, column);
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

  CompletionList completion(CompileRequest request, int line, int column) {
    Page page = new Page(request.text, request.lookup);
    int offset = page.offset(line, column);
    GspScanner.Tag tag = GspScanner.at(page.tags, offset);
    completions.remove(request.path);
    if (tag == null || tag.closing && offset > tag.nameEnd) {
      return CompletionList.EMPTY;
    }
    Map<String, Map<String, Taglibs.Tag>> all = taglibs.all(request.lookup);
    List<Completion> items = new ArrayList<>();
    List<CompletionDetail> details = new ArrayList<>();
    if (offset >= tag.namespaceStart() && offset < tag.nameStart) {
      Range replace = page.range(tag.namespaceStart(), tag.nameStart - 1);
      for (String namespace : all.keySet()) {
        items.add(new Completion(namespace, Completion.Kind.PACKAGE, "namespace", null, replace, List.of(), -1));
      }
    } else if (offset >= tag.nameStart && offset <= tag.nameEnd) {
      Map<String, Taglibs.Tag> namespace = all.get(tag.namespace);
      Range replace = page.range(tag.nameStart, tag.nameEnd);
      for (Taglibs.Tag known : namespace == null ? List.<Taglibs.Tag>of() : namespace.values()) {
        items.add(new Completion(known.name, Completion.Kind.METHOD, known.simpleOwner() == null ? "GSP tag" : known.simpleOwner(), null, replace, List.of(), details.size()));
        details.add(new CompletionDetail(known.owner == null ? "GSP tag" : known.owner, markdown(known)));
      }
    } else if (!tag.closing && !inValue(tag, offset)) {
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
        Range replace = page.range(start, end);
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
    if (!details.isEmpty()) {
      completions.put(request.path, details);
    }
    return items.isEmpty() ? CompletionList.EMPTY : new CompletionList(items, false);
  }

  private static boolean inValue(GspScanner.Tag tag, int offset) {
    for (GspScanner.Attribute attribute : tag.attributes) {
      if (attribute.valueStart >= 0 && offset >= attribute.valueStart && offset <= attribute.valueEnd) {
        return true;
      }
    }
    return false;
  }

  private static boolean isNameChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
  }

  CompletionDetail resolveCompletion(String path, int id) {
    List<CompletionDetail> details = completions.get(path);
    return details == null || id < 0 || id >= details.size() ? null : details.get(id);
  }

  void forget(String path) {
    pages.remove(path);
    completions.remove(path);
  }

  void clear() {
    pages.clear();
    completions.clear();
  }
}
