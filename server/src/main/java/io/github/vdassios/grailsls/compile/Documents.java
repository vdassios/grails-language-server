package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystemNotFoundException;
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

  /**
   * The path of a {@code file:} URI with symlinks resolved, or null for any other scheme ({@code untitled:},
   * {@code jdt:}). Resolved because the project model holds the real paths Gradle reports: a workspace opened through a
   * symlink (on macOS {@code /tmp} is one) must still match its source roots.
   */
  public static Path path(String uri) {
    try {
      URI parsed = URI.create(uri);
      return "file".equals(parsed.getScheme()) ? realPath(Path.of(parsed)) : null;
    } catch (IllegalArgumentException | FileSystemNotFoundException e) {
      return null; // not a URI, or one that is no absolute path
    }
  }

  /**
   * Symlinks resolved. A file that does not exist (not saved yet, or deleted) gets the real path of its nearest
   * existing directory and the rest of its own path.
   */
  static Path realPath(Path path) {
    Path normal = path.normalize();
    for (Path existing = normal; existing != null; existing = existing.getParent()) {
      try {
        return existing.toRealPath().resolve(existing.relativize(normal));
      } catch (IOException e) {
        // not there: try its directory
      }
    }
    return normal;
  }

  /** Opens a document; returns null, and keeps nothing, when its URI is no file. */
  public Document open(String uri, String text, int version) {
    Path path = path(uri);
    if (path == null) {
      return null;
    }
    Document document = new Document(uri, path, text, version);
    open.put(path, document);
    return document;
  }

  /** Applies changes in order; returns the new version, or null when the document is not open. */
  public Document change(String uri, List<TextDocumentContentChangeEvent> changes, int version) {
    Path path = path(uri);
    if (path == null) {
      return null;
    }
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
    Path path = path(uri);
    if (path != null) {
      open.remove(path);
    }
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
