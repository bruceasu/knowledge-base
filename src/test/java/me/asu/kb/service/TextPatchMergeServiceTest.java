package me.asu.kb.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TextPatchMergeServiceTest {

  private final TextPatchMergeService service = new TextPatchMergeService();

  @Test
  void unchangedServerAcceptsSubmittedContent() {
    TextPatchMergeResult result = service.merge("one\ntwo", "one\ntwo", "one\nchanged");

    assertEquals(TextPatchMergeResult.Status.MERGED, result.status());
    assertEquals("one\nchanged", result.mergedText());
    assertTrue(result.conflicts().isEmpty());
  }

  @Test
  void mergesIndependentChangesFromServerAndSubmittedVersions() {
    TextPatchMergeResult result =
        service.merge(
            "one\ntwo\nthree\nfour",
            "one\nserver\nthree\nfour",
            "one\ntwo\nclient\nfour");

    assertEquals(TextPatchMergeResult.Status.MERGED, result.status());
    assertEquals("one\nserver\nclient\nfour", result.mergedText());
  }

  @Test
  void mergesInsertionsAtDifferentPositions() {
    TextPatchMergeResult result =
        service.merge("one\nthree", "one\nserver\nthree", "client\none\nthree");

    assertEquals(TextPatchMergeResult.Status.MERGED, result.status());
    assertEquals("client\none\nserver\nthree", result.mergedText());
  }

  @Test
  void rejectsDifferentInsertionsAtTheSamePosition() {
    TextPatchMergeResult result =
        service.merge("one\nthree", "one\nserver\nthree", "one\nclient\nthree");

    assertEquals(TextPatchMergeResult.Status.CONFLICT, result.status());
    assertEquals(1, result.conflicts().size());
  }

  @Test
  void rejectsOverlappingChangesAndReturnsAllVersions() {
    TextPatchMergeResult result =
        service.merge("one\ntwo", "one\nserver", "one\nclient");

    assertEquals(TextPatchMergeResult.Status.CONFLICT, result.status());
    assertFalse(result.conflicts().isEmpty());
    TextPatchMergeResult.Conflict conflict = result.conflicts().get(0);
    assertEquals("one\ntwo", conflict.baseText());
    assertEquals("one\nserver", conflict.serverText());
    assertEquals("one\nclient", conflict.submittedText());
  }

  @Test
  void identicalChangesAreAppliedOnce() {
    TextPatchMergeResult result = service.merge("one\ntwo", "one\nupdated", "one\nupdated");

    assertEquals(TextPatchMergeResult.Status.MERGED, result.status());
    assertEquals("one\nupdated", result.mergedText());
  }
}
