package me.asu.kb.retrieval;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import me.asu.kb.core.knowledge.KnowledgeObject;
import me.asu.kb.core.knowledge.ObjectType;
import me.asu.kb.core.knowledge.SourceRef;
import me.asu.kb.store.SqliteKnowledgeStore;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/** Separate, rebuildable Lucene projection for traceable engineering-knowledge objects. */
final class EngineeringKnowledgeLuceneProjection implements Closeable {
  static final String SCHEMA_VERSION = "engineering-knowledge-lucene-v1";

  private final Directory directory;
  private final Analyzer analyzer;
  private IndexWriter writer;
  private DirectoryReader committedReader;

  EngineeringKnowledgeLuceneProjection(Path indexDirectory) throws IOException {
    directory = FSDirectory.open(Objects.requireNonNull(indexDirectory, "indexDirectory"));
    analyzer = new StandardAnalyzer();
    writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
    refreshReader();
  }

  synchronized void rebuild(String workspaceId, List<SqliteKnowledgeStore.StoredObject> objects)
      throws IOException {
    requireText(workspaceId, "workspaceId");
    objects = List.copyOf(Objects.requireNonNull(objects, "objects"));
    try {
      writer.deleteAll();
      for (SqliteKnowledgeStore.StoredObject stored : objects) {
        writer.addDocument(toDocument(workspaceId, stored));
      }
      publishRebuild();
    } catch (IOException | RuntimeException failure) {
      discardRebuild(failure);
      throw failure;
    }
  }

  /** Rebuilds from committed Store pages and publishes only after the final page succeeds. */
  synchronized void rebuildFromStore(String workspaceId, SqliteKnowledgeStore store)
      throws IOException, SQLException {
    requireText(workspaceId, "workspaceId");
    Objects.requireNonNull(store, "store");
    try {
      writer.deleteAll();
      Optional<String> afterId = Optional.empty();
      while (true) {
        List<SqliteKnowledgeStore.StoredObject> page = store.findObjectsAfter(afterId, 500, false);
        for (SqliteKnowledgeStore.StoredObject stored : page) {
          writer.addDocument(toDocument(workspaceId, stored));
        }
        if (page.size() < 500) {
          break;
        }
        afterId = Optional.of(page.get(page.size() - 1).object().id());
      }
      publishRebuild();
    } catch (IOException | SQLException | RuntimeException failure) {
      discardRebuild(failure);
      throw failure;
    }
  }

  private void publishRebuild() throws IOException {
    writer.commit();
    refreshReader();
  }

  private void discardRebuild(Exception failure) {
    try {
      writer.rollback();
      writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
    } catch (IOException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  List<LexicalHit> search(ValidatedKnowledgeQuery request) throws Exception {
    DirectoryReader reader = acquireReader();
    if (reader == null) {
      return List.of();
    }
    try {
      MultiFieldQueryParser parser =
          new MultiFieldQueryParser(
              new String[] {"title", "content", "metadata"},
              analyzer,
              Map.of("title", 4.0f, "metadata", 2.0f, "content", 1.0f));
      BooleanQuery.Builder query = new BooleanQuery.Builder();
      query.add(
          parser.parse(MultiFieldQueryParser.escape(request.text())), BooleanClause.Occur.MUST);
      query.add(
          new TermQuery(new Term("workspace_id", request.workspaceId())),
          BooleanClause.Occur.FILTER);
      if (!request.includeStale()) {
        query.add(new TermQuery(new Term("lifecycle", "CURRENT")), BooleanClause.Occur.FILTER);
      }
      if (!request.objectTypes().isEmpty()) {
        BooleanQuery.Builder types = new BooleanQuery.Builder();
        for (ObjectType type : request.objectTypes()) {
          types.add(
              new TermQuery(new Term("object_type", type.name())),
              BooleanClause.Occur.SHOULD);
        }
        types.setMinimumNumberShouldMatch(1);
        query.add(types.build(), BooleanClause.Occur.FILTER);
      }
      IndexSearcher searcher = new IndexSearcher(reader);
      ScoreDoc[] scoreDocs =
          searcher.search(query.build(), request.budgets().luceneCandidates()).scoreDocs;
      List<LexicalHit> hits = new ArrayList<>(scoreDocs.length);
      for (ScoreDoc scoreDoc : scoreDocs) {
        Document document = searcher.storedFields().document(scoreDoc.doc);
        hits.add(readHit(document));
      }
      return List.copyOf(hits);
    } finally {
      reader.decRef();
    }
  }

  private static Document toDocument(
      String workspaceId, SqliteKnowledgeStore.StoredObject stored) {
    KnowledgeObject object = stored.object();
    Document document = new Document();
    addKeyword(document, "workspace_id", workspaceId);
    addKeyword(document, "object_id", object.id());
    addKeyword(document, "object_type", object.type().name());
    addKeyword(document, "source_system", object.source().system());
    addKeyword(document, "source_locator", object.source().locator());
    object.source().revision().ifPresent(value -> addKeyword(document, "source_revision", value));
    addKeyword(document, "knowledge_class", object.knowledgeClass().name());
    addKeyword(document, "lifecycle", stored.lifecycleState().name());
    addKeyword(
        document,
        "input_content_hash",
        object.inputContentHash().orElse(object.contentHash()));
    addKeyword(document, "projection_version", SCHEMA_VERSION);
    document.add(new TextField("title", object.title(), Field.Store.YES));
    object.content().ifPresent(value -> document.add(new TextField("content", value, Field.Store.YES)));
    if (!object.metadata().isEmpty()) {
      document.add(
          new TextField(
              "metadata", String.join(" ", object.metadata().values()), Field.Store.NO));
    }
    return document;
  }

  private static void addKeyword(Document document, String name, String value) {
    document.add(new StringField(name, value, Field.Store.YES));
  }

  private static LexicalHit readHit(Document document) {
    return new LexicalHit(
        required(document, "workspace_id"),
        required(document, "object_id"),
        ObjectType.valueOf(required(document, "object_type")),
        required(document, "title"),
        Optional.ofNullable(document.get("content")),
        new SourceRef(
            required(document, "source_system"),
            required(document, "source_locator"),
            Optional.ofNullable(document.get("source_revision"))),
        required(document, "knowledge_class"),
        required(document, "lifecycle"),
        required(document, "input_content_hash"),
        required(document, "projection_version"));
  }

  private synchronized DirectoryReader acquireReader() throws IOException {
    if (committedReader == null) {
      return null;
    }
    committedReader.incRef();
    return committedReader;
  }

  private synchronized void refreshReader() throws IOException {
    if (!DirectoryReader.indexExists(directory)) {
      return;
    }
    if (committedReader == null) {
      committedReader = DirectoryReader.open(directory);
      return;
    }
    DirectoryReader replacement = DirectoryReader.openIfChanged(committedReader);
    if (replacement != null) {
      DirectoryReader previous = committedReader;
      committedReader = replacement;
      previous.decRef();
    }
  }

  @Override
  public synchronized void close() throws IOException {
    if (committedReader != null) {
      committedReader.close();
      committedReader = null;
    }
    writer.close();
    directory.close();
    analyzer.close();
  }

  private static String required(Document document, String name) {
    return requireText(document.get(name), name);
  }

  private static String requireText(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  record LexicalHit(
      String workspaceId,
      String objectId,
      ObjectType objectType,
      String title,
      Optional<String> content,
      SourceRef source,
      String knowledgeClass,
      String lifecycle,
      String inputContentHash,
      String projectionVersion) {}
}
