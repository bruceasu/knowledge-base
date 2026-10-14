package me.asu.kb.ingestion;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.store.SqliteKnowledgeStore;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectLifecycleState;
import me.asu.kb.store.SqliteKnowledgeStore.ObjectWrite;
import me.asu.kb.store.SqliteKnowledgeStore.ProjectionReplacement;
import me.asu.kb.store.SqliteKnowledgeStore.StoredObject;

/** SQLite-backed coordinator that stages every source unit before opening its short write. */
public final class SqliteKnowledgeProjectionIngestion implements KnowledgeProjectionIngestion {
  private final SqliteKnowledgeStore store;
  private final IngestionRequestValidator validator;
  private final KnowledgeProjectionNormalizer normalizer;

  public SqliteKnowledgeProjectionIngestion(
      SqliteKnowledgeStore store, Map<String, Path> workspaceRoots) {
    this(store, new IngestionRequestValidator(workspaceRoots),
        new KnowledgeProjectionNormalizer());
  }

  SqliteKnowledgeProjectionIngestion(
      SqliteKnowledgeStore store,
      IngestionRequestValidator validator,
      KnowledgeProjectionNormalizer normalizer) {
    this.store = Objects.requireNonNull(store, "store");
    this.validator = Objects.requireNonNull(validator, "validator");
    this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
  }

  @Override
  public IngestionReport ingest(IngestionRequest request) {
    ValidatedIngestionRequest validated;
    try {
      validated = validator.validate(request);
    } catch (IngestionValidationException exception) {
      return rejectedRequest(request, exception);
    }
    List<IngestionUnitReport> reports = new ArrayList<>();
    for (IngestionUnitValidation outcome : validated.units()) {
      if (outcome.rejected().isPresent()) {
        reports.add(outcome.rejected().orElseThrow());
        continue;
      }
      ValidatedIngestionUnit unit = outcome.accepted().orElseThrow();
      try {
        NormalizedProjection projection = normalizer.normalize(unit);
        ProjectionState state = inspect(projection);
        if (state == ProjectionState.UNCHANGED) {
          reports.add(completed(unit, projection, IngestionStatus.UNCHANGED));
          continue;
        }
        persist(projection, replacements(projection));
        reports.add(completed(unit, projection,
            state == ProjectionState.CHANGED ? IngestionStatus.STALE : IngestionStatus.SAVED));
      } catch (IngestionPersistenceException exception) {
        reports.add(failed(unit, IngestionDiagnosticCode.STORE_FAILED,
            "atomic Store write failed"));
      } catch (RuntimeException exception) {
        reports.add(failed(unit, IngestionDiagnosticCode.NORMALIZATION_FAILED,
            "source unit could not be normalized"));
      }
    }
    return new IngestionReport(validated.workspaceId(), reports);
  }

  private static IngestionReport rejectedRequest(
      IngestionRequest request, IngestionValidationException exception) {
    String workspaceId = request == null ? "" : request.workspaceId();
    if (request == null || request.sourceUnits().isEmpty()) {
      return new IngestionReport(workspaceId, List.of());
    }
    List<IngestionUnitReport> reports = request.sourceUnits().stream()
        .map(unit -> new IngestionUnitReport(
            unit.stableSourceId(), unit.category(), IngestionStatus.FAILED, 0, 0,
            Optional.empty(), Optional.empty(), Optional.empty(),
            List.of(new IngestionDiagnostic(exception.code(), exception.getMessage()))))
        .toList();
    return new IngestionReport(workspaceId, reports);
  }

  private ProjectionState inspect(NormalizedProjection projection) {
    boolean anyStored = false;
    try {
      for (ObjectWrite write : projection.write().objects()) {
        Optional<StoredObject> stored = store.findObject(write.object().id());
        if (stored.isEmpty()) {
          return anyStored ? ProjectionState.CHANGED : ProjectionState.NEW;
        }
        anyStored = true;
        StoredObject current = stored.orElseThrow();
        if (current.lifecycleState() != ObjectLifecycleState.CURRENT
            || !current.object().equals(write.object())
            || !current.inputObjectId().equals(write.inputObjectId())) {
          return ProjectionState.CHANGED;
        }
      }
      return anyStored ? ProjectionState.UNCHANGED : ProjectionState.NEW;
    } catch (SQLException exception) {
      throw new IngestionPersistenceException("knowledge projection read failed", exception);
    }
  }

  private static List<ProjectionReplacement> replacements(NormalizedProjection projection) {
    return projection.write().objects().stream()
        .map(ObjectWrite::object)
        .filter(object -> object.generatorId().isPresent())
        .map(object -> new ProjectionReplacement(
            object.canonicalSourceId().orElseThrow(), object.generatorId().orElseThrow(),
            object.generatorVersion().orElseThrow()))
        .distinct()
        .toList();
  }

  private void persist(
      NormalizedProjection projection, List<ProjectionReplacement> replacements) {
    try {
      store.save(projection.write(), replacements);
    } catch (SQLException | RuntimeException exception) {
      throw new IngestionPersistenceException("knowledge projection write failed", exception);
    }
  }

  private static IngestionUnitReport completed(
      ValidatedIngestionUnit unit, NormalizedProjection projection, IngestionStatus status) {
    return new IngestionUnitReport(
        unit.sourceUnit().stableSourceId(), unit.sourceUnit().category(), status,
        projection.objectCount(), projection.evidenceCount(), Optional.of(projection.inputHash()),
        Optional.of(projection.profileId()), Optional.of(projection.profileVersion()), List.of());
  }

  private static IngestionUnitReport failed(
      ValidatedIngestionUnit unit, IngestionDiagnosticCode code, String detail) {
    return new IngestionUnitReport(
        unit.sourceUnit().stableSourceId(), unit.sourceUnit().category(), IngestionStatus.FAILED,
        0, 0, Optional.of(unit.inputHash()), Optional.of(unit.profileId()),
        Optional.of(unit.profileVersion()), List.of(new IngestionDiagnostic(code, detail)));
  }

  private static final class IngestionPersistenceException extends RuntimeException {
    private IngestionPersistenceException(String message, Exception cause) {
      super(message, cause);
    }
  }

  private enum ProjectionState {
    NEW,
    UNCHANGED,
    CHANGED
  }
}
