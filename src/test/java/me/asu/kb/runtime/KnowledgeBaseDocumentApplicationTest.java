package me.asu.kb.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import me.asu.kb.llm.EchoLlmClient;
import me.asu.kb.service.KnowledgeBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KnowledgeBaseDocumentApplicationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void executesDocumentAndSessionOperationsInsideOneFreshRoot() throws Exception {
    Path documentRoot = temporaryDirectory.resolve("document");
    try (KnowledgeBaseDocumentApplication application =
        new KnowledgeBaseDocumentApplication(
            temporaryDirectory, new KnowledgeBase(documentRoot, new EchoLlmClient()))) {
      Object created =
          application.execute(
              SingleWorkspaceDocumentApplication.Operation.CREATE_DOCUMENT,
              Map.of("filename", "note.md", "content", "hello"));
      assertTrue(created instanceof KnowledgeBase.DocumentView);

      @SuppressWarnings("unchecked")
      Map<String, Object> session =
          (Map<String, Object>)
              application.execute(
                  SingleWorkspaceDocumentApplication.Operation.CREATE_SESSION,
                  Map.of("title", "work"));
      assertEquals("session-create", session.get("operation"));

      Path source = temporaryDirectory.resolve("source.md");
      Files.writeString(source, "source");
      assertEquals(
          "ingest-file",
          ((Map<?, ?>)
                  application.execute(
                      SingleWorkspaceDocumentApplication.Operation.INGEST_FILE,
                      Map.of("path", source.toString())))
              .get("operation"));
    }
  }

  @Test
  void rejectsPathsOutsideTheServiceWorkspace() throws Exception {
    try (KnowledgeBaseDocumentApplication application =
        new KnowledgeBaseDocumentApplication(
            temporaryDirectory,
            new KnowledgeBase(temporaryDirectory.resolve("document"), new EchoLlmClient()))) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              application.execute(
                  SingleWorkspaceDocumentApplication.Operation.INGEST_FILE,
                  Map.of("path", temporaryDirectory.resolveSibling("outside.md").toString())));
    }
  }
}
