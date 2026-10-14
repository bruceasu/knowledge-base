package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrashFileStoreTest {

  @TempDir Path tempDir;

  @Test
  void movesSourceAndHistoryAndRestoresWithoutOverwriting() throws Exception {
    Path source = tempDir.resolve("root/note.md");
    Path history = tempDir.resolve(".kb/history/7");
    Files.createDirectories(source.getParent());
    Files.createDirectories(history);
    Files.writeString(source, "current");
    Files.writeString(history.resolve("1.md"), "old");
    Instant deletedAt = Instant.parse("2026-08-29T00:00:00Z");
    TrashEntry entry = new TrashFileStore(tempDir).move(new ArticleId(7), source, history, deletedAt);

    assertEquals(false, Files.exists(source));
    assertEquals(false, Files.exists(history));
    Path target = tempDir.resolve("root/restored.md");
    new TrashFileStore(tempDir).restore(entry, target);
    assertEquals("current", Files.readString(target));
    assertThrows(java.io.IOException.class, () -> new TrashFileStore(tempDir).restore(entry, target));
  }
}
