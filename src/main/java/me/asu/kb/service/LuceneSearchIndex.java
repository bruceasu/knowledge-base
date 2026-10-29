package me.asu.kb.service;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.search.SearchHit;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.StoredField;
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
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

final class LuceneSearchIndex implements Closeable {

  private final Directory directory;
  private final Analyzer analyzer;
  private final CommitHook commitHook;
  private final SearchHook searchHook;
  private IndexWriter writer;
  /** The last fully committed reader. Individual searches retain a reference while in use. */
  private DirectoryReader committedReader;

  LuceneSearchIndex(Path indexDir) throws IOException {
    this(indexDir, () -> {}, () -> {});
  }

  LuceneSearchIndex(Path indexDir, CommitHook commitHook) throws IOException {
    this(indexDir, commitHook, () -> {});
  }

  LuceneSearchIndex(Path indexDir, CommitHook commitHook, SearchHook searchHook) throws IOException {
    this.directory = FSDirectory.open(indexDir);
    this.analyzer = new StandardAnalyzer();
    this.commitHook = commitHook;
    this.searchHook = searchHook;
    this.writer = newWriter();
    refreshCommittedReader();
  }

  private IndexWriter newWriter() throws IOException {
    return new IndexWriter(directory, new IndexWriterConfig(analyzer));
  }

  void addChunk(
      KnowledgeRepository.DocumentRow document,
      KnowledgeRepository.ChunkRow chunk,
      KnowledgeRepository.EffectiveDocumentMetadata metadata)
      throws IOException {
    Document luceneDoc = new Document();
    luceneDoc.add(new StringField("path", document.path(), Field.Store.YES));
    luceneDoc.add(new StringField("root_path", document.rootPath(), Field.Store.YES));
    luceneDoc.add(new StringField("document_id", String.valueOf(document.id()), Field.Store.YES));
    luceneDoc.add(new StoredField("chunk_id", chunk.id()));
    luceneDoc.add(new IntPoint("chunk_no", chunk.chunkNo()));
    luceneDoc.add(new StoredField("chunk_no_stored", chunk.chunkNo()));
    luceneDoc.add(new TextField("title", metadata.title().map(KnowledgeRepository.EffectiveMetadataValue::value).orElse(""), Field.Store.YES));
    luceneDoc.add(new StoredField("chunk_title", Optional.ofNullable(chunk.title()).orElse("")));
    luceneDoc.add(new TextField("content", chunk.content(), Field.Store.YES));
    metadata.tags().forEach(tag -> luceneDoc.add(new TextField("tags", tag.value(), Field.Store.YES)));
    metadata.tags().forEach(tag -> luceneDoc.add(new StringField("tag_exact", tag.value(), Field.Store.NO)));
    metadata.keywords().forEach(keyword -> luceneDoc.add(new TextField("keywords", keyword.value(), Field.Store.YES)));
    metadata.summary().ifPresent(summary -> luceneDoc.add(new TextField("summary", summary.value(), Field.Store.YES)));
    writer.addDocument(luceneDoc);
  }

  void deletePath(String path) throws IOException {
    writer.deleteDocuments(new Term("path", path));
  }

  void clear() throws IOException {
    writer.deleteAll();
  }

  void commit() throws IOException {
    commitHook.beforeCommit();
    writer.commit();
    refreshCommittedReader();
  }

  @FunctionalInterface
  interface CommitHook {
    void beforeCommit() throws IOException;
  }

  @FunctionalInterface
  interface SearchHook {
    void afterReaderAcquired() throws Exception;
  }

  /** Discards uncommitted index mutations without publishing a partial rebuild. */
  synchronized void discardUncommittedChanges() throws IOException {
    writer.rollback();
    writer = newWriter();
    refreshCommittedReader();
  }

  List<SearchHit> search(String queryText, int limit, String pathPrefix) throws Exception {
    return search(queryText, limit, pathPrefix, List.of());
  }

  List<SearchHit> search(String queryText, int limit, String pathPrefix, List<String> tagFilters)
      throws Exception {
    return search(queryText, limit, pathPrefix, tagFilters, null);
  }

  List<SearchHit> search(String queryText, int limit, String pathPrefix, List<String> tagFilters,
      java.util.Set<Long> allowedDocuments) throws Exception {
    DirectoryReader reader = acquireCommittedReader();
    if (reader == null) {
      return List.of();
    }
    try {
      searchHook.afterReaderAcquired();
      IndexSearcher searcher = new IndexSearcher(reader);
      Query query = buildQuery(queryText, pathPrefix, tagFilters);
      if (allowedDocuments != null) {
        var terms = allowedDocuments.stream().map(id -> new org.apache.lucene.util.BytesRef(id.toString())).toList();
        query = new org.apache.lucene.search.BooleanQuery.Builder()
            .add(query, org.apache.lucene.search.BooleanClause.Occur.MUST)
            .add(new org.apache.lucene.search.TermInSetQuery("document_id", terms), org.apache.lucene.search.BooleanClause.Occur.FILTER)
            .build();
      }
      TopDocs topDocs = searcher.search(query, limit);
      List<SearchHit> hits = new ArrayList<>();
      for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
        Document doc = searcher.doc(scoreDoc.doc);
        String content = doc.get("content");
        String snippet = buildSnippet(content, queryText);
        hits.add(
            new SearchHit(
                Long.parseLong(doc.get("document_id")),
                doc.getField("chunk_id").numericValue().longValue(),
                doc.get("path"),
                Optional.ofNullable(doc.get("chunk_title")).filter(value -> !value.isBlank()).orElse(doc.get("title")),
                doc.getField("chunk_no_stored").numericValue().intValue(),
                snippet,
                doc.get("summary"),
                scoreDoc.score,
                List.of()));
      }
      return hits;
    } finally {
      reader.decRef();
    }
  }

  private synchronized DirectoryReader acquireCommittedReader() throws IOException {
    if (committedReader == null) {
      return null;
    }
    committedReader.incRef();
    return committedReader;
  }

  private synchronized void refreshCommittedReader() throws IOException {
    if (!DirectoryReader.indexExists(directory)) {
      return;
    }
    if (committedReader == null) {
      committedReader = DirectoryReader.open(directory);
      return;
    }
    DirectoryReader refreshed = DirectoryReader.openIfChanged(committedReader);
    if (refreshed != null) {
      DirectoryReader previous = committedReader;
      committedReader = refreshed;
      previous.decRef();
    }
  }

  private Query buildQuery(String queryText, String pathPrefix, List<String> tagFilters) throws Exception {
    MultiFieldQueryParser parser =
        new MultiFieldQueryParser(
            new String[] {"title", "tags", "keywords", "summary", "content"},
            analyzer,
            Map.of("title", 4.0f, "tags", 3.0f, "keywords", 2.0f, "summary", 1.5f, "content", 1.0f));
    Query base = parser.parse(MultiFieldQueryParser.escape(queryText));
    boolean hasPathFilter = pathPrefix != null && !pathPrefix.isBlank();
    boolean hasTagFilter = tagFilters != null && !tagFilters.isEmpty();
    if (!hasPathFilter && !hasTagFilter) {
      return base;
    }
    BooleanQuery.Builder builder = new BooleanQuery.Builder();
    builder.add(base, BooleanClause.Occur.MUST);
    if (hasPathFilter) {
      builder.add(new org.apache.lucene.search.PrefixQuery(new org.apache.lucene.index.Term("path", pathPrefix)), BooleanClause.Occur.FILTER);
    }
    for (String tag : tagFilters == null ? List.<String>of() : tagFilters) {
      builder.add(
          new org.apache.lucene.search.TermQuery(
              new Term("tag_exact", tag.toLowerCase(Locale.ROOT).trim())),
          BooleanClause.Occur.FILTER);
    }
    return builder.build();
  }

  private String buildSnippet(String content, String queryText) {
    if (content == null || content.isBlank()) {
      return "";
    }
    String lowered = content.toLowerCase(Locale.ROOT);
    for (String token : queryText.toLowerCase(Locale.ROOT).split("\\s+")) {
      if (token.isBlank()) {
        continue;
      }
      int index = lowered.indexOf(token);
      if (index >= 0) {
        int start = Math.max(0, index - 40);
        int end = Math.min(content.length(), index + token.length() + 80);
        return content.substring(start, end).replaceAll("\\s+", " ").trim();
      }
    }
    return content.length() <= 160 ? content : content.substring(0, 160).replaceAll("\\s+", " ").trim();
  }

  @Override
  public void close() throws IOException {
    if (committedReader != null) {
      committedReader.close();
      committedReader = null;
    }
    writer.close();
    directory.close();
    analyzer.close();
  }
}
