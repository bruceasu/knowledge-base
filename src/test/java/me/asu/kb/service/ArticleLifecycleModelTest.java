package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ArticleLifecycleModelTest {
  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

  @Test
  void articleUsesDocumentIdAndKeepsMetadataOutsideRevisionHistory() {
    ArticleId id = new ArticleId(7);
    ArticleRevision first = revision(1, "a");
    ArticleRevision second = revision(2, "b");
    RevisionHistory history = new RevisionHistory(id, List.of(first, second));

    Article article =
        new Article(
            id,
            history.current(),
            new ArticleMetadata(Optional.of("Title"), Optional.empty(), List.of("java"), List.of("kb")),
            ArticleState.ACTIVE,
            new ProjectionStatus(ProjectionState.PENDING, 2, Optional.empty(), NOW));

    assertEquals(7, article.id().value());
    assertEquals(second, history.current());
    assertEquals(List.of("java"), article.metadata().keywords());
  }

  @Test
  void historyRequiresAtLeastOneContiguousRevision() {
    ArticleId id = new ArticleId(7);
    assertThrows(IllegalArgumentException.class, () -> new RevisionHistory(id, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new RevisionHistory(id, List.of(revision(2, "a"))));
  }

  @Test
  void failedProjectionRequiresReasonAndTrashExpiryCannotPrecedeDeletion() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new ProjectionStatus(ProjectionState.FAILED, 1, Optional.empty(), NOW));
    assertThrows(
        IllegalArgumentException.class,
        () -> new TrashEntry(new ArticleId(1), "root/a.md", ".kb/trash/a.md", NOW, NOW.minusSeconds(1)));
  }

  private static ArticleRevision revision(long number, String hash) {
    return new ArticleRevision(number, hash, "markdown", "root/article.md", ArticleSourceKind.ROOT, NOW);
  }
}
