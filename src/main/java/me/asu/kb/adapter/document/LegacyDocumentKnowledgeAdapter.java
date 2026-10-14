package me.asu.kb.adapter.document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.ingest.Chunk;
import me.asu.kb.service.Article;
import me.asu.kb.service.ArticleMetadata;
import me.asu.kb.service.ArticleRevision;

/**
 * Maps a read-only legacy document snapshot to the storage-independent engineering knowledge core.
 *
 * <p>This class is intentionally not registered with the existing ingestion, persistence, search,
 * HTTP, or CLI paths.
 */
public final class LegacyDocumentKnowledgeAdapter {

  private static final String ADAPTER_GENERATOR_ID = "legacy-document-knowledge-adapter";
  private static final String ADAPTER_GENERATOR_VERSION = "1";
  private static final Pattern ATX_HEADING =
      Pattern.compile("^[ \\t]{0,3}(#{1,6})[ \\t]+(.+?)[ \\t]*$");

  /** Returns one in-memory projection and all deterministic diagnostics observed during adaptation. */
  public DocumentKnowledgeProjection adapt(LegacyDocumentSnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");

    List<AdapterDiagnostic> diagnostics = new ArrayList<>();
    Optional<SourceRef> source = sourceRef(snapshot.sourceProvenance(), diagnostics);
    if (source.isEmpty()) {
      return new DocumentKnowledgeProjection(
          Optional.empty(), Optional.empty(), List.of(), List.of(), diagnostics);
    }

    Article article = snapshot.article();
    ArticleRevision revision = article.currentRevision();
    String documentId = documentId(snapshot.stableSourceId());
    String title = documentTitle(article.metadata(), diagnostics);
    ObjectType documentType = documentType(snapshot, diagnostics);
    List<String> lines = splitLines(snapshot.revisionContent());
    KnowledgeObject document =
        KnowledgeObject.canonical(
            documentId,
            documentType,
            title,
            content(snapshot.revisionContent()),
            source.orElseThrow(),
            documentMetadata(article, revision),
            revision.contentHash());

    List<ParsedSection> parsedSections = parseSections(lines, diagnostics);
    List<DocumentSection> sections = projectSections(document, source.orElseThrow(), parsedSections);
    List<DerivedChunkProjection> chunks =
        projectChunks(snapshot, document, source.orElseThrow(), lines.size(), sections, diagnostics);

    return new DocumentKnowledgeProjection(
        Optional.of(document),
        lines.isEmpty() ? Optional.empty() : Optional.of(new SourceRange(1, lines.size())),
        sections,
        chunks,
        diagnostics);
  }

  private Optional<SourceRef> sourceRef(
      Optional<LegacySourceProvenance> provenance, List<AdapterDiagnostic> diagnostics) {
    if (provenance.isEmpty()) {
      diagnostics.add(AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.MISSING_SOURCE_SYSTEM));
      diagnostics.add(AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.MISSING_SOURCE_LOCATOR));
      return Optional.empty();
    }

    LegacySourceProvenance value = provenance.orElseThrow();
    if (value.system().isEmpty()) {
      diagnostics.add(AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.MISSING_SOURCE_SYSTEM));
    }
    if (value.locator().isEmpty()) {
      diagnostics.add(AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.MISSING_SOURCE_LOCATOR));
    }
    if (value.system().isEmpty() || value.locator().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new SourceRef(value.system().orElseThrow(), value.locator().orElseThrow(), value.revision()));
  }

  private String documentTitle(ArticleMetadata metadata, List<AdapterDiagnostic> diagnostics) {
    return metadata
        .title()
        .filter(title -> !title.isBlank())
        .orElseGet(
            () -> {
              diagnostics.add(
                  AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.MISSING_DOCUMENT_TITLE));
              return "Untitled document";
            });
  }

  private ObjectType documentType(
      LegacyDocumentSnapshot snapshot, List<AdapterDiagnostic> diagnostics) {
    Optional<OpenSpecArtifactKind> fromPath = classificationFromPath(snapshot.repositoryRelativePath());
    if (snapshot.explicitOpenSpecArtifactKind().isPresent()
        && fromPath.isPresent()
        && snapshot.explicitOpenSpecArtifactKind().get() != fromPath.get()) {
      diagnostics.add(
          AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.ARTIFACT_CLASSIFICATION_CONFLICT));
    }
    return snapshot
        .explicitOpenSpecArtifactKind()
        .or(() -> fromPath)
        .map(this::objectType)
        .orElse(ObjectType.DOCUMENT);
  }

  private Optional<OpenSpecArtifactKind> classificationFromPath(Optional<String> repositoryRelativePath) {
    if (repositoryRelativePath.isEmpty()) {
      return Optional.empty();
    }
    String path = repositoryRelativePath.orElseThrow().replace('\\', '/').strip();
    while (path.startsWith("./")) {
      path = path.substring(2);
    }
    if (path.matches("openspec/(?:[^/]+/)*specs/[^/]+/spec\\.md")) {
      return Optional.of(OpenSpecArtifactKind.REQUIREMENT);
    }
    if (path.matches("openspec/(?:[^/]+/)*design\\.md")) {
      return Optional.of(OpenSpecArtifactKind.DESIGN);
    }
    if (path.matches("openspec/(?:[^/]+/)*\\.openspec\\.yaml")) {
      return Optional.of(OpenSpecArtifactKind.CONFIGURATION);
    }
    return Optional.empty();
  }

  private ObjectType objectType(OpenSpecArtifactKind kind) {
    return switch (kind) {
      case REQUIREMENT -> ObjectType.REQUIREMENT;
      case DESIGN -> ObjectType.DESIGN;
      case CONFIGURATION -> ObjectType.CONFIGURATION;
    };
  }

  private Map<String, String> documentMetadata(Article article, ArticleRevision revision) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("legacy.article-id", Long.toString(article.id().value()));
    metadata.put("legacy.revision-number", Long.toString(revision.revisionNumber()));
    metadata.put("legacy.revision-format", revision.format());
    metadata.put("legacy.source-path", revision.sourcePath());
    metadata.put("legacy.source-kind", revision.sourceKind().name());
    metadata.put("legacy.revision-created-at", revision.createdAt().toString());
    metadata.put("adapter.content-hash-scope", "legacy-source-bytes");

    ArticleMetadata articleMetadata = article.metadata();
    articleMetadata.summary().filter(value -> !value.isBlank()).ifPresent(value -> metadata.put("legacy.summary", value));
    copyList(metadata, "legacy.keyword", articleMetadata.keywords());
    copyList(metadata, "legacy.tag", articleMetadata.tags());
    return metadata;
  }

  private void copyList(Map<String, String> metadata, String prefix, List<String> values) {
    for (int index = 0; index < values.size(); index++) {
      String value = values.get(index);
      if (value != null && !value.isBlank()) {
        metadata.put(prefix + "." + index, value);
      }
    }
  }

  private List<ParsedSection> parseSections(
      List<String> lines, List<AdapterDiagnostic> diagnostics) {
    List<ParsedSection> sections = new ArrayList<>();
    Deque<ParsedSection> stack = new ArrayDeque<>();
    Map<String, Integer> rootOccurrences = new HashMap<>();
    Fence openFence = null;
    int fenceStartLine = 0;

    for (int index = 0; index < lines.size(); index++) {
      int lineNumber = index + 1;
      String line = lines.get(index);
      String trimmed = line.stripLeading();

      if (openFence != null) {
        if (isFenceClosing(trimmed, openFence)) {
          openFence = null;
        }
        continue;
      }

      Optional<Fence> openingFence = fenceOpening(trimmed);
      if (openingFence.isPresent()) {
        openFence = openingFence.orElseThrow();
        fenceStartLine = lineNumber;
        continue;
      }

      Optional<Heading> heading = heading(line);
      if (heading.isPresent()) {
        Heading value = heading.orElseThrow();
        while (!stack.isEmpty() && stack.peekLast().level >= value.level) {
          stack.removeLast().endLine = lineNumber - 1;
        }

        ParsedSection parent = stack.peekLast();
        int expectedNextLevel = parent == null ? 1 : parent.level + 1;
        if (value.level > expectedNextLevel) {
          diagnostics.add(AdapterDiagnostic.at(AdapterDiagnosticCode.SKIPPED_HEADING_LEVEL, lineNumber));
        }

        Map<String, Integer> occurrences = parent == null ? rootOccurrences : parent.childOccurrences;
        int occurrence = occurrences.merge(normalizeTitle(value.title), 1, Integer::sum);
        List<PathSegment> path = new ArrayList<>();
        if (parent != null) {
          path.addAll(parent.identityPath);
        }
        path.add(new PathSegment(value.title, normalizeTitle(value.title), occurrence));

        ParsedSection section =
            new ParsedSection(value.title, value.level, lineNumber, parent, path, occurrence);
        sections.add(section);
        stack.addLast(section);
        continue;
      }

      if (isSetextUnderline(lines, index) || isLikelyUnsupportedAtx(trimmed)) {
        diagnostics.add(AdapterDiagnostic.at(AdapterDiagnosticCode.UNSUPPORTED_HEADING_SYNTAX, lineNumber));
      }
    }

    if (openFence != null) {
      diagnostics.add(AdapterDiagnostic.at(AdapterDiagnosticCode.UNCLOSED_FENCE, fenceStartLine));
    }
    while (!stack.isEmpty()) {
      stack.removeLast().endLine = lines.size();
    }
    return sections;
  }

  private Optional<Heading> heading(String line) {
    Matcher matcher = ATX_HEADING.matcher(line);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    String title = matcher.group(2).replaceFirst("[ \\t]+#+[ \\t]*$", "").strip();
    if (title.isBlank()) {
      return Optional.empty();
    }
    return Optional.of(new Heading(matcher.group(1).length(), title));
  }

  private boolean isSetextUnderline(List<String> lines, int index) {
    if (index == 0 || lines.get(index - 1).isBlank()) {
      return false;
    }
    return lines.get(index).matches("^[=\\-]{3,}[ \\t]*$");
  }

  private boolean isLikelyUnsupportedAtx(String trimmed) {
    return trimmed.startsWith("#");
  }

  private Optional<Fence> fenceOpening(String trimmed) {
    if (trimmed.length() < 3) {
      return Optional.empty();
    }
    char marker = trimmed.charAt(0);
    if (marker != '`' && marker != '~') {
      return Optional.empty();
    }
    int length = repeatedPrefixLength(trimmed, marker);
    return length >= 3 ? Optional.of(new Fence(marker, length)) : Optional.empty();
  }

  private boolean isFenceClosing(String trimmed, Fence fence) {
    return !trimmed.isEmpty()
        && trimmed.charAt(0) == fence.marker
        && repeatedPrefixLength(trimmed, fence.marker) >= fence.length;
  }

  private int repeatedPrefixLength(String value, char marker) {
    int length = 0;
    while (length < value.length() && value.charAt(length) == marker) {
      length++;
    }
    return length;
  }

  private List<DocumentSection> projectSections(
      KnowledgeObject document, SourceRef source, List<ParsedSection> parsedSections) {
    List<DocumentSection> sections = new ArrayList<>();
    Map<ParsedSection, String> identifiers = new HashMap<>();
    for (ParsedSection parsed : parsedSections) {
      String sectionId = sectionId(document.id(), parsed.identityPath);
      identifiers.put(parsed, sectionId);
      String parentId =
          parsed.parent == null ? document.id() : identifiers.get(parsed.parent);
      SourceRange range = new SourceRange(parsed.startLine, parsed.endLine);
      List<String> titlePath = parsed.identityPath.stream().map(PathSegment::title).toList();
      String sectionContent = contentSlice(parsed, document.content().orElse(""));
      KnowledgeObject object =
          KnowledgeObject.canonical(
              sectionId,
              ObjectType.SECTION,
              parsed.title,
              content(sectionContent),
              source,
              sectionMetadata(parsed, range),
              textHash(sectionContent));
      sections.add(new DocumentSection(object, parentId, parsed.level, titlePath, range));
    }
    return sections;
  }

  private String contentSlice(ParsedSection section, String documentContent) {
    List<String> lines = splitLines(documentContent);
    if (section.startLine > lines.size()) {
      return "";
    }
    int endIndex = Math.min(section.endLine, lines.size());
    return String.join("\n", lines.subList(section.startLine - 1, endIndex));
  }

  private Map<String, String> sectionMetadata(ParsedSection section, SourceRange range) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("adapter.content-hash-scope", "utf8-text");
    metadata.put("heading.level", Integer.toString(section.level));
    metadata.put("heading.path", section.identityPath.stream().map(PathSegment::title).reduce((a, b) -> a + " / " + b).orElseThrow());
    metadata.put("heading.occurrence", Integer.toString(section.occurrence));
    metadata.put("source.start-line", Integer.toString(range.startLine()));
    metadata.put("source.end-line", Integer.toString(range.endLine()));
    return metadata;
  }

  private List<DerivedChunkProjection> projectChunks(
      LegacyDocumentSnapshot snapshot,
      KnowledgeObject document,
      SourceRef source,
      int documentLineCount,
      List<DocumentSection> sections,
      List<AdapterDiagnostic> diagnostics) {
    List<DerivedChunkProjection> chunks = new ArrayList<>();
    String inputContentHash = snapshot.article().currentRevision().contentHash();
    for (Chunk legacyChunk : snapshot.chunks()) {
      Optional<SourceRange> range = chunkRange(legacyChunk, documentLineCount, diagnostics);
      Optional<DocumentSection> section = deepestContainingSection(range, sections);
      String canonicalSourceId = section.map(value -> value.object().id()).orElse(document.id());
      String chunkContent = legacyChunk.content();
      if (chunkContent == null) {
        diagnostics.add(AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.MISSING_CHUNK_CONTENT));
        chunkContent = "";
      }
      if (snapshot.legacyChunkGenerator().isEmpty()) {
        diagnostics.add(
            new AdapterDiagnostic(AdapterDiagnosticCode.LEGACY_CHUNK_GENERATOR_UNKNOWN, range));
      }

      String chunkTitle = legacyChunk.title();
      if (chunkTitle == null || chunkTitle.isBlank()) {
        chunkTitle = document.title() + " chunk " + legacyChunk.chunkNo();
      }
      KnowledgeObject object =
          KnowledgeObject.derived(
              chunkId(document.id(), inputContentHash, legacyChunk, range, chunkContent),
              ObjectType.CHUNK,
              chunkTitle,
              content(chunkContent),
              source,
              chunkMetadata(legacyChunk, range, snapshot.legacyChunkGenerator()),
              textHash(chunkContent),
              canonicalSourceId,
              ADAPTER_GENERATOR_ID,
              ADAPTER_GENERATOR_VERSION,
              inputContentHash);
      chunks.add(
          new DerivedChunkProjection(
              object, section.map(value -> value.object().id()), range, legacyChunk.chunkNo()));
    }
    return chunks;
  }

  private Optional<SourceRange> chunkRange(
      Chunk chunk, int documentLineCount, List<AdapterDiagnostic> diagnostics) {
    if (chunk.startLine() <= 0
        || chunk.endLine() < chunk.startLine()
        || chunk.endLine() > documentLineCount) {
      diagnostics.add(AdapterDiagnostic.withoutLocation(AdapterDiagnosticCode.INVALID_CHUNK_RANGE));
      return Optional.empty();
    }
    return Optional.of(new SourceRange(chunk.startLine(), chunk.endLine()));
  }

  private Optional<DocumentSection> deepestContainingSection(
      Optional<SourceRange> range, List<DocumentSection> sections) {
    if (range.isEmpty()) {
      return Optional.empty();
    }
    SourceRange chunkRange = range.orElseThrow();
    DocumentSection match = null;
    for (DocumentSection candidate : sections) {
      SourceRange sectionRange = candidate.sourceRange();
      if (sectionRange.startLine() <= chunkRange.startLine()
          && sectionRange.endLine() >= chunkRange.endLine()
          && (match == null || candidate.headingLevel() > match.headingLevel())) {
        match = candidate;
      }
    }
    return Optional.ofNullable(match);
  }

  private Map<String, String> chunkMetadata(
      Chunk chunk,
      Optional<SourceRange> range,
      Optional<LegacyGeneratorProvenance> legacyGenerator) {
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("adapter.content-hash-scope", "utf8-text");
    metadata.put("legacy.chunk-number", Integer.toString(chunk.chunkNo()));
    range.ifPresent(
        value -> {
          metadata.put("source.start-line", Integer.toString(value.startLine()));
          metadata.put("source.end-line", Integer.toString(value.endLine()));
        });
    legacyGenerator.ifPresent(
        value -> {
          metadata.put("legacy.generator-id", value.id());
          metadata.put("legacy.generator-version", value.version());
        });
    return metadata;
  }

  private String documentId(String stableSourceId) {
    return "document:v1:sha256:" + sha256(stableSourceId);
  }

  private String sectionId(String documentId, List<PathSegment> path) {
    String identityPath =
        path.stream()
            .map(segment -> segment.normalizedTitle + "#" + segment.occurrence)
            .reduce((left, right) -> left + "\u001f" + right)
            .orElseThrow();
    return "section:v1:sha256:" + sha256(documentId + "\u001f" + identityPath);
  }

  private String chunkId(
      String documentId,
      String inputContentHash,
      Chunk chunk,
      Optional<SourceRange> range,
      String chunkContent) {
    String rangeToken =
        range.map(value -> value.startLine() + ":" + value.endLine()).orElse("unlocated");
    return "chunk:v1:sha256:"
        + sha256(
            documentId
                + "\u001f"
                + inputContentHash
                + "\u001f"
                + chunk.chunkNo()
                + "\u001f"
                + rangeToken
                + "\u001f"
                + textHash(chunkContent));
  }

  private String normalizeTitle(String title) {
    return Normalizer.normalize(title, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT)
        .replaceAll("\\s+", " ")
        .strip();
  }

  private Optional<String> content(String value) {
    return value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  private List<String> splitLines(String value) {
    return value.isEmpty() ? List.of() : List.of(value.split("\\R", -1));
  }

  private String textHash(String value) {
    return "sha256:utf8:" + sha256(value);
  }

  private String sha256(String value) {
    try {
      byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hash = new StringBuilder(bytes.length * 2);
      for (byte valueByte : bytes) {
        hash.append(String.format("%02x", Byte.toUnsignedInt(valueByte)));
      }
      return hash.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }

  private record Heading(int level, String title) {}

  private record Fence(char marker, int length) {}

  private record PathSegment(String title, String normalizedTitle, int occurrence) {}

  private static final class ParsedSection {
    private final String title;
    private final int level;
    private final int startLine;
    private final ParsedSection parent;
    private final List<PathSegment> identityPath;
    private final int occurrence;
    private final Map<String, Integer> childOccurrences = new HashMap<>();
    private int endLine;

    private ParsedSection(
        String title,
        int level,
        int startLine,
        ParsedSection parent,
        List<PathSegment> identityPath,
        int occurrence) {
      this.title = title;
      this.level = level;
      this.startLine = startLine;
      this.parent = parent;
      this.identityPath = List.copyOf(identityPath);
      this.occurrence = occurrence;
      this.endLine = startLine;
    }
  }
}
