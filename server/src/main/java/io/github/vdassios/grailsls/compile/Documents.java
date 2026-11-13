package io.github.vdassios.grailsls.compile;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;

/** Text of the open documents, kept current from incremental changes. Keyed by file path. */
public final class Documents {
  /** An open document at one version. */
  public static final class Document {
    public final String uri;
    public final Path path;
    public final String text;
    public final int version;

    Document(String uri, Path path, String text, int version) {
      this.uri = uri;
      this.path = path;
      this.text = text;
      this.version = version;
    }
  }

  private final Map<Path, Document> open = new ConcurrentHashMap<>();

  /** The path of a {@code file:} URI, or null for any other scheme. */
  public static Path path(String uri) {
    URI parsed = URI.create(uri);
    return "file".equals(parsed.getScheme()) ? Path.of(parsed).normalize() : null;
  }

  public Document open(String uri, String text, int version) {
    Document document = new Document(uri, path(uri), text, version);
    open.put(document.path, document);
    return document;
  }

  /** Applies changes in order; returns the new version, or null when the document is not open. */
  public Document change(String uri, List<TextDocumentContentChangeEvent> changes, int version) {
    Path path = path(uri);
    return open.computeIfPresent(path, (key, document) -> {
      String text = document.text;
      for (TextDocumentContentChangeEvent change : changes) {
        if (change.getRange() == null) {
          text = change.getText();
        } else {
          int start = offset(text, change.getRange().getStart());
          int end = offset(text, change.getRange().getEnd());
          text = text.substring(0, start) + change.getText() + text.substring(end);
        }
      }
      return new Document(document.uri, path, text, version);
    });
  }

  public void close(String uri) {
    open.remove(path(uri));
  }

  public Document get(Path path) {
    return path == null ? null : open.get(path);
  }

  public List<Document> all() {
    return List.copyOf(open.values());
  }

  /** Offset of an LSP position (UTF-16 units, as Java strings count). Positions past the end clamp to it. */
  static int offset(String text, Position position) {
    int offset = 0;
    for (int line = 0; line < position.getLine(); line++) {
      int newline = text.indexOf('\n', offset);
      if (newline < 0) {
        return text.length();
      }
      offset = newline + 1;
    }
    int lineEnd = text.indexOf('\n', offset);
    return Math.min(offset + position.getCharacter(), lineEnd < 0 ? text.length() : lineEnd);
  }
}
