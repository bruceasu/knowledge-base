package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicTextFileWriterTest {

  @TempDir Path tempDir;

  @Test
  void preservesExistingCharsetAndLineEndings() throws Exception {
    Path file = tempDir.resolve("note.txt");
    Charset shiftJis = Charset.forName("Shift_JIS");
    Files.write(file, "一行目\r\n二行目\r\n".getBytes(shiftJis));

    AtomicTextFileWriter.WriteResult result =
        new AtomicTextFileWriter().write(file, "更新一行\n更新二行\n");

    byte[] saved = Files.readAllBytes(file);
    assertEquals(shiftJis, result.charset());
    assertEquals("\r\n", result.lineEnding());
    assertEquals("更新一行\r\n更新二行\r\n", new String(saved, shiftJis));
    assertFalse(new String(saved, StandardCharsets.ISO_8859_1).contains("\n\n"));
  }

  @Test
  void preservesUtf8Bom() throws Exception {
    Path file = tempDir.resolve("bom.md");
    Files.write(file, new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', '\n'});

    new AtomicTextFileWriter().write(file, "b\n");

    byte[] saved = Files.readAllBytes(file);
    assertTrue(saved.length >= 3);
    assertEquals((byte) 0xEF, saved[0]);
    assertEquals((byte) 0xBB, saved[1]);
    assertEquals((byte) 0xBF, saved[2]);
    assertEquals("b\n", new String(saved, 3, saved.length - 3, StandardCharsets.UTF_8));
  }

  @Test
  void createsNewFilesAsUtf8WithLf() throws Exception {
    Path file = tempDir.resolve("new.md");

    AtomicTextFileWriter.WriteResult result =
        new AtomicTextFileWriter().write(file, "标题\r\n正文");

    assertEquals(StandardCharsets.UTF_8, result.charset());
    assertEquals("\n", result.lineEnding());
    assertEquals("标题\n正文", Files.readString(file, StandardCharsets.UTF_8));
  }

  @Test
  void failedWriteDoesNotLeaveTemporaryFile() throws Exception {
    Path file = tempDir.resolve("target");
    Files.createDirectory(file);

    try {
      new AtomicTextFileWriter().write(file, "content");
    } catch (java.io.IOException expected) {
      // The target is a directory; the original filesystem state must remain intact.
    }

    try (var files = Files.list(tempDir)) {
      assertEquals(1, files.count());
    }
  }
}
