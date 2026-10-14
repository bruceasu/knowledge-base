package me.asu.kb.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import org.mozilla.universalchardet.UniversalDetector;

/** Writes already-authorized text paths by replacing them with a complete temporary file. */
public final class AtomicTextFileWriter {

  private static final byte[] UTF_8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
  private static final byte[] UTF_16_LE_BOM = {(byte) 0xFF, (byte) 0xFE};
  private static final byte[] UTF_16_BE_BOM = {(byte) 0xFE, (byte) 0xFF};
  private static final byte[] UTF_32_LE_BOM = {(byte)0xFF, (byte) 0xFE, 0x00, 0x00};
  private static final byte[] UTF_32_BE_BOM = {0x00, 0x00, (byte) 0xFE, (byte) 0xFF};

  /**
   * Saves text using the existing file's charset and line-ending style when possible.
   *
   * <p>The path must already have passed the caller's workspace/path boundary checks. This class
   * deliberately performs no authorization or application-level persistence.
   */
  public WriteResult write(Path path, String content) throws IOException {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(content, "content");
    Path target = path.toAbsolutePath().normalize();
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("target path has no parent: " + target);
    }

    ExistingFormat format = Files.exists(target) ? ExistingFormat.detect(target) : ExistingFormat.defaults();
    String preservedContent = format.lineEnding().normalize(content);
    byte[] contentBytes = preservedContent.getBytes(format.charset());
    byte[] bytes = prependBom(contentBytes, format.bom());
    Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
    boolean moved = false;
    try {
      try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE)) {
        channel.write(ByteBuffer.wrap(bytes));
        channel.force(true);
      }
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IOException("atomic replacement is not supported for " + target, exception);
      }
      moved = true;
      return new WriteResult(target, format.charset(), format.lineEnding().value(), bytes.length);
    } finally {
      if (!moved) {
        Files.deleteIfExists(temporary);
      }
    }
  }

  private static byte[] prependBom(byte[] content, byte[] bom) {
    if (bom.length == 0) {
      return content;
    }
    byte[] result = new byte[bom.length + content.length];
    System.arraycopy(bom, 0, result, 0, bom.length);
    System.arraycopy(content, 0, result, bom.length, content.length);
    return result;
  }

  public record WriteResult(Path path, Charset charset, String lineEnding, long byteCount) {
    public WriteResult {
      Objects.requireNonNull(path, "path");
      Objects.requireNonNull(charset, "charset");
      Objects.requireNonNull(lineEnding, "lineEnding");
    }
  }

  private record ExistingFormat(Charset charset, byte[] bom, LineEnding lineEnding) {

    private static ExistingFormat defaults() {
      return new ExistingFormat(StandardCharsets.UTF_8, new byte[0], LineEnding.LF);
    }

    private static ExistingFormat detect(Path path) throws IOException {
      byte[] bytes = Files.readAllBytes(path);
      Bom bom = Bom.detect(bytes);
      Charset charset = bom.charset();
      if (charset == null && bytes.length > 0) {
        String detected = UniversalDetector.detectCharset(path.toFile());
        if (detected != null) {
          charset = Charset.forName(detected);
        }
      }
      if (charset == null) {
        charset = StandardCharsets.UTF_8;
      }
      String existingText = new String(bytes, bom.length(), bytes.length - bom.length(), charset);
      return new ExistingFormat(charset, bom.bytes(), LineEnding.detect(existingText));
    }
  }

  private enum LineEnding {
    LF("\n"),
    CRLF("\r\n"),
    CR("\r");

    private final String value;

    LineEnding(String value) {
      this.value = value;
    }

    private String value() {
      return value;
    }

    private String normalize(String text) {
      return text.replaceAll("\\r\\n|\\r|\\n", value);
    }

    private static LineEnding detect(String text) {
      for (int i = 0; i < text.length(); i++) {
        if (text.charAt(i) == '\r') {
          return i + 1 < text.length() && text.charAt(i + 1) == '\n' ? CRLF : CR;
        }
        if (text.charAt(i) == '\n') {
          return LF;
        }
      }
      return LF;
    }
  }

  private record Bom(Charset charset, byte[] bytes, int length) {

    private static Bom detect(byte[] bytes) {
      if (startsWith(bytes, UTF_32_LE_BOM)) {
        return new Bom(Charset.forName("UTF-32LE"), UTF_32_LE_BOM, UTF_32_LE_BOM.length);
      }
      if (startsWith(bytes, UTF_32_BE_BOM)) {
        return new Bom(Charset.forName("UTF-32BE"), UTF_32_BE_BOM, UTF_32_BE_BOM.length);
      }
      if (startsWith(bytes, UTF_8_BOM)) {
        return new Bom(StandardCharsets.UTF_8, UTF_8_BOM, UTF_8_BOM.length);
      }
      if (startsWith(bytes, UTF_16_LE_BOM)) {
        return new Bom(StandardCharsets.UTF_16LE, UTF_16_LE_BOM, UTF_16_LE_BOM.length);
      }
      if (startsWith(bytes, UTF_16_BE_BOM)) {
        return new Bom(StandardCharsets.UTF_16BE, UTF_16_BE_BOM, UTF_16_BE_BOM.length);
      }
      return new Bom(null, new byte[0], 0);
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
      if (source.length < prefix.length) {
        return false;
      }
      for (int i = 0; i < prefix.length; i++) {
        if (source[i] != prefix[i]) {
          return false;
        }
      }
      return true;
    }
  }
}