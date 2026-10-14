package me.asu.kb.ingestion;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import me.asu.kb.adapter.document.LegacyDocumentSnapshot;
import me.asu.kb.adapter.gitevidence.GitCommitEvidence;
import me.asu.kb.adapter.gitevidence.GitFileChange;
import me.asu.kb.adapter.symbol.ExtractedSymbol;

/** Pure front-door validation performed before source adaptation or persistence begins. */
final class IngestionRequestValidator {
  static final int DEFAULT_MAX_UNITS = 64;
  static final int HARD_MAX_UNITS = 256;
  static final int DEFAULT_MAX_OBJECTS = 10_000;
  static final int HARD_MAX_OBJECTS = 50_000;
  static final int MAX_OBJECTS_PER_UNIT = 10_000;
  static final int MAX_PROVENANCE_ENTRIES_PER_UNIT = 4_096;

  private final Map<String, Path> workspaceRoots;

  IngestionRequestValidator(Map<String, Path> workspaceRoots) {
    if (workspaceRoots == null) {
      throw new IllegalArgumentException("workspaceRoots must not be null");
    }
    Map<String, Path> roots = new LinkedHashMap<>();
    workspaceRoots.forEach(
        (id, root) -> {
          if (id == null || id.isBlank() || root == null) {
            throw new IllegalArgumentException("workspace registration must have an id and root");
          }
          try {
            roots.put(id, root.toRealPath().normalize());
          } catch (IOException exception) {
            throw new IllegalArgumentException("workspace root is unavailable: " + id, exception);
          }
        });
    this.workspaceRoots = Map.copyOf(roots);
  }

  ValidatedIngestionRequest validate(IngestionRequest request) {
    if (request == null) {
      throw requestFailure(IngestionDiagnosticCode.INVALID_REQUEST, "request must not be null");
    }
    String workspaceId = requireText(request.workspaceId(), "workspaceId");
    Path workspaceRoot = workspaceRoots.get(workspaceId);
    if (workspaceRoot == null) {
      throw requestFailure(IngestionDiagnosticCode.UNKNOWN_WORKSPACE, "workspace is not registered");
    }
    int maxUnits = effectiveLimit(request.maxUnits(), DEFAULT_MAX_UNITS, HARD_MAX_UNITS, "maxUnits");
    int maxObjects =
        effectiveLimit(request.maxObjects(), DEFAULT_MAX_OBJECTS, HARD_MAX_OBJECTS, "maxObjects");
    if (request.sourceUnits().isEmpty()) {
      throw requestFailure(IngestionDiagnosticCode.INVALID_REQUEST, "sourceUnits must not be empty");
    }
    if (request.sourceUnits().size() > maxUnits) {
      throw requestFailure(IngestionDiagnosticCode.LIMIT_EXCEEDED, "source unit limit exceeded");
    }

    List<IngestionUnitValidation> outcomes = new ArrayList<>();
    Set<String> sourceIds = new HashSet<>();
    int totalObjects = 0;
    for (IngestionSourceUnit unit : request.sourceUnits()) {
      IngestionUnitValidation outcome;
      if (!sourceIds.add(unit.stableSourceId())) {
        outcome = reject(unit, IngestionStatus.FAILED, IngestionDiagnosticCode.INVALID_REQUEST,
            "stableSourceId is duplicated in the request");
      } else {
        outcome = validateUnit(workspaceId, workspaceRoot, unit);
      }
      if (outcome.accepted().isPresent()) {
        int nextTotal = totalObjects + outcome.accepted().orElseThrow().estimatedObjectCount();
        if (nextTotal > maxObjects) {
          outcome = reject(unit, IngestionStatus.FAILED, IngestionDiagnosticCode.LIMIT_EXCEEDED,
              "request candidate object limit exceeded");
        } else {
          totalObjects = nextTotal;
        }
      }
      outcomes.add(outcome);
    }
    return new ValidatedIngestionRequest(workspaceId, workspaceRoot, maxObjects, outcomes);
  }

  private IngestionUnitValidation validateUnit(
      String workspaceId, Path workspaceRoot, IngestionSourceUnit unit) {
    if (unit instanceof UnsupportedIngestionUnit) {
      return reject(unit, IngestionStatus.UNSUPPORTED, IngestionDiagnosticCode.UNSUPPORTED_CATEGORY,
          "ripgrep text candidates are query-time evidence and cannot be persisted");
    }
    try {
      if (unit instanceof DocumentIngestionUnit document) {
        return validateDocument(workspaceRoot, document);
      }
      if (unit instanceof JavaSymbolIngestionUnit symbols) {
        return validateSymbols(workspaceRoot, symbols);
      }
      if (unit instanceof GitCommitIngestionUnit git) {
        return validateGit(workspaceId, workspaceRoot, git);
      }
      return reject(unit, IngestionStatus.UNSUPPORTED, IngestionDiagnosticCode.UNSUPPORTED_CATEGORY,
          "source category is not supported");
    } catch (UnitValidationException exception) {
      return reject(unit, IngestionStatus.FAILED, exception.code, exception.getMessage());
    }
  }

  private IngestionUnitValidation validateDocument(
      Path workspaceRoot, DocumentIngestionUnit unit) {
    LegacyDocumentSnapshot snapshot = unit.snapshot();
    if (snapshot.sourceProvenance().isEmpty()
        || snapshot.sourceProvenance().orElseThrow().system().isEmpty()
        || snapshot.sourceProvenance().orElseThrow().locator().isEmpty()) {
      throw unitFailure(IngestionDiagnosticCode.SNAPSHOT_UNAVAILABLE,
          "document source system and locator are required");
    }
    String relativePath = snapshot.repositoryRelativePath().orElseThrow(
        () -> unitFailure(IngestionDiagnosticCode.INVALID_SCOPE,
            "document repositoryRelativePath is required"));
    requireScopedPath(workspaceRoot, relativePath);
    int objectCount = 1 + snapshot.chunks().size();
    requireUnitBudgets(objectCount, 3 + snapshot.chunks().size() * 3);
    String hash = snapshot.article().currentRevision().contentHash();
    return accept(unit, hash, "legacy-document-snapshot", "1", objectCount,
        3 + snapshot.chunks().size() * 3);
  }

  private IngestionUnitValidation validateSymbols(
      Path workspaceRoot, JavaSymbolIngestionUnit unit) {
    String relativePath = normalizedRelativePath(workspaceRoot, unit.workspaceRelativePath());
    if (!unit.extraction().result().successful()) {
      throw unitFailure(IngestionDiagnosticCode.ADAPTER_INCOMPLETE,
          "Ctags extraction must be complete and diagnostic-free");
    }
    String inputHash = unit.extraction().sourceContentHashes().get(relativePath);
    if (inputHash == null) {
      throw unitFailure(IngestionDiagnosticCode.SNAPSHOT_UNAVAILABLE,
          "verified source hash is unavailable for the selected Java file");
    }
    String version = unit.extraction().result().profile().effectiveVersion().orElseThrow(
        () -> unitFailure(IngestionDiagnosticCode.SNAPSHOT_UNAVAILABLE,
            "effective Ctags version is required"));
    List<ExtractedSymbol> selected = unit.extraction().result().symbols().stream()
        .filter(symbol -> symbol.relativePath().equals(relativePath))
        .toList();
    int objectCount = 1 + selected.size();
    int provenanceCount = 3 + selected.size() * 6;
    requireUnitBudgets(objectCount, provenanceCount);
    return accept(unit, inputHash, unit.extraction().result().profile().profileId(), version,
        objectCount, provenanceCount);
  }

  private IngestionUnitValidation validateGit(
      String workspaceId, Path workspaceRoot, GitCommitIngestionUnit unit) {
    GitCommitEvidence commit = unit.commit();
    String objectFormat = unit.profile().objectFormat().orElseThrow(
        () -> unitFailure(IngestionDiagnosticCode.SNAPSHOT_UNAVAILABLE,
            "Git object format is required"));
    String version = unit.profile().effectiveVersion().orElseThrow(
        () -> unitFailure(IngestionDiagnosticCode.SNAPSHOT_UNAVAILABLE,
            "effective Git version is required"));
    int oidLength = switch (objectFormat) {
      case "sha1" -> 40;
      case "sha256" -> 64;
      default -> throw unitFailure(IngestionDiagnosticCode.SNAPSHOT_UNAVAILABLE,
          "unsupported Git object format");
    };
    if (!commit.objectId().matches("[0-9a-f]{" + oidLength + "}")) {
      throw unitFailure(IngestionDiagnosticCode.SNAPSHOT_UNAVAILABLE,
          "Git object id is not complete for the observed object format");
    }
    if (!commit.evidence().source().locator().equals(workspaceId)) {
      throw unitFailure(IngestionDiagnosticCode.INVALID_SCOPE,
          "commit evidence belongs to another workspace");
    }
    for (GitFileChange change : commit.changes()) {
      change.oldPath().ifPresent(path -> requireScopedPath(workspaceRoot, path));
      change.newPath().ifPresent(path -> requireScopedPath(workspaceRoot, path));
      if (!change.evidence().source().locator().startsWith(workspaceId + ":")) {
        throw unitFailure(IngestionDiagnosticCode.INVALID_SCOPE,
            "file-change evidence belongs to another workspace");
      }
    }
    int provenanceCount = 6 + commit.changes().size() * 10;
    requireUnitBudgets(1, provenanceCount);
    return accept(unit, commit.objectId(), unit.profile().profileId(), version, 1, provenanceCount);
  }

  private static IngestionUnitValidation accept(
      IngestionSourceUnit unit, String hash, String profileId, String profileVersion,
      int objectCount, int provenanceCount) {
    return IngestionUnitValidation.accepted(unit,
        new ValidatedIngestionUnit(unit, hash, profileId, profileVersion,
            objectCount, provenanceCount));
  }

  private static IngestionUnitValidation reject(
      IngestionSourceUnit unit, IngestionStatus status, IngestionDiagnosticCode code, String detail) {
    return IngestionUnitValidation.rejected(unit,
        new IngestionUnitReport(unit.stableSourceId(), unit.category(), status, 0, 0,
            Optional.empty(), Optional.empty(), Optional.empty(),
            List.of(new IngestionDiagnostic(code, detail))));
  }

  private static String normalizedRelativePath(Path root, String value) {
    requireScopedPath(root, value);
    return value.replace('\\', '/');
  }

  private static void requireScopedPath(Path root, String value) {
    Path relative;
    try {
      relative = Path.of(value);
    } catch (RuntimeException exception) {
      throw unitFailure(IngestionDiagnosticCode.INVALID_SCOPE, "source path is invalid");
    }
    if (relative.isAbsolute() || !relative.normalize().equals(relative)
        || relative.getNameCount() == 0 || relative.startsWith("..")) {
      throw unitFailure(IngestionDiagnosticCode.INVALID_SCOPE,
          "source path must be normalized and workspace-relative");
    }
    Path resolved = root.resolve(relative).normalize();
    if (!resolved.startsWith(root)) {
      throw unitFailure(IngestionDiagnosticCode.INVALID_SCOPE,
          "source path escapes the registered workspace");
    }
  }

  private static void requireUnitBudgets(int objectCount, int provenanceCount) {
    if (objectCount > MAX_OBJECTS_PER_UNIT || provenanceCount > MAX_PROVENANCE_ENTRIES_PER_UNIT) {
      throw unitFailure(IngestionDiagnosticCode.LIMIT_EXCEEDED,
          "source unit object or provenance limit exceeded");
    }
  }

  private static int effectiveLimit(int requested, int defaultValue, int hardLimit, String name) {
    if (requested < 0 || requested > hardLimit) {
      throw requestFailure(IngestionDiagnosticCode.LIMIT_EXCEEDED, name + " exceeds its hard limit");
    }
    return requested == 0 ? defaultValue : requested;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw requestFailure(IngestionDiagnosticCode.INVALID_REQUEST, name + " must not be blank");
    }
    return value;
  }

  private static IngestionValidationException requestFailure(
      IngestionDiagnosticCode code, String message) {
    return new IngestionValidationException(code, message);
  }

  private static UnitValidationException unitFailure(
      IngestionDiagnosticCode code, String message) {
    return new UnitValidationException(code, message);
  }

  private static final class UnitValidationException extends IllegalArgumentException {
    private final IngestionDiagnosticCode code;

    private UnitValidationException(IngestionDiagnosticCode code, String message) {
      super(message);
      this.code = code;
    }
  }
}
