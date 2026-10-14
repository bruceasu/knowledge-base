package me.asu.kb.adapter.codesearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import me.asu.kb.core.knowledge.EvidenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepCodeSearchAdapterTest {
  @TempDir Path tempDir;

  private Path workspace;

  @BeforeEach
  void setUp() throws IOException {
    workspace = tempDir.resolve("workspace");
    Files.createDirectories(workspace);
  }

  @Test
  void rejectsInvalidRequestsBeforeLaunchingAProcess() throws IOException {
    writeWorkspaceFile("src/Present.java", "needle\n");
    FakeLauncher launcher = new FakeLauncher(FakeProcess.completed("", 0));
    RipgrepCodeSearchAdapter adapter = adapter(launcher, defaults());

    assertDiagnostic(
        adapter.search(new CodeSearchRequest("main", "", List.of(), 0, 0)),
        CodeSearchDiagnosticCode.EMPTY_QUERY);
    assertDiagnostic(
        adapter.search(CodeSearchRequest.exact("unknown", "needle")),
        CodeSearchDiagnosticCode.UNKNOWN_WORKSPACE);
    assertDiagnostic(
        adapter.search(new CodeSearchRequest("main", "needle", List.of("../outside"), 0, 0)),
        CodeSearchDiagnosticCode.INVALID_SCOPE);
    assertDiagnostic(
        adapter.search(
            new CodeSearchRequest("main", "needle", java.util.Collections.nCopies(65, "src"), 0, 0)),
        CodeSearchDiagnosticCode.REQUEST_LIMIT_EXCEEDED);

    assertEquals(0, launcher.startCount);
  }

  @Test
  void constructsLiteralShellFreeRequestWithoutIgnoreOverrides() throws IOException {
    writeWorkspaceFile("src/Present.java", "no match\n");
    FakeLauncher launcher = new FakeLauncher(FakeProcess.completed("", 1));
    RipgrepCodeSearchAdapter adapter = adapter(launcher, defaults());
    String query = "--looks-like-an-option $(not-a-command)";

    CodeSearchResult result =
        adapter.search(new CodeSearchRequest("main", query, List.of("src"), 0, 0));

    assertTrue(result.successful());
    assertEquals(workspace.toRealPath(), launcher.workingDirectory);
    assertEquals(query, launcher.arguments.get(launcher.arguments.indexOf("--") + 1));
    assertTrue(launcher.arguments.contains("--fixed-strings"));
    assertFalse(launcher.arguments.contains("--hidden"));
    assertFalse(launcher.arguments.contains("--no-ignore"));
    assertEquals("src", launcher.arguments.get(launcher.arguments.size() - 1));
  }

  @Test
  void normalizesSortsAndReturnsSourceEvidenceWithoutARelation() throws IOException {
    writeWorkspaceFile("z/Two.java", "needle z\n");
    writeWorkspaceFile("a/One.java", "before\nxx needle\nafter\n");
    String output =
        matchEvent("z/Two.java", 1, "needle z\n", 0, 6)
            + contextEvent("a/One.java", 1, "before\n")
            + matchEvent("a/One.java", 2, "xx needle\n", 3, 9)
            + contextEvent("a/One.java", 3, "after\n");
    FakeLauncher launcher = new FakeLauncher(FakeProcess.completed(output, 0));
    RipgrepCodeSearchAdapter adapter = adapter(launcher, defaults());

    CodeSearchResult result =
        adapter.search(new CodeSearchRequest("main", "needle", List.of(), 1, 0));

    assertTrue(result.successful());
    assertEquals(List.of("a/One.java", "z/Two.java"), result.matches().stream().map(CodeSearchMatch::relativePath).toList());
    CodeSearchMatch first = result.matches().get(0);
    assertEquals(new CodeSearchRange(2, 4, 2, 10), first.range());
    assertEquals("before\nxx needle\nafter", first.excerpt());
    assertFalse(first.excerptTruncated());
    assertEquals("needle", first.matchedText());
    assertEquals(EvidenceType.SOURCE_TEXT, first.evidence().type());
    assertEquals("engineering-workspace", first.evidence().source().system());
    assertEquals("main:a/One.java", first.evidence().source().locator());
    assertEquals("a/One.java:L2:C4-L2:C10", first.evidence().location());
  }

  @Test
  void mapsNormalEmptyAndToolFailuresWithoutPretendingTheyAreTheSame() {
    FakeLauncher emptyLauncher = new FakeLauncher(FakeProcess.completed("", 1));
    CodeSearchResult empty = adapter(emptyLauncher, defaults()).search(CodeSearchRequest.exact("main", "needle"));

    assertTrue(empty.successful());
    assertTrue(empty.matches().isEmpty());
    assertTrue(empty.diagnostics().isEmpty());

    FakeLauncher emptySuccessLauncher = new FakeLauncher(FakeProcess.completed("", 0));
    assertDiagnostic(
        adapter(emptySuccessLauncher, defaults()).search(CodeSearchRequest.exact("main", "needle")),
        CodeSearchDiagnosticCode.MALFORMED_OUTPUT);

    FakeLauncher unavailableLauncher = new FakeLauncher(new IOException("missing"));
    assertDiagnostic(
        adapter(unavailableLauncher, defaults()).search(CodeSearchRequest.exact("main", "needle")),
        CodeSearchDiagnosticCode.TOOL_UNAVAILABLE);

    FakeLauncher unexpectedLauncher = new FakeLauncher(FakeProcess.completed("", 2));
    assertDiagnostic(
        adapter(unexpectedLauncher, defaults()).search(CodeSearchRequest.exact("main", "needle")),
        CodeSearchDiagnosticCode.UNEXPECTED_EXIT);
  }

  @Test
  void mapsTimeoutMalformedAndEncodingOutputDiagnosticsAndCleansUp() {
    FakeProcess timedOut = FakeProcess.running("{}");
    FakeLauncher timeoutLauncher = new FakeLauncher(timedOut);
    assertDiagnostic(
        adapter(timeoutLauncher, settings(200, 1_024, Duration.ofMillis(10)))
            .search(CodeSearchRequest.exact("main", "needle")),
        CodeSearchDiagnosticCode.TIMEOUT);
    assertTrue(timedOut.destroyed);

    FakeLauncher malformedLauncher = new FakeLauncher(FakeProcess.completed("not-json\n", 0));
    assertDiagnostic(
        adapter(malformedLauncher, defaults()).search(CodeSearchRequest.exact("main", "needle")),
        CodeSearchDiagnosticCode.MALFORMED_OUTPUT);

    String invalidEncodedPath =
        "{\"type\":\"match\",\"data\":{\"path\":{\"bytes\":\"%%%\"}}}\n";
    FakeLauncher encodingLauncher = new FakeLauncher(FakeProcess.completed(invalidEncodedPath, 0));
    assertDiagnostic(
        adapter(encodingLauncher, defaults()).search(CodeSearchRequest.exact("main", "needle")),
        CodeSearchDiagnosticCode.ENCODING_ERROR);

    String escapedPath = matchEvent("../outside.java", 1, "needle\n", 0, 6);
    FakeLauncher escapedPathLauncher = new FakeLauncher(FakeProcess.completed(escapedPath, 0));
    assertDiagnostic(
        adapter(escapedPathLauncher, defaults()).search(CodeSearchRequest.exact("main", "needle")),
        CodeSearchDiagnosticCode.MALFORMED_OUTPUT);
  }

  @Test
  void convertsRipgrepByteOffsetsToUnicodeColumns() throws IOException {
    writeWorkspaceFile("a/Unicode.java", "éneedle\n");
    String output = matchEvent("a/Unicode.java", 1, "éneedle\n", 2, 8);
    CodeSearchResult result =
        adapter(new FakeLauncher(FakeProcess.completed(output, 0)), defaults())
            .search(CodeSearchRequest.exact("main", "needle"));

    assertTrue(result.successful());
    assertEquals(new CodeSearchRange(1, 2, 1, 8), result.matches().get(0).range());
    assertEquals("éneedle", result.matches().get(0).excerpt());
  }

  @Test
  void exposesOutputAndResultBudgetTruncation() throws IOException {
    writeWorkspaceFile("a/One.java", "needle\n");
    writeWorkspaceFile("b/Two.java", "needle\n");
    String output =
        matchEvent("b/Two.java", 1, "needle\n", 0, 6)
            + matchEvent("a/One.java", 1, "needle\n", 0, 6);

    CodeSearchResult resultLimited =
        adapter(new FakeLauncher(FakeProcess.completed(output, 0)), defaults())
            .search(new CodeSearchRequest("main", "needle", List.of(), 0, 1));
    assertFalse(resultLimited.complete());
    assertEquals(List.of("a/One.java"), resultLimited.matches().stream().map(CodeSearchMatch::relativePath).toList());
    assertEquals(CodeSearchDiagnosticCode.RESULT_TRUNCATED, resultLimited.diagnostics().get(0).code());

    FakeProcess outputLimitedProcess = FakeProcess.completed(output, 0);
    CodeSearchResult outputLimited =
        adapter(new FakeLauncher(outputLimitedProcess), settings(200, 24, Duration.ofSeconds(1)))
            .search(CodeSearchRequest.exact("main", "needle"));
    assertFalse(outputLimited.complete());
    assertEquals(CodeSearchDiagnosticCode.OUTPUT_TRUNCATED, outputLimited.diagnostics().get(0).code());
    assertTrue(outputLimitedProcess.destroyed);
  }

  private RipgrepCodeSearchAdapter adapter(
      FakeLauncher launcher, RipgrepCodeSearchAdapter.Settings settings) {
    return new RipgrepCodeSearchAdapter(Map.of("main", workspace), settings, launcher);
  }

  private static RipgrepCodeSearchAdapter.Settings defaults() {
    return RipgrepCodeSearchAdapter.Settings.defaults();
  }

  private static RipgrepCodeSearchAdapter.Settings settings(
      int maxResults, int maxOutputBytes, Duration timeout) {
    return new RipgrepCodeSearchAdapter.Settings(
        "rg", 512, 64, 3, maxResults, 4_096, maxOutputBytes, timeout);
  }

  private void writeWorkspaceFile(String relativePath, String content) throws IOException {
    Path file = workspace.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static void assertDiagnostic(CodeSearchResult result, CodeSearchDiagnosticCode code) {
    assertFalse(result.complete());
    assertEquals(List.of(code), result.diagnostics().stream().map(CodeSearchDiagnostic::code).toList());
  }

  private static String matchEvent(String path, int line, String text, int start, int end) {
    return "{\"type\":\"match\",\"data\":{\"path\":{\"text\":\"%s\"},\"lines\":{\"text\":\"%s\"},\"line_number\":%d,\"submatches\":[{\"match\":{\"text\":\"needle\"},\"start\":%d,\"end\":%d}]}}%n"
        .formatted(jsonText(path), jsonText(text), line, start, end);
  }

  private static String contextEvent(String path, int line, String text) {
    return "{\"type\":\"context\",\"data\":{\"path\":{\"text\":\"%s\"},\"lines\":{\"text\":\"%s\"},\"line_number\":%d}}%n"
        .formatted(jsonText(path), jsonText(text), line);
  }

  private static String jsonText(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  private static final class FakeLauncher implements RipgrepProcessLauncher {
    private final RipgrepRunningProcess process;
    private final IOException startupFailure;
    private int startCount;
    private List<String> arguments = List.of();
    private Path workingDirectory;

    private FakeLauncher(RipgrepRunningProcess process) {
      this.process = process;
      this.startupFailure = null;
    }

    private FakeLauncher(IOException startupFailure) {
      this.process = null;
      this.startupFailure = startupFailure;
    }

    @Override
    public RipgrepRunningProcess start(List<String> arguments, Path workingDirectory) throws IOException {
      startCount++;
      this.arguments = List.copyOf(arguments);
      this.workingDirectory = workingDirectory;
      if (startupFailure != null) {
        throw startupFailure;
      }
      return process;
    }
  }

  private static final class FakeProcess implements RipgrepRunningProcess {
    private final InputStream output;
    private final int exitCode;
    private final boolean finishes;
    private boolean destroyed;
    private boolean forciblyDestroyed;

    private FakeProcess(InputStream output, int exitCode, boolean finishes) {
      this.output = output;
      this.exitCode = exitCode;
      this.finishes = finishes;
    }

    private static FakeProcess completed(String output, int exitCode) {
      return new FakeProcess(
          new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), exitCode, true);
    }

    private static FakeProcess running(String output) {
      return new FakeProcess(
          new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), 0, false);
    }

    @Override
    public InputStream standardOutput() {
      return output;
    }

    @Override
    public boolean waitFor(Duration timeout) {
      return finishes;
    }

    @Override
    public int exitCode() {
      return exitCode;
    }

    @Override
    public void destroy() {
      destroyed = true;
    }

    @Override
    public void destroyForcibly() {
      forciblyDestroyed = true;
    }
  }
}
