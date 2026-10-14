package me.asu.kb.adapter.symbol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import me.asu.kb.core.knowledge.EvidenceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CtagsSymbolExtractorTest {
  private static final String FIXTURE_ROOT =
      "/me/asu/kb/adapter/symbol/fixtures/universal-ctags-json-java-v1/";

  @TempDir Path tempDir;

  private Path workspace;

  @BeforeEach
  void setUp() throws IOException {
    workspace = tempDir.resolve("workspace");
    Files.createDirectories(workspace);
  }

  @Test
  void rejectsInvalidRequestsBeforeLaunchingAProcess() throws IOException {
    writeWorkspaceFile("src/main/java/demo/Widget.java", "class Widget {}\n");
    FakeLauncher launcher = new FakeLauncher();
    CtagsSymbolExtractor extractor = extractor(launcher, defaults());

    assertDiagnostic(
        extractor.extract(
            new SymbolExtractionRequest("main", "Kotlin", List.of("src/main/java/demo/Widget.java"), 0)),
        SymbolExtractionDiagnosticCode.UNSUPPORTED_LANGUAGE);
    assertDiagnostic(
        extractor.extract(SymbolExtractionRequest.forJava("unknown", List.of("src/main/java/demo/Widget.java"))),
        SymbolExtractionDiagnosticCode.UNKNOWN_WORKSPACE);
    assertDiagnostic(
        extractor.extract(SymbolExtractionRequest.forJava("main", List.of("../outside/Widget.java"))),
        SymbolExtractionDiagnosticCode.INVALID_SCOPE);
    assertDiagnostic(
        extractor.extract(SymbolExtractionRequest.forJava("main", List.of())),
        SymbolExtractionDiagnosticCode.INVALID_SCOPE);
    assertDiagnostic(
        extractor.extract(
            SymbolExtractionRequest.forJava(
                "main", Collections.nCopies(65, "src/main/java/demo/Widget.java"))),
        SymbolExtractionDiagnosticCode.REQUEST_LIMIT_EXCEEDED);
    assertDiagnostic(
        extractor.extract(
            new SymbolExtractionRequest(
                "main", "Java", List.of("src/main/java/demo/Widget.java"), 201)),
        SymbolExtractionDiagnosticCode.REQUEST_LIMIT_EXCEEDED);

    assertEquals(0, launcher.startCount);
  }

  @Test
  void requiresAnExplicitCtagsExecutablePath() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new CtagsSymbolExtractor.Settings("ctags", 64, 200, 1_024, Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CtagsSymbolExtractor.Settings(executable().toString(), 65, 200, 1_024, Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new CtagsSymbolExtractor.Settings(executable().toString(), 64, 201, 1_024, Duration.ofSeconds(5)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CtagsSymbolExtractor.Settings(
                executable().toString(), 64, 200, 1_048_577, Duration.ofSeconds(5)));
  }

  @Test
  void invokesTheFixedShellFreeProfileAndCapturesItsVersion() throws IOException {
    writeFixtureFiles();
    CtagsSymbolExtractor.Settings settings = defaults();
    FakeLauncher launcher =
        new FakeLauncher(profileProcess(), javaLanguageProcess(), process(fixture("symbols.jsonl"), 0));

    SymbolExtractionResult result = extractor(launcher, settings).extract(requestForFixtureFiles());

    assertTrue(result.successful());
    assertEquals(3, launcher.startCount);
    assertEquals(workspace.toRealPath(), launcher.workingDirectories.get(0));
    assertEquals(workspace.toRealPath(), launcher.workingDirectories.get(1));
    assertEquals(workspace.toRealPath(), launcher.workingDirectories.get(2));
    assertEquals(
        List.of(settings.executable(), "--options=NONE", "--version"), launcher.arguments.get(0));
    assertEquals(
        List.of(settings.executable(), "--options=NONE", "--list-languages"),
        launcher.arguments.get(1));
    assertEquals(
        List.of(
            settings.executable(),
            "--options=NONE",
            "--output-format=json",
            "--sort=no",
            "--fields=+n+K+S+e+l+p+z",
            "--languages=Java",
            "--kinds-Java=+acefgimpr",
            "-f",
            "-",
            "./src/main/java/demo/Widget.java",
            "./src/main/java/demo/Zeta.java",
            "./src/main/java/demo/Marker.java",
            "./src/main/java/demo/Mode.java",
            "./src/main/java/demo/Named.java",
            "./src/main/java/demo/Snapshot.java"),
        launcher.arguments.get(2));
    assertFalse(launcher.arguments.get(2).contains("--"));
    assertEquals(
        Optional.of("Universal Ctags 6.2.0 (p6.2.20260920.0)"), result.profile().effectiveVersion());
  }

  @Test
  void normalizesSortsAndKeepsPrimaryMethodIdentityAcrossLineShifts() throws IOException {
    writeFixtureFiles();
    FakeLauncher launcher =
        new FakeLauncher(
            profileProcess(),
            javaLanguageProcess(),
            process(fixture("symbols.jsonl"), 0),
            profileProcess(),
            javaLanguageProcess(),
            process(fixture("symbols-line-shifted.jsonl"), 0));
    CtagsSymbolExtractor extractor = extractor(launcher, defaults());

    SymbolExtractionResult first = extractor.extract(requestForFixtureFiles());
    SymbolExtractionResult shifted =
        extractor.extract(
            SymbolExtractionRequest.forJava("main", List.of("src/main/java/demo/Widget.java")));

    assertTrue(first.successful());
    List<String> order = first.symbols().stream().map(CtagsSymbolExtractorTest::sortKey).toList();
    List<String> sorted = new ArrayList<>(order);
    Collections.sort(sorted);
    assertEquals(sorted, order);

    ExtractedSymbol original = method(first, "render", "(String value)");
    ExtractedSymbol afterShift = method(shifted, "render", "(String value)");
    ExtractedSymbol overload = method(first, "render", "(int value)");
    ExtractedSymbol outerId = field(first, "id", "demo.Widget.id");
    ExtractedSymbol innerId = field(first, "id", "demo.Widget.Inner.id");
    assertEquals("demo.Widget.render", original.qualifiedName().orElseThrow());
    assertEquals(original.identity(), afterShift.identity());
    assertNotEquals(original.identity(), overload.identity());
    assertNotEquals(outerId.identity(), innerId.identity());
    assertEquals("class", original.scopeKind().orElseThrow());
    assertEquals(
        1,
        first.symbols().stream()
            .filter(symbol -> symbol.simpleName().equals("render"))
            .filter(symbol -> symbol.signature().equals(Optional.of("(String value)")))
            .count());
    assertEquals(new SymbolSourceRange(8, Optional.of(10)), original.range());
    assertEquals(new SymbolSourceRange(18, Optional.of(20)), afterShift.range());
    assertEquals(EvidenceType.STATIC_ANALYSIS, original.evidence().type());
    assertEquals("engineering-workspace", original.evidence().source().system());
    assertEquals("main:src/main/java/demo/Widget.java", original.evidence().source().locator());
    assertEquals("src/main/java/demo/Widget.java:L8-L10", original.evidence().location());
  }

  @Test
  void reportsIncompleteMetadataAndKeepsTheDocumentedLocationSensitiveFallback()
      throws IOException {
    writeWorkspaceFile("src/main/java/demo/Widget.java", "class Widget {}\n");
    FakeLauncher launcher =
        new FakeLauncher(
            profileProcess(),
            javaLanguageProcess(),
            process(fixture("incomplete-and-unknown.jsonl"), 0),
            profileProcess(),
            javaLanguageProcess(),
            process(fixture("location-sensitive-line-shifted.jsonl"), 0));
    CtagsSymbolExtractor extractor = extractor(launcher, defaults());
    SymbolExtractionRequest request =
        SymbolExtractionRequest.forJava("main", List.of("src/main/java/demo/Widget.java"));
    SymbolExtractionResult result = extractor.extract(request);
    SymbolExtractionResult shifted = extractor.extract(request);

    assertFalse(result.complete());
    assertTrue(diagnosticCodes(result).contains(SymbolExtractionDiagnosticCode.MISSING_REQUIRED_FIELD));
    assertTrue(diagnosticCodes(result).contains(SymbolExtractionDiagnosticCode.UNKNOWN_KIND));
    assertTrue(
        diagnosticCodes(result).contains(SymbolExtractionDiagnosticCode.LOCATION_SENSITIVE_IDENTITY));
    ExtractedSymbol legacy = method(result, "legacy", null);
    assertTrue(legacy.identity().locationSensitive());
    assertEquals("demo.Widget.legacy", legacy.qualifiedName().orElseThrow());
    assertEquals(new SymbolSourceRange(30, Optional.of(31)), legacy.range());
    assertNotEquals(legacy.identity(), method(shifted, "legacy", null).identity());
  }

  @Test
  void distinguishesNormalEmptyOutputAndProfileOrProcessFailures() throws IOException {
    writeWorkspaceFile("src/main/java/demo/Widget.java", "class Widget {}\n");
    SymbolExtractionRequest request =
        SymbolExtractionRequest.forJava("main", List.of("src/main/java/demo/Widget.java"));

    SymbolExtractionResult empty =
        extractor(new FakeLauncher(profileProcess(), javaLanguageProcess(), process("", 0)), defaults())
            .extract(request);
    assertTrue(empty.successful());
    assertTrue(empty.symbols().isEmpty());

    assertDiagnostic(
        extractor(new FakeLauncher(new IOException("missing")), defaults()).extract(request),
        SymbolExtractionDiagnosticCode.TOOL_UNAVAILABLE);
    assertDiagnostic(
        extractor(new FakeLauncher(process("Exuberant Ctags 5.8\n", 0)), defaults()).extract(request),
        SymbolExtractionDiagnosticCode.INCOMPATIBLE_PROFILE);
    assertDiagnostic(
        extractor(new FakeLauncher(profileProcess(), process("C\n", 0)), defaults()).extract(request),
        SymbolExtractionDiagnosticCode.INCOMPATIBLE_PROFILE);
    assertDiagnostic(
        extractor(
                new FakeLauncher(profileProcess(), javaLanguageProcess(), process("", 2)), defaults())
            .extract(request),
        SymbolExtractionDiagnosticCode.UNEXPECTED_EXIT);
  }

  @Test
  void mapsTimeoutMalformedEncodingAndEscapingFailuresAndCleansUp() throws IOException {
    writeWorkspaceFile("src/main/java/demo/Widget.java", "class Widget {}\n");
    SymbolExtractionRequest request =
        SymbolExtractionRequest.forJava("main", List.of("src/main/java/demo/Widget.java"));

    FakeProcess timedOut = running("{}\n");
    assertDiagnostic(
        extractor(
                new FakeLauncher(profileProcess(), javaLanguageProcess(), timedOut),
                settings(200, 1_024, Duration.ofSeconds(1)))
            .extract(request),
        SymbolExtractionDiagnosticCode.TIMEOUT);
    assertTrue(timedOut.destroyed);
    assertTrue(timedOut.forciblyDestroyed);

    assertDiagnostic(
        extractor(
                new FakeLauncher(profileProcess(), javaLanguageProcess(), process("not-json\n", 0)),
                defaults())
            .extract(request),
        SymbolExtractionDiagnosticCode.MALFORMED_OUTPUT);
    assertDiagnostic(
        extractor(
                new FakeLauncher(
                    profileProcess(), javaLanguageProcess(), process(new byte[] {(byte) 0xc3}, 0)),
                defaults())
            .extract(request),
        SymbolExtractionDiagnosticCode.ENCODING_ERROR);
    String escaped =
        "{\"_type\":\"tag\",\"name\":\"Widget\",\"path\":\"../outside.java\",\"language\":\"Java\",\"line\":1,\"kind\":\"class\"}\n";
    assertDiagnostic(
        extractor(
                new FakeLauncher(profileProcess(), javaLanguageProcess(), process(escaped, 0)), defaults())
            .extract(request),
        SymbolExtractionDiagnosticCode.MALFORMED_OUTPUT);
    writeWorkspaceFile("src/main/java/demo/Other.java", "class Other {}\n");
    String unrequested =
        "{\"_type\":\"tag\",\"name\":\"Other\",\"path\":\"src/main/java/demo/Other.java\",\"language\":\"Java\",\"line\":1,\"kind\":\"class\"}\n";
    assertDiagnostic(
        extractor(
                new FakeLauncher(profileProcess(), javaLanguageProcess(), process(unrequested, 0)),
                defaults())
            .extract(request),
        SymbolExtractionDiagnosticCode.MALFORMED_OUTPUT);
  }

  @Test
  void exposesOutputAndResultTruncationWithoutPersistingCandidates() throws IOException {
    writeFixtureFiles();
    FakeProcess outputLimited = process(fixture("symbols.jsonl"), 0);
    SymbolExtractionResult outputResult =
        extractor(
                new FakeLauncher(profileProcess(), javaLanguageProcess(), outputLimited),
                settings(200, 100, Duration.ofSeconds(1)))
            .extract(requestForFixtureFiles());
    assertFalse(outputResult.complete());
    assertTrue(diagnosticCodes(outputResult).contains(SymbolExtractionDiagnosticCode.OUTPUT_TRUNCATED));
    assertTrue(outputLimited.destroyed);

    String completeLine = "{\"_type\":\"ptag\",\"name\":\"JSON_OUTPUT_VERSION\",\"path\":\"1.0\"}\n";
    String partialLine = "{\"_type\":\"tag\",\"name\":\"é";
    byte[] unicodeOutput = (completeLine + partialLine + "\"}\n").getBytes(StandardCharsets.UTF_8);
    int partialUnicodeLimit =
        completeLine.getBytes(StandardCharsets.UTF_8).length
            + partialLine.getBytes(StandardCharsets.UTF_8).length
            - 1;
    SymbolExtractionResult unicodeTruncated =
        extractor(
                new FakeLauncher(
                    profileProcess(), javaLanguageProcess(), process(unicodeOutput, 0)),
                settings(200, partialUnicodeLimit, Duration.ofSeconds(1)))
            .extract(requestForFixtureFiles());
    assertEquals(
        List.of(SymbolExtractionDiagnosticCode.OUTPUT_TRUNCATED),
        diagnosticCodes(unicodeTruncated));

    SymbolExtractionResult resultLimited =
        extractor(
                new FakeLauncher(
                    profileProcess(), javaLanguageProcess(), process(fixture("symbols.jsonl"), 0)),
                settings(1, 1_024_576, Duration.ofSeconds(1)))
            .extract(requestForFixtureFiles());
    assertFalse(resultLimited.complete());
    assertEquals(1, resultLimited.symbols().size());
    assertEquals(
        List.of(SymbolExtractionDiagnosticCode.RESULT_TRUNCATED), diagnosticCodes(resultLimited));
  }

  private CtagsSymbolExtractor extractor(
      FakeLauncher launcher, CtagsSymbolExtractor.Settings settings) {
    return new CtagsSymbolExtractor(Map.of("main", workspace), settings, launcher);
  }

  private CtagsSymbolExtractor.Settings defaults() {
    return CtagsSymbolExtractor.Settings.defaults(executable().toString());
  }

  private CtagsSymbolExtractor.Settings settings(int maxResults, int maxOutputBytes, Duration timeout) {
    return new CtagsSymbolExtractor.Settings(
        executable().toString(), 64, maxResults, maxOutputBytes, timeout);
  }

  private Path executable() {
    return tempDir.resolve("tools").resolve("ctags.exe").toAbsolutePath();
  }

  private SymbolExtractionRequest requestForFixtureFiles() {
    return SymbolExtractionRequest.forJava("main", fixtureFiles());
  }

  private static List<String> fixtureFiles() {
    return List.of(
        "src/main/java/demo/Widget.java",
        "src/main/java/demo/Zeta.java",
        "src/main/java/demo/Marker.java",
        "src/main/java/demo/Mode.java",
        "src/main/java/demo/Named.java",
        "src/main/java/demo/Snapshot.java");
  }

  private void writeFixtureFiles() throws IOException {
    for (String file : fixtureFiles()) {
      writeWorkspaceFile(file, "package demo;\nclass Placeholder {}\n");
    }
  }

  private void writeWorkspaceFile(String relativePath, String content) throws IOException {
    Path file = workspace.resolve(relativePath);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content, StandardCharsets.UTF_8);
  }

  private static ExtractedSymbol method(
      SymbolExtractionResult result, String simpleName, String signature) {
    return result.symbols().stream()
        .filter(symbol -> symbol.kind() == SymbolKind.METHOD)
        .filter(symbol -> symbol.simpleName().equals(simpleName))
        .filter(symbol -> signature == null || symbol.signature().orElseThrow().equals(signature))
        .findFirst()
        .orElseThrow();
  }

  private static ExtractedSymbol field(
      SymbolExtractionResult result, String simpleName, String qualifiedName) {
    return result.symbols().stream()
        .filter(symbol -> symbol.kind() == SymbolKind.FIELD)
        .filter(symbol -> symbol.simpleName().equals(simpleName))
        .filter(symbol -> symbol.qualifiedName().orElseThrow().equals(qualifiedName))
        .findFirst()
        .orElseThrow();
  }

  private static String sortKey(ExtractedSymbol symbol) {
    return "%s|%010d|%010d|%s|%s|%s"
        .formatted(
            symbol.relativePath(),
            symbol.range().startLine(),
            symbol.range().endLine().orElse(Integer.MAX_VALUE),
            symbol.kind().ctagsName(),
            symbol.qualifiedName().orElse(""),
            symbol.signature().orElse(""));
  }

  private static List<SymbolExtractionDiagnosticCode> diagnosticCodes(
      SymbolExtractionResult result) {
    return result.diagnostics().stream().map(SymbolExtractionDiagnostic::code).toList();
  }

  private static void assertDiagnostic(
      SymbolExtractionResult result, SymbolExtractionDiagnosticCode code) {
    assertFalse(result.complete());
    assertEquals(List.of(code), diagnosticCodes(result));
  }

  private static String fixture(String name) throws IOException {
    try (InputStream input = CtagsSymbolExtractorTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
      if (input == null) {
        throw new IOException("fixture is unavailable: " + name);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static FakeProcess profileProcess() {
    return process("Universal Ctags 6.2.0 (p6.2.20260920.0)\nFeatures: +json\n", 0);
  }

  private static FakeProcess javaLanguageProcess() {
    return process("C\nJava\nJavaProperties\n", 0);
  }

  private static FakeProcess process(String output, int exitCode) {
    return process(output.getBytes(StandardCharsets.UTF_8), exitCode);
  }

  private static FakeProcess process(byte[] output, int exitCode) {
    return new FakeProcess(new ByteArrayInputStream(output), exitCode, true);
  }

  private static FakeProcess running(String output) {
    return new FakeProcess(
        new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), 0, false);
  }

  private static final class FakeLauncher implements CtagsProcessLauncher {
    private final List<Object> outcomes;
    private final List<List<String>> arguments = new ArrayList<>();
    private final List<Path> workingDirectories = new ArrayList<>();
    private int startCount;

    private FakeLauncher(Object... outcomes) {
      this.outcomes = new ArrayList<>(List.of(outcomes));
    }

    @Override
    public CtagsRunningProcess start(List<String> arguments, Path workingDirectory) throws IOException {
      startCount++;
      this.arguments.add(List.copyOf(arguments));
      workingDirectories.add(workingDirectory);
      if (outcomes.isEmpty()) {
        throw new IOException("unexpected process invocation");
      }
      Object outcome = outcomes.remove(0);
      if (outcome instanceof IOException failure) {
        throw failure;
      }
      return (CtagsRunningProcess) outcome;
    }
  }

  private static final class FakeProcess implements CtagsRunningProcess {
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
