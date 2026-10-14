package me.asu.kb.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import me.asu.kb.llm.EchoLlmClient;

/**
 * Reproducible, opt-in local performance baseline. It is deliberately not a JUnit test so ordinary
 * verification does not turn timings into pass/fail gates.
 */
public final class PerformanceMeasurement {
  private static final int DOCUMENTS = 100;
  private static final int MULTI_CHUNK_DOCUMENTS = 20;
  private static final int RUNS = 10;

  private PerformanceMeasurement() {}

  public static void main(String[] args) throws Exception {
    Path workspace = Files.createTempDirectory("knowledge-base-performance-");
    try {
      Path sources = Files.createDirectories(workspace.resolve("sources"));
      createDataset(sources);
      try (KnowledgeBase knowledgeBase = new KnowledgeBase(workspace, new EchoLlmClient())) {
        knowledgeBase.sync(sources);
        knowledgeBase.rebuild();
        run("rebuild", () -> knowledgeBase.rebuild());
        run("100 searches", () -> {
          for (int i = 0; i < 100; i++) knowledgeBase.search("benchmark token", 10);
        });
        run("unchanged sync", () -> knowledgeBase.sync(sources));
        run("page load", () -> knowledgeBase.documents(50, 0, "benchmark"));
      }
      try (KnowledgeRepository repository = new KnowledgeRepository(workspace.resolve("data/kb.sqlite"))) {
        System.out.printf("dataset: documents=%d, multiChunkDocuments=%d, chunks=%d%n",
            DOCUMENTS, MULTI_CHUNK_DOCUMENTS, repository.findAllChunks().size());
      }
    } finally {
      deleteTree(workspace);
    }
  }

  private static void createDataset(Path sources) throws IOException {
    for (int i = 0; i < DOCUMENTS; i++) {
      String text = "# Benchmark " + i + "\n\nbenchmark token document " + i + "\n";
      if (i < MULTI_CHUNK_DOCUMENTS) {
        StringBuilder sections = new StringBuilder(text);
        for (int section = 0; section < 12; section++) {
          sections.append("\n## Section ").append(section).append('\n');
          sections.append(("benchmark token repeated content for chunking " + i + ". ").repeat(60));
          sections.append('\n');
        }
        text = sections.toString();
      }
      Files.writeString(sources.resolve(String.format("benchmark-%03d.md", i)), text,
          StandardCharsets.UTF_8);
    }
  }

  private static void run(String name, CheckedOperation operation) throws Exception {
    operation.run();
    List<Long> timings = new ArrayList<>();
    for (int i = 0; i < RUNS; i++) {
      long started = System.nanoTime();
      operation.run();
      timings.add(System.nanoTime() - started);
    }
    timings.sort(Comparator.naturalOrder());
    System.out.printf("%s: p50=%.2f ms, p95=%.2f ms, samples=%s%n", name,
        millis(timings.get(timings.size() / 2)), millis(timings.get((int) Math.ceil(RUNS * 0.95) - 1)),
        Arrays.toString(timings.stream().mapToDouble(PerformanceMeasurement::millis).toArray()));
  }

  private static double millis(long nanos) {
    return nanos / 1_000_000.0;
  }

  private static void deleteTree(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
    }
  }

  @FunctionalInterface
  private interface CheckedOperation {
    void run() throws Exception;
  }
}
