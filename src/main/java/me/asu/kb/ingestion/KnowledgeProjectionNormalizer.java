package me.asu.kb.ingestion;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.adapter.document.DocumentKnowledgeProjection;
import me.asu.kb.adapter.document.LegacyDocumentKnowledgeAdapter;
import me.asu.kb.adapter.gitevidence.GitCommitEvidence;
import me.asu.kb.adapter.gitevidence.GitFileChange;
import me.asu.kb.adapter.symbol.ExtractedSymbol;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionWrite;

/** Maps accepted source units into core objects without inferring semantic relations. */
final class KnowledgeProjectionNormalizer {
  private static final String JAVA_SOURCE_PROFILE = "java-source-file-v1";
  private static final String GIT_COMMIT_PROFILE = "git-commit-evidence-v1";

  private final LegacyDocumentKnowledgeAdapter documentAdapter;

  KnowledgeProjectionNormalizer() {
    this(new LegacyDocumentKnowledgeAdapter());
  }

  KnowledgeProjectionNormalizer(LegacyDocumentKnowledgeAdapter documentAdapter) {
    this.documentAdapter = documentAdapter;
  }

  NormalizedProjection normalize(ValidatedIngestionUnit validated) {
    if (validated.sourceUnit() instanceof DocumentIngestionUnit document) {
      return normalizeDocument(validated, document);
    }
    if (validated.sourceUnit() instanceof JavaSymbolIngestionUnit symbols) {
      return normalizeSymbols(validated, symbols);
    }
    if (validated.sourceUnit() instanceof GitCommitIngestionUnit git) {
      return normalizeGit(validated, git);
    }
    throw new IllegalArgumentException("unsupported source unit cannot be normalized");
  }

  private NormalizedProjection normalizeDocument(
      ValidatedIngestionUnit validated, DocumentIngestionUnit unit) {
    DocumentKnowledgeProjection projection = documentAdapter.adapt(unit.snapshot());
    KnowledgeObject document = projection.document().orElseThrow(
        () -> new IllegalArgumentException("document Adapter did not produce a canonical object"));
    List<ObjectWrite> writes = new ArrayList<>();
    writes.add(ObjectWrite.canonical(document));
    projection.sections().forEach(section -> writes.add(ObjectWrite.canonical(section.object())));
    projection.derivedChunks().forEach(
        chunk -> writes.add(ObjectWrite.derived(chunk.object(), chunk.object().canonicalSourceId().orElseThrow())));
    requireBudgets(writes.size(), 0);
    return normalized(validated, writes, 0);
  }

  private NormalizedProjection normalizeSymbols(
      ValidatedIngestionUnit validated, JavaSymbolIngestionUnit unit) {
    String path = unit.workspaceRelativePath().replace('\\', '/');
    SourceRef source = new SourceRef(
        "engineering-workspace", path, Optional.of(validated.inputHash()));
    String sourceId = "java-source:v1:sha256:" + sha256(unit.stableSourceId());
    Map<String, String> sourceMetadata = new LinkedHashMap<>();
    sourceMetadata.put("engineering.subtype", "java-source-file");
    sourceMetadata.put("workspace.relative-path", path);
    sourceMetadata.put("adapter.profile-id", validated.profileId());
    sourceMetadata.put("adapter.profile-version", validated.profileVersion());
    KnowledgeObject sourceObject = KnowledgeObject.canonical(
        sourceId, ObjectType.OTHER, path, Optional.empty(), source, sourceMetadata,
        validated.inputHash());

    List<ObjectWrite> writes = new ArrayList<>();
    writes.add(ObjectWrite.canonical(sourceObject));
    int evidenceCount = 0;
    for (ExtractedSymbol symbol : unit.extraction().result().symbols()) {
      if (!symbol.relativePath().equals(path)) {
        continue;
      }
      Map<String, String> metadata = new LinkedHashMap<>();
      metadata.put("symbol.kind", symbol.kind().name());
      metadata.put("symbol.language", symbol.language().name());
      metadata.put("symbol.identity", symbol.identity().value());
      metadata.put("symbol.location-sensitive", Boolean.toString(symbol.identity().locationSensitive()));
      metadata.put("source.start-line", Integer.toString(symbol.range().startLine()));
      symbol.range().endLine().ifPresent(value -> metadata.put("source.end-line", value.toString()));
      symbol.qualifiedName().ifPresent(value -> metadata.put("symbol.qualified-name", value));
      symbol.signature().ifPresent(value -> metadata.put("symbol.signature", value));
      symbol.scope().ifPresent(value -> metadata.put("symbol.scope", value));
      symbol.scopeKind().ifPresent(value -> metadata.put("symbol.scope-kind", value));
      addEvidence(metadata, symbol.evidence());
      evidenceCount++;
      String symbolId = "java-symbol:v1:sha256:"
          + sha256(sourceId + "\u001f" + symbol.identity().value());
      String contentHash = "sha256:utf8:" + sha256(symbol.identity().value());
      KnowledgeObject object = KnowledgeObject.derived(
          symbolId, ObjectType.CODE_SYMBOL, symbol.simpleName(), symbol.signature(), source,
          metadata, contentHash, sourceId, validated.profileId(), validated.profileVersion(),
          validated.inputHash());
      writes.add(ObjectWrite.derived(object, sourceId));
    }
    requireBudgets(writes.size(), evidenceCount * 6);
    return normalized(validated, writes, evidenceCount);
  }

  private NormalizedProjection normalizeGit(
      ValidatedIngestionUnit validated, GitCommitIngestionUnit unit) {
    GitCommitEvidence commit = unit.commit();
    Map<String, String> metadata = new LinkedHashMap<>();
    metadata.put("git.author-name", commit.authorName());
    metadata.put("git.author-email", commit.authorEmail());
    metadata.put("git.authored-at", commit.authoredAt().toString());
    metadata.put("git.committer-name", commit.committerName());
    metadata.put("git.committer-email", commit.committerEmail());
    metadata.put("git.committed-at", commit.committedAt().toString());
    for (int index = 0; index < commit.parentObjectIds().size(); index++) {
      metadata.put("git.parent." + index, commit.parentObjectIds().get(index));
    }
    addEvidence(metadata, commit.evidence());
    for (int index = 0; index < commit.changes().size(); index++) {
      addChange(metadata, index, commit.changes().get(index));
    }
    String id = "git-commit:v1:" + commit.objectId();
    KnowledgeObject object = KnowledgeObject.canonical(
        id, ObjectType.COMMIT, commit.subject(), Optional.of(commit.subject()),
        commit.evidence().source(), metadata, commit.objectId());
    int evidenceCount = 1 + commit.changes().size();
    requireBudgets(1, 6 + commit.changes().size() * 10);
    return normalized(validated, List.of(ObjectWrite.canonical(object)), evidenceCount);
  }

  private static void addChange(Map<String, String> metadata, int index, GitFileChange change) {
    String prefix = "git.change." + index + ".";
    metadata.put(prefix + "kind", change.kind().name());
    metadata.put(prefix + "binary", Boolean.toString(change.binary()));
    change.oldPath().ifPresent(value -> metadata.put(prefix + "old-path", value));
    change.newPath().ifPresent(value -> metadata.put(prefix + "new-path", value));
    addEvidence(metadata, prefix + "evidence.", change.evidence());
  }

  private static void addEvidence(Map<String, String> metadata, Evidence evidence) {
    addEvidence(metadata, "provenance.evidence.", evidence);
  }

  private static void addEvidence(
      Map<String, String> metadata, String prefix, Evidence evidence) {
    metadata.put(prefix + "type", evidence.type().name());
    metadata.put(prefix + "source-system", evidence.source().system());
    metadata.put(prefix + "source-locator", evidence.source().locator());
    evidence.source().revision().ifPresent(value -> metadata.put(prefix + "source-revision", value));
    metadata.put(prefix + "location", evidence.location());
    metadata.put(prefix + "confidence", Double.toString(evidence.confidence()));
  }

  private static NormalizedProjection normalized(
      ValidatedIngestionUnit validated, List<ObjectWrite> objects, int evidenceCount) {
    return new NormalizedProjection(new ProjectionWrite(objects, List.of(), List.of()),
        objects.size(), evidenceCount, validated.inputHash(), validated.profileId(),
        validated.profileVersion());
  }

  private static void requireBudgets(int objects, int provenanceEntries) {
    if (objects > IngestionRequestValidator.MAX_OBJECTS_PER_UNIT
        || provenanceEntries > IngestionRequestValidator.MAX_PROVENANCE_ENTRIES_PER_UNIT) {
      throw new IllegalArgumentException("normalized source unit exceeds its resource budget");
    }
  }

  private static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder result = new StringBuilder(64);
      for (byte item : digest) {
        result.append(String.format("%02x", Byte.toUnsignedInt(item)));
      }
      return result.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 must be available", exception);
    }
  }
}
