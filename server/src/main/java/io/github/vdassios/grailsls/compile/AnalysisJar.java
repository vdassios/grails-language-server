package io.github.vdassios.grailsls.compile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The {@code analysis} layer ships inside the server jar as a nested jar ({@code analysis.bin} next to this class),
 * because it must be loaded by each module loader, not by the server's. Extracted once per content hash.
 */
public final class AnalysisJar {
  private static final String RESOURCE = "analysis.bin";

  private AnalysisJar() {}

  /** The jar, extracted into {@code cacheDir} unless it is there already. */
  public static Path extract(Path cacheDir) {
    byte[] bytes;
    try (InputStream in = AnalysisJar.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(RESOURCE + " is missing from the server jar");
      }
      bytes = in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Path target = cacheDir.resolve("analysis-" + sha256(bytes).substring(0, 16) + ".jar");
    if (Files.isRegularFile(target)) {
      return target;
    }
    try {
      Files.createDirectories(cacheDir);
      Path tmp = Files.createTempFile(cacheDir, "analysis", ".tmp");
      Files.write(tmp, bytes);
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return target;
  }

  private static String sha256(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
