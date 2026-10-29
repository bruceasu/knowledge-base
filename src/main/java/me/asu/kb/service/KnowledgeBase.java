package me.asu.kb.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.mozilla.universalchardet.UniversalDetector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.Instant;
import me.asu.kb.ask.AskResponse;
import me.asu.kb.ask.SourceReference;
import me.asu.kb.embedding.EmbeddingClient;
import me.asu.kb.ingest.Chunk;
import me.asu.kb.ingest.OrgMetadataExtractor;
import me.asu.kb.ingest.HtmlMetadataExtractor;
import me.asu.kb.ingest.TextChunker;
import me.asu.kb.metadata.LlmMetadataService;
import me.asu.kb.llm.LlmClient;
import me.asu.kb.search.SearchHit;
import me.asu.kb.tag.TagService;
import me.asu.kb.session.SessionService;
import me.asu.kb.summary.LlmSummaryService;
import me.asu.kb.summary.SummaryService;

public final class KnowledgeBase implements AutoCloseable {

  private static final int DEFAULT_SESSION_CONTEXT_WINDOW = 8;

  private final Path workspaceDir;
  private final KnowledgeRepository repository;
  private final SessionService sessions;
  private final LuceneSearchIndex index;
  private final TextChunker chunker = new TextChunker();
  private final OrgMetadataExtractor orgMetadataExtractor = new OrgMetadataExtractor();
  private final HtmlMetadataExtractor htmlMetadataExtractor = new HtmlMetadataExtractor();
  private final LlmMetadataService llmMetadataService;
  private final TagService tagService = new TagService();
  private final SummaryService summaryService;
  private final LlmClient llmClient;
  private final EmbeddingClient embeddingClient;
  private PersonalCatalog personalCatalog;
  private PersonalKnowledgeService personalService;

  /** Initialize additive state before the composition begins accepting requests. */
  public void initializePersonalCatalog() throws Exception {
    if (personalCatalog != null) return;
    Path backupDirectory = workspaceDir.resolve(".kb").resolve("migration-backups");
    Files.createDirectories(backupDirectory);
    personalCatalog = repository.initializePersonalCatalog(backupDirectory.resolve(
        "before-personal-v1-" + java.util.UUID.randomUUID() + ".sqlite"));
  }

  public Optional<String> personalServiceId() {
    return personalCatalog == null ? Optional.empty() : Optional.of(personalCatalog.serviceId());
  }

  public void initializePersonalService(me.asu.kb.runtime.PersonalKnowledgeSettings settings) throws Exception {
    initializePersonalCatalog();
    personalService = new PersonalKnowledgeService(this, repository, personalCatalog, index, workspaceDir, settings);
  }

  public me.asu.kb.runtime.PersonalKnowledgeApplication personalApplication() {
    if (personalService == null) throw new me.asu.kb.runtime.PersonalKnowledgeApplication.Failure(503, "UNAVAILABLE");
    return personalService;
  }

  /** Uses deterministic managed paths and avoids implicit LLM/network calls on personal imports. */
  long ingestPersonal(Path path, String title) throws Exception {
    String root = path.getParent().toAbsolutePath().toString();
    repository.activateRoot(root);
    String content = Files.readString(path, StandardCharsets.UTF_8);
    String contentHash = sha256(content.getBytes(StandardCharsets.UTF_8));
    var existing = repository.findDocumentByPath(path.toAbsolutePath().toString());
    if (existing.isEmpty() || !existing.get().hash().equals(contentHash)) {
      ingestFile(root, path, content, contentHash, title, nextRevision(existing.orElse(null), contentHash, content), false);
    } else {
      reprojectDocument(existing.get().id());
    }
    return repository.findDocumentByPath(path.toAbsolutePath().toString()).orElseThrow().id();
  }

  public KnowledgeBase(Path workspaceDir, LlmClient llmClient) throws Exception {
    this(workspaceDir, llmClient, EmbeddingClient.disabled());
  }

  public KnowledgeBase(Path workspaceDir, LlmClient llmClient, EmbeddingClient embeddingClient)
      throws Exception {
    this(workspaceDir, llmClient, embeddingClient, LuceneSearchIndex::new);
  }

  KnowledgeBase(Path workspaceDir, LlmClient llmClient, EmbeddingClient embeddingClient,
      IndexFactory indexFactory) throws Exception {
    this.workspaceDir = workspaceDir.toAbsolutePath().normalize();
    Files.createDirectories(this.workspaceDir.resolve("data"));
    Files.createDirectories(this.workspaceDir.resolve("index"));
    this.repository = new KnowledgeRepository(this.workspaceDir.resolve("data/kb.sqlite"));
    this.sessions = new SessionService(repository);
    this.index = indexFactory.create(this.workspaceDir.resolve("index/lucene"));
    this.summaryService = new LlmSummaryService(llmClient);
    this.llmClient = llmClient;
    this.llmMetadataService = new LlmMetadataService(llmClient, llmClient.getClass().getSimpleName());
    this.embeddingClient = embeddingClient == null ? EmbeddingClient.disabled() : embeddingClient;
  }

  public SessionService sessions() {
    return sessions;
  }

  @FunctionalInterface
  interface IndexFactory {
    LuceneSearchIndex create(Path indexDir) throws IOException;
  }

  private <T> T read(ReadOperation<T> operation) throws Exception {
    return repository.inReadTransaction(ignored -> operation.execute());
  }

  private <T> T readSql(ReadOperation<T> operation) throws SQLException {
    try {
      return read(operation);
    } catch (SQLException error) {
      throw error;
    } catch (Exception error) {
      throw new SQLException("isolated read failed", error);
    }
  }

  @FunctionalInterface
  private interface ReadOperation<T> {
    T execute() throws Exception;
  }

  public Optional<String> checkpoint(String name) throws SQLException {
    return readSql(() -> repository.checkpoint(name));
  }

  public int pendingProjectionBatchCount() throws SQLException {
    return readSql(repository::pendingProjectionBatchCount);
  }

  public KnowledgeRepository.EffectiveDocumentMetadata documentMetadata(long documentId)
      throws SQLException {
    return readSql(() -> repository.findEffectiveDocumentMetadata(documentId));
  }

  public DocumentView document(long documentId) throws Exception {
    return read(() -> {
      KnowledgeRepository.DocumentRow document = repository.findDocumentById(documentId)
          .orElseThrow(() -> new SQLException("Document not found: " + documentId));
      Article article = repository.findArticleById(documentId)
          .orElseThrow(() -> new SQLException("Article not found: " + documentId));
      Path path = Path.of(document.path());
      if (!Files.isRegularFile(path)) {
        throw new IOException("Document source file is not available: " + path);
      }
      return new DocumentView(
          document.id(), document.path(), document.fileType(), document.hash(), decode(path),
          repository.findEffectiveDocumentMetadata(documentId), article.currentRevision().revisionNumber(),
          article.projection().state().name().toLowerCase(Locale.ROOT));
    });
  }

  /**
   * Saves editor content as a new current revision after checking the editor's
   * base hash. The physical write completes before projection is attempted.
   */
  public DocumentView saveDocument(
      long documentId,
      String submittedContent,
      String expectedBaseHash,
      String title,
      List<String> tags,
      List<String> keywords,
      String summary) throws Exception {
    if (submittedContent == null) {
      throw new IllegalArgumentException("content is required");
    }
    KnowledgeRepository.DocumentRow document = repository.findDocumentById(documentId)
        .orElseThrow(() -> new SQLException("Document not found: " + documentId));
    Path path = Path.of(document.path());
    List<Path> roots = repository.findRoots().stream().map(KnowledgeRepository.RootRow::path)
        .map(Path::of).toList();
    new CanonicalPathBoundaryValidator(workspaceDir, roots).validate(path);
    if (!Files.isRegularFile(path)) {
      throw new IOException("Document source file is not available: " + path);
    }

    String serverContent = decode(path);
    String serverHash = sha256(path);
    if (expectedBaseHash != null && !expectedBaseHash.isBlank()
        && !expectedBaseHash.equals(serverHash)) {
      Optional<String> baseContent = repository.findRevisionHistory(documentId).revisions().stream()
          .filter(revision -> expectedBaseHash.equals(revision.contentHash()))
          .findFirst()
          .flatMap(revision -> {
            try {
              return Optional.of(new RevisionSnapshotStore(workspaceDir)
                  .read(new ArticleId(documentId), revision.revisionNumber()));
            } catch (IOException ignored) {
              return Optional.empty();
            }
          });
      if (baseContent.isPresent()) {
        TextPatchMergeResult merge = new TextPatchMergeService()
            .merge(baseContent.orElseThrow(), serverContent, submittedContent);
        if (merge.status() == TextPatchMergeResult.Status.MERGED) {
          submittedContent = merge.mergedText();
        } else {
          TextPatchMergeResult.Conflict conflict = merge.conflicts().get(0);
          repository.recordEvent("document_conflict", documentId,
              "expected=" + expectedBaseHash + ", server=" + serverHash, Instant.now());
          throw new DocumentSaveConflictException(expectedBaseHash, serverHash,
              conflict.baseText(), conflict.serverText(), conflict.submittedText());
        }
      } else {
        repository.recordEvent("document_conflict", documentId,
            "base snapshot unavailable; expected=" + expectedBaseHash, Instant.now());
        throw new DocumentSaveConflictException(expectedBaseHash, serverHash,
            null, serverContent, submittedContent);
      }
    }

    Optional<Article> article = repository.findArticleById(documentId);
    if (article.isEmpty()) {
      throw new SQLException("Article not found: " + documentId);
    }
    Article current = article.orElseThrow();
    RevisionSnapshotStore snapshots = new RevisionSnapshotStore(workspaceDir);
    snapshots.writeIfAbsent(current.id(), current.currentRevision(), serverContent);
    new AtomicTextFileWriter().write(path, submittedContent);
    String savedHash = sha256(path);
    long revisionNumber = current.currentRevision().revisionNumber() + 1;
    ArticleRevision revision = new ArticleRevision(revisionNumber, savedHash,
        document.fileType(), document.path(), current.currentRevision().sourceKind(), Instant.now());
    try {
      snapshots.write(current.id(), revision, submittedContent);
      ingestFile(document.rootPath(), path, submittedContent, savedHash, path.getFileName().toString(),
          Optional.of(revision));
      if (title != null || summary != null || tags != null || keywords != null) {
        updateManualMetadata(documentId, title, tags, keywords, summary);
      }
      repository.recordEvent("document_saved", documentId, "revision=" + revisionNumber, Instant.now());
    } catch (Exception error) {
      throw new DocumentSaveRecoveryException(documentId, path, savedHash, error);
    }
    return document(documentId);
  }

  public DocumentView createDocument(String filename, String content, String title,
      List<String> tags, List<String> keywords, String summary) throws Exception {
    if (filename == null || filename.isBlank() || filename.contains("\\") || filename.contains("/")) {
      throw new IllegalArgumentException("filename must be a simple text filename");
    }
    if (content == null) throw new IllegalArgumentException("content is required");
    String safeName = filename.matches(".*\\.[A-Za-z0-9]{1,12}$") ? filename : filename + ".md";
    Path directory = workspaceDir.resolve("articles").resolve(java.time.LocalDate.now().toString());
    Files.createDirectories(directory);
    Path path = directory.resolve(safeName);
    int suffix = 1;
    while (Files.exists(path)) {
      int dot = safeName.lastIndexOf('.');
      String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
      String extension = dot > 0 ? safeName.substring(dot) : "";
      path = directory.resolve(stem + "-" + suffix++ + extension);
    }
    new AtomicTextFileWriter().write(path, content);
    ingest(path, path.getFileName().toString());
    long documentId = repository.findDocumentByPath(path.toString())
        .orElseThrow(() -> new SQLException("created document was not indexed")).id();
    if (title != null || summary != null || tags != null || keywords != null) {
      updateManualMetadata(documentId, title, tags, keywords, summary);
    }
    return document(documentId);
  }

  public static final class DocumentSaveConflictException extends IOException {
    private final String expectedBaseHash;
    private final String serverHash;
    private final String base;
    private final String server;
    private final String submitted;

    public DocumentSaveConflictException(String expectedBaseHash, String serverHash,
        String base, String server, String submitted) {
      super("document changed since it was opened");
      this.expectedBaseHash = expectedBaseHash;
      this.serverHash = serverHash;
      this.base = base;
      this.server = server;
      this.submitted = submitted;
    }

    public String expectedBaseHash() { return expectedBaseHash; }
    public String serverHash() { return serverHash; }
    public String base() { return base; }
    public String server() { return server; }
    public String submitted() { return submitted; }
  }

  /** The source file is durable; retrying sync is the explicit recovery path. */
  public static final class DocumentSaveRecoveryException extends IOException {
    private final long documentId;
    private final Path sourcePath;
    private final String savedHash;

    public DocumentSaveRecoveryException(long documentId, Path sourcePath, String savedHash,
        Exception cause) {
      super("source file was saved but business update did not finish; run sync to recover", cause);
      this.documentId = documentId;
      this.sourcePath = sourcePath;
      this.savedHash = savedHash;
    }

    public long documentId() { return documentId; }
    public Path sourcePath() { return sourcePath; }
    public String savedHash() { return savedHash; }
  }

  public List<DocumentInfo> documents() throws SQLException {
    return readSql(() -> repository.findAllDocuments().stream()
        .map(document -> new DocumentInfo(document.id(), document.path(), document.fileType()))
        .toList());
  }

  public DocumentPage documents(int limit, int offset, String query) throws SQLException {
    KnowledgeRepository.DocumentPage page = repository.findDocumentPage(limit, offset, query);
    return new DocumentPage(page.documents().stream()
        .map(document -> new DocumentInfo(document.id(), document.path(), document.fileType()))
        .toList(), page.total(), limit, offset);
  }

  public RevisionHistory revisionHistory(long documentId) throws SQLException {
    return readSql(() -> {
      repository.findDocumentById(documentId)
          .orElseThrow(() -> new SQLException("Document not found: " + documentId));
      return repository.findRevisionHistory(documentId);
    });
  }

  public TrashEntry deleteDocument(long documentId) throws Exception {
    KnowledgeRepository.DocumentRow document = repository.findDocumentById(documentId)
        .orElseThrow(() -> new SQLException("Document not found: " + documentId));
    Article article = repository.findArticleById(documentId)
        .orElseThrow(() -> new SQLException("Article not found: " + documentId));
    if (personalService != null) personalService.markDeleted(documentId, document.path());
    Path source = Path.of(document.path());
    Path history = workspaceDir.resolve(".kb").resolve("history").resolve(Long.toString(documentId));
    Instant deletedAt = Instant.now();
    TrashEntry entry = new TrashFileStore(workspaceDir).move(article.id(), source, history, deletedAt);
    repository.insertTrashEntry(entry);
    repository.deleteDocumentProjectionData(documentId);
    repository.markArticleDeleted(article.id(), deletedAt);
    index.deletePath(document.path());
    index.commit();
    repository.recordEvent("document_deleted", documentId, entry.trashPath(), deletedAt);
    return entry;
  }

  public List<TrashEntry> trashEntries() throws SQLException {
    return readSql(repository::findTrashEntries);
  }

  /** Performs retention cleanup; callers may invoke this from a scheduler or maintenance command. */
  public CleanupResult cleanupRetention(Instant now) throws Exception {
    Instant revisionCutoff = now.minusSeconds(3L * 365 * 24 * 60 * 60);
    Instant trashCutoff = now;
    int revisionsRemoved = 0;
    int trashRemoved = 0;
    List<String> failures = new ArrayList<>();
    for (TrashEntry entry : repository.findTrashEntries()) {
      if (!entry.expiresAt().isAfter(trashCutoff)) {
        try {
          new TrashFileStore(workspaceDir).purge(entry);
          repository.deleteTrashEntry(entry.articleId());
          repository.deleteDocumentPermanently(entry.articleId().value());
          trashRemoved++;
        } catch (Exception error) {
          failures.add("trash " + entry.articleId().value() + ": " + error.getMessage());
        }
      }
    }
    for (KnowledgeRepository.DocumentRow document : repository.findAllDocuments()) {
      Optional<Article> article = repository.findArticleById(document.id());
      if (article.isEmpty()) {
        continue;
      }
      RevisionHistory history = repository.findRevisionHistory(document.id());
      for (ArticleRevision revision : history.revisions()) {
        if (revision.revisionNumber() != history.current().revisionNumber()
            && revision.createdAt().isBefore(revisionCutoff)) {
          try {
            new RevisionSnapshotStore(workspaceDir).delete(article.orElseThrow().id(), revision);
            repository.deleteArticleRevision(document.id(), revision.revisionNumber());
            revisionsRemoved++;
          } catch (Exception error) {
            failures.add("revision " + document.id() + "/" + revision.revisionNumber()
                + ": " + error.getMessage());
          }
        }
      }
    }
    return new CleanupResult(revisionsRemoved, trashRemoved, failures);
  }

  public record CleanupResult(int revisionsRemoved, int trashRemoved, List<String> failures) {
    public CleanupResult {
      failures = List.copyOf(failures);
    }
  }

  public List<KnowledgeRepository.WorkspaceEventRow> recentEvents(int limit) throws SQLException {
    return readSql(() -> repository.recentEvents(limit));
  }

  public DocumentView restoreDocument(long documentId) throws Exception {
    TrashEntry entry = repository.findTrashEntries().stream()
        .filter(candidate -> candidate.articleId().value() == documentId)
        .findFirst()
        .orElseThrow(() -> new SQLException("Trash entry not found: " + documentId));
    Path target = Path.of(entry.originalPath());
    TrashFileStore store = new TrashFileStore(workspaceDir);
    Path history = workspaceDir.resolve(".kb").resolve("history").resolve(Long.toString(documentId));
    store.restore(entry, target);
    store.restoreHistory(entry, new ArticleId(documentId));
    String content = decode(target);
    String hash = sha256(target);
    Article article = repository.findArticleById(documentId)
        .orElseThrow(() -> new SQLException("Article not found: " + documentId));
    long revisionNumber = article.currentRevision().revisionNumber() + 1;
    ArticleRevision revision = new ArticleRevision(revisionNumber, hash,
        article.currentRevision().format(), target.toString(), article.currentRevision().sourceKind(), Instant.now());
    new RevisionSnapshotStore(workspaceDir).write(article.id(), revision, content);
    repository.restoreArticle(article.id(), Instant.now());
    repository.deleteTrashEntry(article.id());
    repository.insertArticleRevision(article.id(), revision);
    ingestFile(target.getParent().toString(), target, content, hash, target.getFileName().toString());
    repository.recordEvent("trash_restored", documentId, "newRevision=" + revisionNumber, Instant.now());
    if (personalService != null) personalService.markRestored(documentId);
    return document(documentId);
  }

  public DocumentView restoreRevision(long documentId, long revisionNumber) throws Exception {
    KnowledgeRepository.DocumentRow document = repository.findDocumentById(documentId)
        .orElseThrow(() -> new SQLException("Document not found: " + documentId));
    Article article = repository.findArticleById(documentId)
        .orElseThrow(() -> new SQLException("Article not found: " + documentId));
    ArticleRevision selected = repository.findRevisionHistory(documentId).revisions().stream()
        .filter(revision -> revision.revisionNumber() == revisionNumber)
        .findFirst()
        .orElseThrow(() -> new SQLException("Revision not found: " + revisionNumber));
    Path path = Path.of(document.path());
    String content;
    try {
      content = new RevisionSnapshotStore(workspaceDir).read(article.id(), revisionNumber);
    } catch (IOException missingSnapshot) {
      if (revisionNumber != article.currentRevision().revisionNumber()) throw missingSnapshot;
      content = decode(path);
    }
    if (Files.exists(path)) {
      new RevisionSnapshotStore(workspaceDir).writeIfAbsent(article.id(), article.currentRevision(), decode(path));
    }
    new AtomicTextFileWriter().write(path, content);
    String hash = sha256(path);
    long nextRevision = article.currentRevision().revisionNumber() + 1;
    ArticleRevision revision = new ArticleRevision(nextRevision, hash, selected.format(),
        selected.sourcePath(), selected.sourceKind(), Instant.now());
    new RevisionSnapshotStore(workspaceDir).write(article.id(), revision, content);
    repository.insertArticleRevision(article.id(), revision);
    ingestFile(document.rootPath(), path, content, hash, path.getFileName().toString());
    repository.recordEvent("revision_restored", documentId, "sourceRevision=" + revisionNumber, Instant.now());
    return document(documentId);
  }

  public void updateManualMetadata(
      long documentId, String title, List<String> tags, List<String> keywords, String summary)
      throws Exception {
    repository.findDocumentById(documentId)
        .orElseThrow(() -> new SQLException("Document not found: " + documentId));
    repository.deleteDocumentMetadataBySource(documentId, "manual");
    repository.deleteDocumentKeywordsBySource(documentId, "manual");
    repository.deleteTagsBySource(documentId, "manual");
    if (title != null && !title.isBlank()) {
      repository.upsertDocumentMetadata(documentId, "title", title.strip(), "manual", null);
    }
    if (summary != null && !summary.isBlank()) {
      repository.upsertDocumentMetadata(documentId, "summary", summary.strip(), "manual", null);
    }
    for (String tag : tags == null ? List.<String>of() : tags) {
      if (tag != null && !tag.isBlank()) {
        repository.upsertDocumentTag(documentId, tag.toLowerCase(Locale.ROOT).trim(), "manual");
      }
    }
    for (String keyword : keywords == null ? List.<String>of() : keywords) {
      if (keyword != null && !keyword.isBlank()) {
        repository.upsertDocumentKeyword(documentId, keyword.toLowerCase(Locale.ROOT).trim(), "manual");
      }
    }
    reprojectDocument(documentId);
  }

  void personalMetadata(long documentId, String title, List<String> tags) throws Exception {
    repository.inTransaction(() -> {
      repository.deleteDocumentMetadataBySource(documentId, "manual");
      repository.deleteTagsBySource(documentId, "manual");
      repository.upsertDocumentMetadata(documentId, "title", title, "manual", null);
      for (String tag : tags) repository.upsertDocumentTag(documentId, tag, "manual");
      Article article = repository.findArticleById(documentId).orElseThrow();
      repository.updateArticleProjectionStatus(article.id(), new ProjectionStatus(
          ProjectionState.PENDING, article.currentRevision().revisionNumber(), Optional.empty(), Instant.now()));
      return null;
    });
  }

  public void reprojectDocument(long documentId) throws Exception {
    KnowledgeRepository.DocumentRow document = repository.findDocumentById(documentId)
        .orElseThrow(() -> new SQLException("Document not found: " + documentId));
    index.deletePath(document.path());
    KnowledgeRepository.EffectiveDocumentMetadata metadata = repository.findEffectiveDocumentMetadata(documentId);
    for (KnowledgeRepository.ChunkRow chunk : repository.findChunksByDocumentId(documentId)) {
      index.addChunk(document, chunk, metadata);
    }
    index.commit();
    repository.findArticleByDocumentId(documentId).ifPresent(article -> {
      try {
        repository.updateArticleProjectionStatus(article.id(), new ProjectionStatus(
            ProjectionState.INDEXED, article.currentRevision().revisionNumber(),
            Optional.empty(), Instant.now()));
      } catch (SQLException error) {
        throw new RuntimeException(error);
      }
    });
  }

  public Optional<KnowledgeRepository.DocumentUploadRow> uploadByContentHash(String contentHash)
      throws SQLException {
    return readSql(() -> repository.findUploadByContentHash(contentHash));
  }

  public void registerUploadIdentity(
      String contentHash,
      Path storedPath,
      String originalFilename,
      String relativePath,
      String batchId)
      throws SQLException {
    KnowledgeRepository.DocumentRow document =
        repository.findDocumentByPath(storedPath.toAbsolutePath().normalize().toString())
            .orElseThrow(() -> new SQLException("uploaded document was not indexed"));
    String storagePath = workspaceDir.relativize(storedPath.toAbsolutePath().normalize()).toString();
    repository.insertUploadIdentity(
        contentHash,
        document.id(),
        storagePath,
        originalFilename,
        relativePath,
        batchId,
        Instant.now().toString());
  }

  public void ingestUpload(Path storedPath, String originalFilename, String contentHash) throws Exception {
    Path uploadRoot = workspaceDir.resolve("uploads").toAbsolutePath().normalize();
    repository.activateRoot(uploadRoot.toString());
    Path normalizedPath = storedPath.toAbsolutePath().normalize();
    ingestFile(uploadRoot.toString(), normalizedPath, decode(normalizedPath), contentHash, originalFilename);
  }

  public void sync(List<Path> roots) throws Exception {
    if (roots == null || roots.isEmpty()) {
      throw new IllegalArgumentException("at least one root directory is required");
    }
    for (Path root : roots) {
      sync(root);
    }
  }

  public void sync(Path root) throws Exception {
    resumePendingProjectionBatches();
    Path normalizedRoot = root.toAbsolutePath().normalize();
    repository.activateRoot(normalizedRoot.toString());
    Set<String> seen = new HashSet<>();

    for (Path path : scan(normalizedRoot)) {
      String normalizedPath = path.toAbsolutePath().normalize().toString();
      seen.add(normalizedPath);
      String hash = sha256(path);
      var existing = repository.findDocumentByPath(normalizedPath);
      if (existing.isPresent() && existing.get().hash().equals(hash)) {
        continue;
      }
      byte[] bytes = Files.readAllBytes(path);
      String preparedHash = sha256(bytes);
      String text = decode(bytes);
      ingestFile(normalizedRoot.toString(), path, text, preparedHash, null,
          nextRevision(existing.orElse(null), preparedHash, text));
    }

    for (KnowledgeRepository.DocumentRow document : repository.findDocumentsByRoot(normalizedRoot.toString())) {
      if (!seen.contains(document.path())) {
        repository.deleteDocument(document.id());
        projectBusinessBatch(List.of(document.path()));
      }
    }
  }

  public void ingest(Path file) throws Exception {
    ingest(file, file.getFileName() == null ? null : file.getFileName().toString());
  }

  public void ingest(Path file, String displayName) throws Exception {
    Path normalized = file.toAbsolutePath().normalize();
    byte[] bytes = Files.readAllBytes(normalized);
    String text = decode(bytes);
    String hash = sha256(bytes);
    String rootPath = normalized.getParent() == null ? normalized.toString() : normalized.getParent().toString();
    resumePendingProjectionBatches();
    repository.activateRoot(rootPath);
    ingestFile(rootPath, normalized, text, hash, displayName);
  }

  public List<SearchHit> search(String query, int limit) throws Exception {
    return search(query, limit, null);
  }

  public List<SearchHit> search(String query, int limit, String pathPrefix) throws Exception {
    return search(query, limit, pathPrefix, List.of());
  }

  public List<SearchHit> search(String query, int limit, String pathPrefix, List<String> tagFilters)
      throws Exception {
    List<SearchHit> hits = index.search(query, limit, pathPrefix, normalizeTagFilters(tagFilters));
    Map<Long, List<String>> tagsByDocumentId = new LinkedHashMap<>();
    List<SearchHit> enriched = new ArrayList<>();
    for (SearchHit hit : hits) {
      if (!repository.isDocumentInActiveRoot(hit.documentId())) {
        continue;
      }
      List<String> tags = tagsByDocumentId.computeIfAbsent(hit.documentId(), this::documentTagsForDocumentId);
      enriched.add(
          new SearchHit(
              hit.documentId(),
              hit.chunkId(),
              hit.path(),
              hit.title(),
              hit.chunkNo(),
              hit.snippet(),
              hit.summary(),
              hit.score(),
              tags));
    }
    return enriched;
  }

  private List<String> normalizeTagFilters(List<String> tagFilters) {
    if (tagFilters == null) {
      return List.of();
    }
    return tagFilters.stream()
        .flatMap(value -> java.util.Arrays.stream(value == null ? new String[0] : value.split(",")))
        .map(value -> value.toLowerCase(Locale.ROOT).trim())
        .filter(value -> !value.isBlank())
        .distinct()
        .toList();
  }

  public AskResponse ask(String question, int topK) throws Exception {
    return ask(null, question, topK);
  }

  public AskResponse ask(Long sessionId, String question, int topK) throws Exception {
    List<SearchHit> hits = search(question, topK);
    List<SourceReference> sources = new ArrayList<>();
    for (SearchHit hit : hits) {
      sources.add(new SourceReference(
          hit.documentId(), hit.path(), hit.title(), hit.chunkNo(), hit.snippet(), hit.tags()));
    }

    Long effectiveSessionId = sessionId;
    String sessionSummary = null;
    List<KnowledgeRepository.SessionMessageRow> recentMessages = List.of();
    if (effectiveSessionId != null) {
      Optional<KnowledgeRepository.SessionRow> session = sessions.findById(effectiveSessionId);
      if (session.isPresent()) {
        sessionSummary = sessions.latestSummary(effectiveSessionId).orElse(null);
        recentMessages = sessions.recentMessages(effectiveSessionId, DEFAULT_SESSION_CONTEXT_WINDOW);
      } else {
        effectiveSessionId = null;
      }
    }

    String prompt = buildPrompt(question, sessionSummary, recentMessages, hits);
    String answer = hits.isEmpty()
        ? "在本地知识库中未找到足够依据。"
        : llmClient.generate(prompt);
    return new AskResponse(effectiveSessionId, answer, sources, prompt);
  }

  public void rebuild() throws Exception {
    try {
      index.clear();
      List<KnowledgeRepository.DocumentRow> documents = repository.findAllDocuments();
      Map<Long, KnowledgeRepository.DocumentRow> documentsById = documents.stream()
          .collect(Collectors.toMap(KnowledgeRepository.DocumentRow::id, d -> d));
      Map<Long, KnowledgeRepository.EffectiveDocumentMetadata> metadataByDocumentId = new LinkedHashMap<>();
      for (KnowledgeRepository.ChunkRow chunkRow : repository.findAllChunks()) {
        KnowledgeRepository.DocumentRow document = documentsById.get(chunkRow.documentId());
        if (document == null) {
          continue;
        }
        KnowledgeRepository.EffectiveDocumentMetadata metadata = metadataByDocumentId.computeIfAbsent(
            document.id(), id -> {
              try {
                refreshDocumentTags(document.id(), document.fileType(), document.path());
                return repository.findEffectiveDocumentMetadata(id);
              } catch (SQLException error) {
                throw new IllegalStateException(error);
              }
            });
        maybeStoreEmbedding(chunkRow.id(), chunkRow.content());
        index.addChunk(document, chunkRow, metadata);
      }
      index.commit();
      String businessCheckpoint = repository.checkpoint("business").orElseGet(this::checkpointValue);
      repository.markAllPendingProjectionBatchesProjected(businessCheckpoint);
      checkpointProjection(businessCheckpoint);
    } catch (Exception error) {
      index.discardUncommittedChanges();
      throw error;
    }
  }

  public List<KnowledgeRepository.RootRow> roots() throws SQLException {
    return readSql(repository::findRoots);
  }

  public void removeRoot(Path root, RootRemovalMode mode, boolean confirmed) throws Exception {
    Path normalizedRoot = root.toAbsolutePath().normalize();
    if (mode == RootRemovalMode.PURGE && !confirmed) {
      throw new IllegalArgumentException("purge requires confirm=true");
    }
    if (mode == RootRemovalMode.DETACH) {
      repository.setRootLifecycle(normalizedRoot.toString(), "detached", null);
      return;
    }
    repository.setRootLifecycle(normalizedRoot.toString(), "purging", null);
    List<String> failures = new ArrayList<>();
    for (KnowledgeRepository.DocumentRow document : repository.findDocumentsByRoot(normalizedRoot.toString())) {
      try {
        deleteDocument(document.id());
      } catch (Exception error) {
        failures.add(document.path() + ": " + error.getMessage());
      }
    }
    if (failures.isEmpty()) {
      repository.setRootLifecycle(normalizedRoot.toString(), "purged", null);
    } else {
      String reason = String.join("; ", failures);
      repository.setRootLifecycle(normalizedRoot.toString(), "purge_failed", reason);
      throw new IOException("root purge partially failed; retry is available: " + reason);
    }
  }

  public enum RootRemovalMode {
    DETACH,
    PURGE
  }

  private void ingestFile(String rootPath, Path path, String text, String hash, String displayName)
      throws Exception {
    ingestFile(rootPath, path, text, hash, displayName, Optional.empty());
  }

  private Optional<ArticleRevision> nextRevision(KnowledgeRepository.DocumentRow existing,
      String contentHash, String content) throws Exception {
    if (existing == null) {
      return Optional.empty();
    }
    Article article = repository.findArticleByDocumentId(existing.id()).orElse(null);
    if (article == null || article.currentRevision().contentHash().equals(contentHash)) {
      return Optional.empty();
    }
    ArticleRevision revision = new ArticleRevision(article.currentRevision().revisionNumber() + 1,
        contentHash, existing.fileType(), existing.path(), article.currentRevision().sourceKind(), Instant.now());
    new RevisionSnapshotStore(workspaceDir).writeIfAbsent(article.id(), revision, content);
    return Optional.of(revision);
  }

  private void ingestFile(String rootPath, Path path, String text, String hash, String displayName,
      Optional<ArticleRevision> newRevision)
      throws Exception {
    ingestFile(rootPath, path, text, hash, displayName, newRevision, true);
  }

  private void ingestFile(String rootPath, Path path, String text, String hash, String displayName,
      Optional<ArticleRevision> newRevision, boolean generateMetadata) throws Exception {
    String fileType = fileType(displayName != null ? displayName : path.getFileName().toString());
    String normalizedPath = path.toAbsolutePath().normalize().toString();
    String displayTitle = displayName != null ? displayName : path.getFileName().toString();
    // LLM calls and chunking are intentionally outside the SQLite transaction.
    String summary = generateMetadata ? summaryService.summarizeDocument(displayTitle, text) : "";
    List<Chunk> chunks = chunker.split(text, chunkFormatFor(fileType));
    Optional<LlmMetadataService.AiMetadata> aiMetadata = generateMetadata ? llmMetadataService.generate(displayTitle, text) : Optional.empty();
    IngestCommit committed = repository.inTransaction(() -> {
      long documentId = repository.upsertDocument(rootPath, normalizedPath, fileType, StandardCharsets.UTF_8.name(), hash);
      if (newRevision.isPresent()) {
        repository.insertArticleRevision(new ArticleId(documentId), newRevision.orElseThrow());
      }
      repository.deleteDocumentMetadataBySource(documentId, "source");
      repository.deleteDocumentKeywordsBySource(documentId, "source");
      repository.deleteTagsBySource(documentId, "source");
      refreshDocumentTags(documentId, fileType, normalizedPath);
      if ("org".equals(fileType)) {
        applyOrgMetadata(documentId, text);
      } else if ("html".equals(fileType)) {
        applyHtmlMetadata(documentId, text);
      }
      repository.upsertDocumentSummary(documentId, summary, summaryService.getClass().getSimpleName());
      List<ChunkEmbedding> embeddings = new ArrayList<>();
      for (Chunk chunk : chunks) {
        long chunkId = repository.insertChunk(documentId, chunk.chunkNo(), chunk.title(), chunk.content(),
            chunk.startLine(), chunk.endLine());
        embeddings.add(new ChunkEmbedding(chunkId, chunk.content()));
      }
      applyAiMetadata(documentId, aiMetadata);
      String businessCheckpoint = checkpointValue();
      repository.putCheckpoint("business", businessCheckpoint);
      long batchId = repository.createProjectionBatch(businessCheckpoint, List.of(normalizedPath));
      return new IngestCommit(new KnowledgeRepository.ProjectionBatchRow(
          batchId, businessCheckpoint, checkpointValue(), List.of(normalizedPath)), embeddings);
    });
    for (ChunkEmbedding embedding : committed.embeddings()) {
      if (generateMetadata) maybeStoreEmbedding(embedding.chunkId(), embedding.content());
    }
    projectBatch(committed.batch());
  }

  public record DocumentInfo(long id, String path, String fileType) {}

  public record DocumentPage(List<DocumentInfo> documents, int total, int limit, int offset) {}

  public record DocumentView(
      long id,
      String path,
      String fileType,
      String contentHash,
      String content,
      KnowledgeRepository.EffectiveDocumentMetadata metadata,
      long currentRevision,
      String projectionState) {}

  private void applyAiMetadata(long documentId, Optional<LlmMetadataService.AiMetadata> generated) throws SQLException {
    if (generated.isEmpty()) {
      return;
    }
    repository.deleteDocumentMetadataBySource(documentId, "ai");
    repository.deleteDocumentKeywordsBySource(documentId, "ai");
    repository.deleteTagsBySource(documentId, "ai");
    LlmMetadataService.AiMetadata metadata = generated.orElseThrow();
    if (metadata.title().isPresent()) {
      upsertAiMetadata(documentId, "title", metadata.title().orElseThrow(), metadata.generatorModel());
    }
    if (metadata.summary().isPresent()) {
      upsertAiMetadata(documentId, "summary", metadata.summary().orElseThrow(), metadata.generatorModel());
    }
    for (String tag : metadata.tags()) {
      repository.upsertDocumentTag(documentId, tag, "ai");
    }
    for (String keyword : metadata.keywords()) {
      repository.upsertDocumentKeyword(documentId, keyword, "ai");
    }
  }

  private void upsertAiMetadata(long documentId, String field, String value, String model)
      throws SQLException {
    repository.upsertDocumentMetadata(documentId, field, value, "ai", model);
  }

  private void applyOrgMetadata(long documentId, String text) throws SQLException {
    repository.deleteDocumentMetadataBySource(documentId, "source");
    repository.deleteTagsBySource(documentId, "source");
    OrgMetadataExtractor.OrgMetadata metadata = orgMetadataExtractor.extract(text);
    if (metadata.title().isPresent()) {
      repository.upsertDocumentMetadata(documentId, "title", metadata.title().orElseThrow(), "source", null);
    }
    for (String tag : metadata.tags()) {
      repository.upsertDocumentTag(documentId, tag, "source");
    }
  }

  private void applyHtmlMetadata(long documentId, String text) throws SQLException {
    repository.deleteDocumentMetadataBySource(documentId, "source");
    repository.deleteDocumentKeywordsBySource(documentId, "source");
    repository.deleteTagsBySource(documentId, "source");
    HtmlMetadataExtractor.HtmlMetadata metadata = htmlMetadataExtractor.extract(text);
    if (metadata.title().isPresent()) {
      repository.upsertDocumentMetadata(documentId, "title", metadata.title().orElseThrow(), "source", null);
    }
    if (metadata.description().isPresent()) {
      repository.upsertDocumentMetadata(documentId, "summary", metadata.description().orElseThrow(), "source", null);
    }
    for (String keyword : metadata.keywords()) {
      repository.upsertDocumentKeyword(documentId, keyword, "source");
    }
    for (String tag : metadata.tags()) {
      repository.upsertDocumentTag(documentId, tag, "source");
    }
  }

  private void resumePendingProjectionBatches() throws Exception {
    for (KnowledgeRepository.ProjectionBatchRow batch : repository.findPendingProjectionBatches()) {
      projectBatch(batch);
    }
  }

  private void projectBusinessBatch(List<String> paths) throws Exception {
    String businessCheckpoint = checkpointBusiness();
    long batchId = repository.createProjectionBatch(businessCheckpoint, paths);
    projectBatch(new KnowledgeRepository.ProjectionBatchRow(batchId, businessCheckpoint, checkpointValue(), paths));
  }

  private record ChunkEmbedding(long chunkId, String content) {}

  private record IngestCommit(KnowledgeRepository.ProjectionBatchRow batch, List<ChunkEmbedding> embeddings) {}

  private void projectBatch(KnowledgeRepository.ProjectionBatchRow batch) throws Exception {
    List<Article> indexedArticles = new ArrayList<>();
    try {
      for (String path : batch.paths()) {
        index.deletePath(path);
        Optional<KnowledgeRepository.DocumentRow> document = repository.findDocumentByPath(path);
        if (document.isPresent()) {
          KnowledgeRepository.EffectiveDocumentMetadata metadata =
              repository.findEffectiveDocumentMetadata(document.get().id());
          for (KnowledgeRepository.ChunkRow chunk : repository.findChunksByDocumentId(document.get().id())) {
            index.addChunk(document.get(), chunk, metadata);
          }
          repository.findArticleByDocumentId(document.get().id()).ifPresent(indexedArticles::add);
        }
      }
      index.commit();
    } catch (Exception error) {
      index.discardUncommittedChanges();
      throw error;
    }
    for (Article article : indexedArticles) {
      repository.updateArticleProjectionStatus(article.id(), new ProjectionStatus(
          ProjectionState.INDEXED, article.currentRevision().revisionNumber(),
          Optional.empty(), Instant.now()));
    }
    repository.markProjectionBatchProjected(batch.id(), batch.businessCheckpoint());
    checkpointProjection(batch.businessCheckpoint());
  }

  private String checkpointBusiness() throws SQLException {
    String checkpoint = checkpointValue();
    repository.putCheckpoint("business", checkpoint);
    return checkpoint;
  }

  private void checkpointProjection(String checkpoint) throws SQLException {
    repository.putCheckpoint("projection", checkpoint);
  }

  private String checkpointValue() {
    return Instant.now().toString();
  }

  private List<Path> scan(Path root) throws IOException {
    List<Path> files = new ArrayList<>();
    if (Files.isRegularFile(root)) {
      if (accept(root)) {
        files.add(root);
      }
      return files;
    }
    try (var stream = Files.walk(root)) {
      stream.filter(Files::isRegularFile).filter(this::accept).forEach(files::add);
    }
    return files;
  }

  private boolean accept(Path path) {
    Path systemRoot = workspaceDir.resolve(".kb").toAbsolutePath().normalize();
    if (path.toAbsolutePath().normalize().startsWith(systemRoot)) {
      return false;
    }
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    if (name.startsWith(".")) {
      return false;
    }
    // todo: add more file types if needed, e.g., PDF, DOCX, etc. For now, we only
    // accept text-based files.
    // some file do not have extensions, so we may need to detect file type by
    // content in the future.
    return name.endsWith(".txt")
        || name.endsWith(".md")
        || name.endsWith(".markdown")
        || name.endsWith(".html")
        || name.endsWith(".htm")
        || name.endsWith(".org")
        || name.endsWith(".java")
        || name.endsWith(".py")
        || name.endsWith(".js")
        || name.endsWith("changelog")
        || name.endsWith("readme")
        || name.endsWith(".ts");
  }

  private String decode(Path path) throws IOException {
    return decode(Files.readAllBytes(path));
  }

  String decode(byte[] bytes) {

    // UTF-8 BOM: EF BB BF
    if (bytes.length >= 3
        && (bytes[0] & 0xFF) == 0xEF
        && (bytes[1] & 0xFF) == 0xBB
        && (bytes[2] & 0xFF) == 0xBF) {
      return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
    }

    
    // UTF-32LE BOM: FF FE 00 00
    if (bytes.length >= 4
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xFE
        && (bytes[2] & 0xFF) == 0x00
        && (bytes[3] & 0xFF) == 0x00) {
      return new String(bytes, 4, bytes.length - 4, Charset.forName("UTF-32LE"));
    }

    // UTF-32BE BOM: 00 00 FE FF
    if (bytes.length >= 4
        && (bytes[0] & 0xFF) == 0x00
        && (bytes[1] & 0xFF) == 0x00
        && (bytes[2] & 0xFF) == 0xFE
        && (bytes[3] & 0xFF) == 0xFF) {
      return new String(bytes, 4, bytes.length - 4, Charset.forName("UTF-32BE"));
    }

    // UTF-16LE BOM: FF FE
    if (bytes.length >= 2
        && (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xFE) {
      return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
    }

    // UTF-16BE BOM: FE FF
    if (bytes.length >= 2
        && (bytes[0] & 0xFF) == 0xFE
        && (bytes[1] & 0xFF) == 0xFF) {
      return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
    }


    String detectedCharset = null;
    try {
      UniversalDetector detector = new UniversalDetector(null);
      detector.handleData(bytes, 0, bytes.length);
      detector.dataEnd();
      detectedCharset = detector.getDetectedCharset();
    } catch (Exception ignored) {
    }

    if (detectedCharset != null) {
      try {
        return new String(bytes, Charset.forName(detectedCharset));
      } catch (Exception ignored) {
      }
    }

    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .decode(java.nio.ByteBuffer.wrap(bytes))
          .toString();
    } catch (Exception ignored) {
      return new String(bytes, Charset.forName("GB18030"));
    }
  }

  private String sha256(Path path) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      try (InputStream input = Files.newInputStream(path)) {
        for (int read; (read = input.read(buffer)) != -1;) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }

  private String sha256(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(bytes);
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private String fileType(String nameValue) {
    String name = nameValue.toLowerCase(Locale.ROOT);
    if (name.endsWith(".md") || name.endsWith(".markdown")) {
      return "markdown";
    }
    if (name.endsWith(".html") || name.endsWith(".htm")) {
      return "html";
    }
    if (name.endsWith(".org")) {
      return "org";
    }
    if (name.endsWith(".java")) {
      return "java";
    }
    if (name.endsWith(".py")) {
      return "python";
    }
    if (name.endsWith(".js")) {
      return "javascript";
    }
    return "txt";
  }

  private TextChunker.Format chunkFormatFor(String fileType) {
    if (fileType == null) {
      return TextChunker.Format.PLAIN;
    }
    return switch (fileType.toLowerCase(Locale.ROOT)) {
      case "markdown" -> TextChunker.Format.MARKDOWN;
      case "html" -> TextChunker.Format.HTML;
      case "org" -> TextChunker.Format.ORG;
      default -> TextChunker.Format.PLAIN;
    };
  }

  private void maybeStoreEmbedding(long chunkId, String content) {
    if (!embeddingClient.enabled()) {
      return;
    }
    try {
      var vector = embeddingClient.embed(content);
      repository.upsertChunkEmbedding(
          chunkId, vector.model(), toJson(vector.values()), vector.values().length);
    } catch (RuntimeException | SQLException e) {
      // Keep Lucene available even when the semantic layer is unavailable.
    }
  }

  private String toJson(float[] values) {
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(Float.toString(values[i]));
    }
    sb.append(']');
    return sb.toString();
  }

  private List<String> documentTagsForDocumentId(long documentId) {
    try {
      return repository.findTagsByDocumentId(documentId).stream()
          .map(KnowledgeRepository.DocumentTagRow::tag)
          .toList();
    } catch (SQLException e) {
      return List.of();
    }
  }

  private void refreshDocumentTags(long documentId, String fileType, String path) throws SQLException {
    repository.deleteTagsBySource(documentId, "system");
    repository.deleteTagsBySource(documentId, "path");
    for (String tag : tagService.generateSystemTags(fileType, path)) {
      repository.upsertDocumentTag(documentId, tag, "system");
    }
    for (String tag : tagService.generatePathTags(path)) {
      repository.upsertDocumentTag(documentId, tag, "path");
    }
  }

  private String buildPrompt(
      String question,
      String sessionSummary,
      List<KnowledgeRepository.SessionMessageRow> recentMessages,
      List<SearchHit> hits) {
    StringBuilder sb = new StringBuilder();
    sb.append("You are a local knowledge-base assistant.\n");
    sb.append("Answer strictly from the provided evidence. If evidence is insufficient, say so.\n\n");

    if (sessionSummary != null && !sessionSummary.isBlank()) {
      sb.append("[Session memory]\n");
      sb.append(sessionSummary).append("\n\n");
    }

    if (recentMessages != null && !recentMessages.isEmpty()) {
      sb.append("[Recent messages]\n");
      for (KnowledgeRepository.SessionMessageRow message : recentMessages) {
        sb.append(message.role()).append(": ").append(message.content()).append('\n');
      }
      sb.append('\n');
    }

    sb.append("[Evidence]\n");
    for (SearchHit hit : hits) {
      sb.append("Path: ").append(hit.path()).append('\n');
      sb.append("Title: ").append(hit.title() == null ? "" : hit.title()).append('\n');
      if (hit.tags() != null && !hit.tags().isEmpty()) {
        sb.append("Tags: ").append(String.join(", ", hit.tags())).append('\n');
      }
      if (hit.summary() != null && !hit.summary().isBlank()) {
        sb.append("Summary: ").append(hit.summary()).append('\n');
      }
      sb.append(hit.snippet()).append("\n\n");
    }
    sb.append("[Question]\n");
    sb.append(question).append('\n');
    return sb.toString();
  }

  @Override
  public void close() throws Exception {
    if (personalService != null) personalService.close();
    index.close();
    repository.close();
  }
}
