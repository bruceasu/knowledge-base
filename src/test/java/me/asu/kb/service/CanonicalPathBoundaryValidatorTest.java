package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CanonicalPathBoundaryValidatorTest {

  private static final Path TEST_BASE = Path.of(System.getProperty("java.io.tmpdir"))
      .toAbsolutePath().normalize();
  private static final Path WORKSPACE = TEST_BASE.resolve("kb-workspace");
  private static final Path ROOT = TEST_BASE.resolve("registered-knowledge");

  private final CanonicalPathBoundaryValidator validator =
      new CanonicalPathBoundaryValidator(WORKSPACE, List.of(ROOT));

  @Test
  void acceptsFilesUnderRegisteredRoot() {
    assertEquals(ROOT.resolve("notes/guide.md"), validator.validate(ROOT.resolve("notes/guide.md")));
  }

  @Test
  void acceptsFilesUnderManagedArticlesAndUploads() {
    assertEquals(WORKSPACE.resolve("articles/2026/08/new.md"),
        validator.validate(WORKSPACE.resolve("articles/2026/08/new.md")));
    assertEquals(WORKSPACE.resolve("uploads/source.txt"),
        validator.validate(WORKSPACE.resolve("uploads/source.txt")));
  }

  @Test
  void rejectsPathTraversalEvenWhenNormalizedPathWouldBeAllowed() {
    Path traversing = ROOT.resolve("notes").resolve("..").resolve("guide.md");

    assertThrows(IllegalArgumentException.class, () -> validator.validate(traversing));
  }

  @Test
  void rejectsHistoryAndTrashDirectories() {
    assertThrows(IllegalArgumentException.class,
        () -> validator.validate(WORKSPACE.resolve(".kb/history/article/revision.md")));
    assertThrows(IllegalArgumentException.class,
        () -> validator.validate(WORKSPACE.resolve(".kb/trash/article/current.md")));
  }

  @Test
  void rejectsPathsOutsideAllBoundaries() {
    assertThrows(IllegalArgumentException.class,
        () -> validator.validate(TEST_BASE.resolve("other/file.md")));
  }

  @Test
  void rejectsRelativeCandidatesAndRoots() {
    assertThrows(IllegalArgumentException.class,
        () -> validator.validate(Path.of("articles/new.md")));
    assertThrows(IllegalArgumentException.class,
        () -> new CanonicalPathBoundaryValidator(WORKSPACE, List.of(Path.of("relative-root"))));
  }

  @Test
  void preservesPathSegmentBoundaries() {
    assertThrows(IllegalArgumentException.class,
        () -> validator.validate(TEST_BASE.resolve("registered-knowledge-base/guide.md")));
  }
}
