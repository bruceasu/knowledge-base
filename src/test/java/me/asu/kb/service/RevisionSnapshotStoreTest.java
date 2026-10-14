package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RevisionSnapshotStoreTest {

  @TempDir Path tempDir;

  @Test
  void writesAndReadsImmutableHistorySnapshot() throws Exception {
    RevisionSnapshotStore store = new RevisionSnapshotStore(tempDir);
    ArticleId articleId = new ArticleId(4);
    ArticleRevision revision =
        new ArticleRevision(1, "hash", "markdown", "/root/note.md", ArticleSourceKind.ROOT, Instant.EPOCH);

    Path snapshot = store.write(articleId, revision, "first\nversion");

    assertEquals(tempDir.resolve(".kb/history/4/1.md"), snapshot);
    assertEquals("first\nversion", store.read(articleId, 1));
    assertEquals(1, store.list(articleId).size());
    assertThrows(java.nio.file.FileAlreadyExistsException.class,
        () -> store.write(articleId, revision, "replacement"));
  }

  @Test
  void historyDirectoryIsNotCreatedUntilFirstSnapshot() throws Exception {
    RevisionSnapshotStore store = new RevisionSnapshotStore(tempDir);

    assertEquals(0, store.list(new ArticleId(9)).size());
    assertEquals(false, Files.exists(tempDir.resolve(".kb/history")));
  }
}
