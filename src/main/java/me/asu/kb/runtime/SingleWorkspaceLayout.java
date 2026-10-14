package me.asu.kb.runtime;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import me.asu.json.OKJSON;

/** Validates or initializes the fresh, versioned single-workspace layout. */
public final class SingleWorkspaceLayout {
  public static final String MARKER_FILE_NAME = ".engineering-knowledge-workspace.json";
  public static final String FORMAT = "engineering-knowledge-single-workspace";
  public static final int VERSION = 1;

  private static final Set<String> MARKER_FIELDS = Set.of("format", "version");
  private static final byte[] MARKER_BYTES =
      ("{\n  \"format\": \"" + FORMAT + "\",\n  \"version\": " + VERSION + "\n}\n")
          .getBytes(StandardCharsets.UTF_8);

  private SingleWorkspaceLayout() {}

  /**
   * Opens a compatible layout or initializes an absent/empty root.
   *
   * <p>This method does not open Store or index resources. Unrecognized existing state is inspected
   * read-only and rejected before any file is created.
   */
  public static OpenedWorkspace open(SingleWorkspaceServiceConfig config) throws IOException {
    if (config == null) {
      throw new NullPointerException("config");
    }
    Path root = config.workspaceRoot();
    Path marker = config.resolveInsideWorkspace(Path.of(MARKER_FILE_NAME));

    if (Files.exists(root)) {
      if (!Files.isDirectory(root)) {
        throw incompatible("workspace root is not a directory");
      }
      if (Files.exists(marker)) {
        validateMarker(marker);
        return new OpenedWorkspace(root, marker, false);
      }
      if (!isEmptyDirectory(root)) {
        throw incompatible(
            "workspace is non-empty but has no compatible marker; use a fresh workspace root");
      }
    } else {
      Files.createDirectories(root);
    }

    publishMarker(marker);
    return new OpenedWorkspace(root, marker, true);
  }

  private static boolean isEmptyDirectory(Path root) throws IOException {
    try (var entries = Files.newDirectoryStream(root)) {
      return !entries.iterator().hasNext();
    }
  }

  private static void validateMarker(Path marker) throws IOException {
    if (!Files.isRegularFile(marker)) {
      throw incompatible("workspace marker is not a regular file");
    }

    String json = Files.readString(marker, StandardCharsets.UTF_8);
    Map<String, Object> values;
    try {
      values = OKJSON.toMap(json, OKJSON.OPTIONS_STRICT_POLICY);
    } catch (RuntimeException error) {
      throw incompatible("workspace marker is malformed", error);
    }
    if (values == null || hasTrailingContent(json) || !MARKER_FIELDS.equals(values.keySet())) {
      throw incompatible("workspace marker must contain exactly format and version");
    }
    if (!FORMAT.equals(values.get("format"))) {
      throw incompatible("workspace marker format is incompatible");
    }
    if (!BigInteger.ONE.equals(values.get("version"))) {
      throw incompatible("workspace marker version must be integer 1");
    }
  }

  private static boolean hasTrailingContent(String json) {
    boolean inString = false;
    boolean escaped = false;
    int depth = 0;
    for (int index = 0; index < json.length(); index++) {
      char character = json.charAt(index);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (character == '\\') {
          escaped = true;
        } else if (character == '"') {
          inString = false;
        }
      } else if (character == '"') {
        inString = true;
      } else if (character == '{' || character == '[') {
        depth++;
      } else if (character == '}' || character == ']') {
        depth--;
        if (depth == 0) {
          return !json.substring(index + 1).isBlank();
        }
      }
    }
    return true;
  }

  private static void publishMarker(Path marker) throws IOException {
    Path temporary =
        marker.resolveSibling("." + MARKER_FILE_NAME + "." + UUID.randomUUID() + ".tmp");
    boolean published = false;
    try {
      try (FileChannel channel =
          FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
        channel.write(ByteBuffer.wrap(MARKER_BYTES));
        channel.force(true);
      }
      try {
        Files.move(temporary, marker, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException error) {
        throw new IOException("atomic workspace marker publication is not supported", error);
      }
      published = true;
    } finally {
      if (!published) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static IOException incompatible(String message) {
    return new IOException(message);
  }

  private static IOException incompatible(String message, Throwable cause) {
    return new IOException(message, cause);
  }

  public record OpenedWorkspace(Path root, Path marker, boolean initialized) {}
}
