package io.github.vdassios.grailsls.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.vdassios.grailsls.api.SourceLookup;

/**
 * Texts of project files that requests read again and again (each page compile reads every controller, each GSP
 * request every taglib): a file's open text, else its text on disk, which is read again only when the file's
 * modification time or size changed.
 */
final class FileTexts {
  private final Map<String, FileText> read = new ConcurrentHashMap<>();

  private static final class FileText {
    final long modified;
    final long size;
    final String text;

    FileText(long modified, long size, String text) {
      this.modified = modified;
      this.size = size;
      this.text = text;
    }
  }

  /** A file's open text in {@code lookup} (which may be null), else its text on disk; null when unreadable. */
  String of(String file, SourceLookup lookup) {
    String open = lookup == null ? null : lookup.openText(file);
    if (open != null) {
      return open;
    }
    try {
      BasicFileAttributes attributes = Files.readAttributes(Path.of(file), BasicFileAttributes.class);
      long modified = attributes.lastModifiedTime().toMillis();
      FileText known = read.get(file);
      if (known != null && known.modified == modified && known.size == attributes.size()) {
        return known.text;
      }
      String text = Files.readString(Path.of(file));
      read.put(file, new FileText(modified, attributes.size(), text));
      return text;
    } catch (IOException | RuntimeException e) {
      read.remove(file);
      return null;
    }
  }

  void forget(String file) {
    read.remove(file);
  }

  /** Whether a file's text is kept. For tests. */
  boolean keeps(String file) {
    return read.containsKey(file);
  }

  void clear() {
    read.clear();
  }
}
