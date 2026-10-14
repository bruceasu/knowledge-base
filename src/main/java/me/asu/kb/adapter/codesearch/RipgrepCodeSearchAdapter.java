package me.asu.kb.adapter.codesearch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import me.asu.json.OKJSON;
import me.asu.kb.core.knowledge.Evidence;
import me.asu.kb.core.knowledge.EvidenceType;
import me.asu.kb.core.knowledge.SourceRef;

/**
 * A bounded, shell-free Adapter for literal ripgrep searches in explicitly registered workspaces.
 * The Adapter returns in-memory source evidence only; it does not create relations or write to
 * SQLite, Lucene, HTTP, or CLI paths.
 */
public final class RipgrepCodeSearchAdapter implements ConstrainedCodeSearcher {
  private static final Duration TERMINATION_GRACE = Duration.ofMillis(100);
  private static final Comparator<PendingMatch> MATCH_ORDER =
      Comparator.comparing(PendingMatch::relativePath)
          .thenComparingInt(match -> match.range().startLine())
          .thenComparingInt(match -> match.range().startColumn())
          .thenComparingInt(PendingMatch::occurrence);

  private final Map<String, Path> workspaceRoots;
  private final Settings settings;
  private final RipgrepProcessLauncher launcher;

  /** Uses the documented default ripgrep executable and fixed resource ceilings. */
  public RipgrepCodeSearchAdapter(Map<String, Path> workspaceRoots) {
    this(workspaceRoots, Settings.defaults(), new ProcessBuilderLauncher());
  }

  /** Uses the supplied immutable resource policy without exposing a process seam to callers. */
  public RipgrepCodeSearchAdapter(Map<String, Path> workspaceRoots, Settings settings) {
    this(workspaceRoots, settings, new ProcessBuilderLauncher());
  }

  RipgrepCodeSearchAdapter(
      Map<String, Path> workspaceRoots, Settings settings, RipgrepProcessLauncher launcher) {
    this.workspaceRoots = canonicalWorkspaceRoots(workspaceRoots);
    this.settings = Objects.requireNonNull(settings, "settings");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
  }

  /**
   * Searches one registered workspace using a fixed-string request. Invalid requests return a
   * diagnostic before the Adapter invokes its process launcher.
   */
  public CodeSearchResult search(CodeSearchRequest request) {
    if (settings.executable().isBlank()) {
      return failure(CodeSearchDiagnosticCode.TOOL_UNAVAILABLE, "ripgrep is not configured");
    }
    SearchPlan plan;
    try {
      plan = validate(request);
    } catch (RequestFailure failure) {
      return failure(failure.code(), failure.getMessage());
    }

    RipgrepRunningProcess process;
    try {
      process = launcher.start(argumentsFor(plan), plan.workspace().root());
    } catch (IOException | SecurityException error) {
      return failure(CodeSearchDiagnosticCode.TOOL_UNAVAILABLE, "ripgrep executable is unavailable");
    }

    try {
      long deadlineNanos = deadlineFromNow(settings.timeout());
      RawOutput output = readOutput(process, deadlineNanos);
      if (output.truncated()) {
        terminate(process);
        return parsedResult(plan, output, true);
      }

      if (!waitFor(process, remainingDuration(deadlineNanos))) {
        terminate(process);
        return failure(CodeSearchDiagnosticCode.TIMEOUT, "ripgrep did not finish before the timeout");
      }

      int exitCode = process.exitCode();
      if (exitCode != 0 && exitCode != 1) {
        return failure(
            CodeSearchDiagnosticCode.UNEXPECTED_EXIT,
            "ripgrep exited with code " + exitCode);
      }

      CodeSearchResult parsed = parsedResult(plan, output, false);
      if (exitCode == 0 && parsed.matches().isEmpty()) {
        return failure(
            CodeSearchDiagnosticCode.MALFORMED_OUTPUT,
            "ripgrep completed without match output for a non-empty literal query");
      }
      if (exitCode == 1 && !parsed.matches().isEmpty()) {
        return failure(
            CodeSearchDiagnosticCode.UNEXPECTED_EXIT,
            "ripgrep reported no match after emitting matches");
      }
      return parsed;
    } catch (TimeoutException error) {
      terminate(process);
      return failure(CodeSearchDiagnosticCode.TIMEOUT, "ripgrep output exceeded the timeout");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      terminate(process);
      return failure(CodeSearchDiagnosticCode.TIMEOUT, "ripgrep search was interrupted");
    } catch (OutputFailure error) {
      terminate(process);
      return failure(error.code(), error.getMessage());
    }
  }

  private SearchPlan validate(CodeSearchRequest request) throws RequestFailure {
    if (request == null) {
      throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_REQUEST, "request is required");
    }

    String workspaceId = request.workspaceId();
    if (workspaceId == null
        || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
        || !workspaceRoots.containsKey(workspaceId)) {
      throw new RequestFailure(CodeSearchDiagnosticCode.UNKNOWN_WORKSPACE, "workspace is not registered");
    }
    Workspace workspace = new Workspace(workspaceId, workspaceRoots.get(workspaceId));

    String query = request.literalQuery();
    if (query == null || query.isBlank()) {
      throw new RequestFailure(CodeSearchDiagnosticCode.EMPTY_QUERY, "literal query must not be blank");
    }
    if (query.indexOf('\u0000') >= 0 || query.indexOf('\n') >= 0 || query.indexOf('\r') >= 0) {
      throw new RequestFailure(
          CodeSearchDiagnosticCode.INVALID_REQUEST, "literal query must be a single line");
    }
    if (query.codePointCount(0, query.length()) > settings.maxQueryCharacters()) {
      throw new RequestFailure(
          CodeSearchDiagnosticCode.REQUEST_LIMIT_EXCEEDED, "literal query exceeds the configured limit");
    }

    if (request.relativeScopes().size() > settings.maxScopedPaths()) {
      throw new RequestFailure(
          CodeSearchDiagnosticCode.REQUEST_LIMIT_EXCEEDED, "scope count exceeds the configured limit");
    }
    int contextLines = request.contextLines();
    if (contextLines < 0 || contextLines > settings.maxContextLines()) {
      throw new RequestFailure(
          CodeSearchDiagnosticCode.REQUEST_LIMIT_EXCEEDED, "context exceeds the configured limit");
    }
    int resultLimit = request.maxResults() == 0 ? settings.maxResults() : request.maxResults();
    if (resultLimit < 1 || resultLimit > settings.maxResults()) {
      throw new RequestFailure(
          CodeSearchDiagnosticCode.REQUEST_LIMIT_EXCEEDED, "result limit exceeds the configured limit");
    }

    LinkedHashSet<String> scopes = new LinkedHashSet<>();
    for (String scope : request.relativeScopes()) {
      scopes.add(validateScope(scope, workspace));
    }
    return new SearchPlan(workspace, query, List.copyOf(scopes), contextLines, resultLimit);
  }

  private String validateScope(String rawScope, Workspace workspace) throws RequestFailure {
    if (rawScope == null || rawScope.isBlank()) {
      throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_SCOPE, "scope must not be blank");
    }
    final Path scope;
    try {
      scope = Path.of(rawScope);
    } catch (InvalidPathException error) {
      throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_SCOPE, "scope path is invalid");
    }
    if (scope.isAbsolute() || containsParentSegment(scope)) {
      throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_SCOPE, "scope must stay inside its workspace");
    }
    final Path candidate;
    try {
      candidate = workspace.root().resolve(scope).normalize();
    } catch (IllegalArgumentException error) {
      throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_SCOPE, "scope path is invalid");
    }
    if (!candidate.startsWith(workspace.root()) || !Files.exists(candidate)) {
      throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_SCOPE, "scope is unavailable in its workspace");
    }
    try {
      Path resolved = candidate.toRealPath();
      if (!resolved.startsWith(workspace.root())) {
        throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_SCOPE, "scope escapes its workspace");
      }
      return relativePath(workspace.root(), resolved);
    } catch (IOException error) {
      throw new RequestFailure(CodeSearchDiagnosticCode.INVALID_SCOPE, "scope cannot be resolved");
    }
  }

  private List<String> argumentsFor(SearchPlan plan) {
    List<String> arguments = new ArrayList<>();
    arguments.add(settings.executable());
    arguments.add("--json");
    arguments.add("--fixed-strings");
    arguments.add("--line-number");
    arguments.add("--column");
    arguments.add("--context");
    arguments.add(Integer.toString(plan.contextLines()));
    arguments.add("--");
    arguments.add(plan.literalQuery());
    if (plan.relativeScopes().isEmpty()) {
      arguments.add(".");
    } else {
      arguments.addAll(plan.relativeScopes());
    }
    return List.copyOf(arguments);
  }

  private RawOutput readOutput(RipgrepRunningProcess process, long deadlineNanos)
      throws TimeoutException, InterruptedException, OutputFailure {
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "ripgrep-code-search-output");
          thread.setDaemon(true);
          return thread;
        };
    ExecutorService reader = Executors.newSingleThreadExecutor(factory);
    try {
      Future<RawOutput> future = reader.submit(() -> readBounded(process.standardOutput()));
      try {
        return future.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof OutputFailure failure) {
          throw failure;
        }
        throw new OutputFailure(
            CodeSearchDiagnosticCode.UNEXPECTED_EXIT, "ripgrep output could not be read");
      }
    } finally {
      reader.shutdownNow();
    }
  }

  private RawOutput readBounded(InputStream stream) throws OutputFailure {
    try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8_192];
      while (true) {
        int read = input.read(buffer);
        if (read < 0) {
          return new RawOutput(output.toByteArray(), false);
        }
        int remaining = settings.maxOutputBytes() - output.size();
        if (read > remaining) {
          output.write(buffer, 0, Math.max(remaining, 0));
          return new RawOutput(output.toByteArray(), true);
        }
        output.write(buffer, 0, read);
        if (output.size() == settings.maxOutputBytes()) {
          int extra = input.read();
          return new RawOutput(output.toByteArray(), extra >= 0);
        }
      }
    } catch (IOException error) {
      throw new OutputFailure(
          CodeSearchDiagnosticCode.UNEXPECTED_EXIT, "ripgrep output could not be read");
    }
  }

  private CodeSearchResult parsedResult(SearchPlan plan, RawOutput output, boolean outputTruncated)
      throws OutputFailure {
    byte[] completeLines = outputTruncated ? completeLines(output.bytes()) : output.bytes();
    String jsonLines = decodeUtf8(completeLines);
    ParsedMatches parsed = parseMatches(jsonLines, plan);
    List<PendingMatch> ordered = new ArrayList<>(parsed.matches());
    ordered.sort(MATCH_ORDER);

    List<CodeSearchDiagnostic> diagnostics = new ArrayList<>();
    boolean complete = true;
    if (outputTruncated) {
      diagnostics.add(
          new CodeSearchDiagnostic(
              CodeSearchDiagnosticCode.OUTPUT_TRUNCATED, "ripgrep output exceeded the configured limit"));
      complete = false;
    }
    if (parsed.excerptTruncated()) {
      diagnostics.add(
          new CodeSearchDiagnostic(
              CodeSearchDiagnosticCode.EXCERPT_TRUNCATED, "one or more excerpts exceeded the configured limit"));
      complete = false;
    }
    if (ordered.size() > plan.resultLimit()) {
      ordered = new ArrayList<>(ordered.subList(0, plan.resultLimit()));
      diagnostics.add(
          new CodeSearchDiagnostic(
              CodeSearchDiagnosticCode.RESULT_TRUNCATED, "matches exceeded the requested result limit"));
      complete = false;
    }

    List<CodeSearchMatch> matches = new ArrayList<>(ordered.size());
    for (PendingMatch match : ordered) {
      matches.add(match.match());
    }
    return new CodeSearchResult(matches, diagnostics, complete);
  }

  private ParsedMatches parseMatches(String jsonLines, SearchPlan plan) throws OutputFailure {
    Map<String, FileLines> fileLines = new LinkedHashMap<>();
    List<ParsedOccurrence> occurrences = new ArrayList<>();
    int occurrenceIndex = 0;
    for (String line : jsonLines.split("\\R", -1)) {
      if (line.isBlank()) {
        continue;
      }
      Map<String, Object> event = jsonObject(line);
      String type = requiredString(event.get("type"), "event type");
      if (!"match".equals(type) && !"context".equals(type)) {
        continue;
      }
      Map<String, Object> data = requiredMap(event.get("data"), "event data");
      String path = normalizedOutputPath(readText(data.get("path"), "path"), plan.workspace());
      int lineNumber = requiredPositiveInt(data.get("line_number"), "line number");
      String text = stripLineEnding(readText(data.get("lines"), "lines"));
      FileLines lines = fileLines.computeIfAbsent(path, ignored -> new FileLines());
      lines.add(lineNumber, text);
      if (!"match".equals(type)) {
        continue;
      }

      List<Object> submatches = requiredList(data.get("submatches"), "submatches");
      if (submatches.isEmpty()) {
        throw malformed("match event has no submatches");
      }
      for (Object submatchValue : submatches) {
        Map<String, Object> submatch = requiredMap(submatchValue, "submatch");
        int startByte = requiredNonNegativeInt(submatch.get("start"), "submatch start");
        int endByte = requiredNonNegativeInt(submatch.get("end"), "submatch end");
        if (endByte <= startByte) {
          throw malformed("ripgrep emitted an invalid match range");
        }
        String matchedText = readText(submatch.get("match"), "submatch text");
        if (matchedText.isBlank()) {
          throw malformed("ripgrep emitted an empty match");
        }
        CodeSearchRange range =
            new CodeSearchRange(
                lineNumber,
                unicodeColumn(text, startByte),
                lineNumber,
                unicodeColumn(text, endByte));
        occurrences.add(new ParsedOccurrence(path, lineNumber, range, matchedText, occurrenceIndex++));
      }
    }

    List<PendingMatch> matches = new ArrayList<>(occurrences.size());
    boolean excerptTruncated = false;
    for (ParsedOccurrence occurrence : occurrences) {
      String excerpt = fileLines.get(occurrence.relativePath()).excerpt(occurrence.lineNumber(), plan.contextLines());
      BoundedText bounded = boundedExcerpt(excerpt);
      excerptTruncated |= bounded.truncated();
      Evidence evidence =
          new Evidence(
              EvidenceType.SOURCE_TEXT,
              SourceRef.of(
                  "engineering-workspace",
                  plan.workspace().id() + ":" + occurrence.relativePath()),
              evidenceLocation(occurrence.relativePath(), occurrence.range()),
              1.0d);
      CodeSearchMatch match =
          new CodeSearchMatch(
              occurrence.relativePath(),
              occurrence.range(),
              bounded.text(),
              bounded.truncated(),
              occurrence.matchedText(),
              evidence);
      matches.add(new PendingMatch(match, occurrence.occurrence()));
    }
    return new ParsedMatches(matches, excerptTruncated);
  }

  private String normalizedOutputPath(String rawPath, Workspace workspace) throws OutputFailure {
    if (rawPath == null || rawPath.isBlank()) {
      throw malformed("ripgrep emitted an invalid path");
    }
    final Path path;
    try {
      path = Path.of(rawPath);
    } catch (InvalidPathException error) {
      throw malformed("ripgrep emitted an invalid path");
    }
    if (path.isAbsolute() || containsParentSegment(path)) {
      throw malformed("ripgrep emitted a path outside the workspace");
    }
    final Path candidate;
    try {
      candidate = workspace.root().resolve(path).normalize();
    } catch (IllegalArgumentException error) {
      throw malformed("ripgrep emitted an invalid path");
    }
    if (!candidate.startsWith(workspace.root())) {
      throw malformed("ripgrep emitted a path outside the workspace");
    }
    try {
      Path resolved = candidate.toRealPath();
      if (!resolved.startsWith(workspace.root())) {
        throw malformed("ripgrep emitted a path outside the workspace");
      }
      return relativePath(workspace.root(), resolved);
    } catch (IOException error) {
      throw malformed("ripgrep emitted an unavailable path");
    }
  }

  private BoundedText boundedExcerpt(String excerpt) {
    int codePoints = excerpt.codePointCount(0, excerpt.length());
    if (codePoints <= settings.maxExcerptCharacters()) {
      return new BoundedText(excerpt, false);
    }
    int retained = Math.max(0, settings.maxExcerptCharacters() - 1);
    int endIndex = excerpt.offsetByCodePoints(0, retained);
    return new BoundedText(excerpt.substring(0, endIndex) + "…", true);
  }

  private static String evidenceLocation(String relativePath, CodeSearchRange range) {
    return relativePath
        + ":L"
        + range.startLine()
        + ":C"
        + range.startColumn()
        + "-L"
        + range.endLine()
        + ":C"
        + range.endColumn();
  }

  private static int unicodeColumn(String text, int byteOffset) throws OutputFailure {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (byteOffset < 0 || byteOffset > bytes.length) {
      throw malformed("ripgrep emitted an invalid match range");
    }
    String prefix = decodeUtf8(java.util.Arrays.copyOf(bytes, byteOffset));
    return prefix.codePointCount(0, prefix.length()) + 1;
  }

  private static String decodeUtf8(byte[] bytes) throws OutputFailure {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new OutputFailure(CodeSearchDiagnosticCode.ENCODING_ERROR, "ripgrep output is not valid UTF-8");
    }
  }

  private static byte[] completeLines(byte[] bytes) {
    int lastNewline = -1;
    for (int index = bytes.length - 1; index >= 0; index--) {
      if (bytes[index] == '\n') {
        lastNewline = index;
        break;
      }
    }
    if (lastNewline < 0) {
      return new byte[0];
    }
    return java.util.Arrays.copyOf(bytes, lastNewline + 1);
  }

  private static Map<String, Object> jsonObject(String line) throws OutputFailure {
    try {
      Map<String, Object> parsed = OKJSON.toMap(line, OKJSON.OPTIONS_STRICT_POLICY);
      if (parsed == null) {
        throw malformed("ripgrep emitted malformed JSON");
      }
      return parsed;
    } catch (RuntimeException error) {
      throw malformed("ripgrep emitted malformed JSON");
    }
  }

  private static Map<String, Object> requiredMap(Object value, String label) throws OutputFailure {
    if (!(value instanceof Map<?, ?> raw)) {
      throw malformed("ripgrep omitted " + label);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : raw.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw malformed("ripgrep emitted an invalid " + label);
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static List<Object> requiredList(Object value, String label) throws OutputFailure {
    if (!(value instanceof List<?> list)) {
      throw malformed("ripgrep omitted " + label);
    }
    return new ArrayList<>(list);
  }

  private static String requiredString(Object value, String label) throws OutputFailure {
    if (!(value instanceof String text) || text.isBlank()) {
      throw malformed("ripgrep emitted an invalid " + label);
    }
    return text;
  }

  private static String readText(Object value, String label) throws OutputFailure {
    Map<String, Object> content = requiredMap(value, label);
    Object text = content.get("text");
    if (text instanceof String valueText) {
      return valueText;
    }
    Object encoded = content.get("bytes");
    if (!(encoded instanceof String encodedText)) {
      throw malformed("ripgrep omitted " + label + " text");
    }
    try {
      return decodeUtf8(Base64.getDecoder().decode(encodedText));
    } catch (IllegalArgumentException error) {
      throw new OutputFailure(
          CodeSearchDiagnosticCode.ENCODING_ERROR, "ripgrep emitted invalid encoded text");
    }
  }

  private static int requiredPositiveInt(Object value, String label) throws OutputFailure {
    int parsed = requiredNonNegativeInt(value, label);
    if (parsed < 1) {
      throw malformed("ripgrep emitted an invalid " + label);
    }
    return parsed;
  }

  private static int requiredNonNegativeInt(Object value, String label) throws OutputFailure {
    if (!(value instanceof Number number)) {
      throw malformed("ripgrep emitted an invalid " + label);
    }
    long parsed = number.longValue();
    if (parsed < 0 || parsed > Integer.MAX_VALUE || parsed != number.doubleValue()) {
      throw malformed("ripgrep emitted an invalid " + label);
    }
    return (int) parsed;
  }

  private static String stripLineEnding(String text) {
    int end = text.length();
    while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == '\r')) {
      end--;
    }
    return text.substring(0, end);
  }

  private static boolean containsParentSegment(Path path) {
    for (Path segment : path) {
      if ("..".equals(segment.toString())) {
        return true;
      }
    }
    return false;
  }

  private static String relativePath(Path root, Path path) {
    String value = root.relativize(path).toString().replace('\\', '/');
    return value.isEmpty() ? "." : value;
  }

  private static Map<String, Path> canonicalWorkspaceRoots(Map<String, Path> workspaceRoots) {
    Objects.requireNonNull(workspaceRoots, "workspaceRoots");
    if (workspaceRoots.isEmpty()) {
      throw new IllegalArgumentException("workspaceRoots must not be empty");
    }
    Map<String, Path> canonical = new LinkedHashMap<>();
    for (Map.Entry<String, Path> entry : workspaceRoots.entrySet()) {
      String workspaceId = entry.getKey();
      if (workspaceId == null || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
        throw new IllegalArgumentException("workspace id is invalid");
      }
      Path root = Objects.requireNonNull(entry.getValue(), "workspace root");
      try {
        Path resolved = root.toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(resolved)) {
          throw new IllegalArgumentException("workspace root must be a directory");
        }
        canonical.put(workspaceId, resolved);
      } catch (IOException error) {
        throw new IllegalArgumentException("workspace root cannot be resolved", error);
      }
    }
    return Collections.unmodifiableMap(canonical);
  }

  private static CodeSearchResult failure(CodeSearchDiagnosticCode code, String detail) {
    return new CodeSearchResult(List.of(), List.of(new CodeSearchDiagnostic(code, detail)), false);
  }

  private static OutputFailure malformed(String detail) {
    return new OutputFailure(CodeSearchDiagnosticCode.MALFORMED_OUTPUT, detail);
  }

  private static long deadlineFromNow(Duration timeout) {
    long nanos = timeout.toNanos();
    long now = System.nanoTime();
    return now > Long.MAX_VALUE - nanos ? Long.MAX_VALUE : now + nanos;
  }

  private static long remainingNanos(long deadlineNanos) throws TimeoutException {
    long remaining = deadlineNanos - System.nanoTime();
    if (remaining <= 0) {
      throw new TimeoutException();
    }
    return remaining;
  }

  private static boolean waitFor(RipgrepRunningProcess process, Duration timeout)
      throws InterruptedException {
    return process.waitFor(timeout);
  }

  private static Duration remainingDuration(long deadlineNanos) throws TimeoutException {
    return Duration.ofNanos(remainingNanos(deadlineNanos));
  }

  private static void terminate(RipgrepRunningProcess process) {
    process.destroy();
    try {
      if (!process.waitFor(TERMINATION_GRACE)) {
        process.destroyForcibly();
        process.waitFor(TERMINATION_GRACE);
      }
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  /** Immutable, bounded Adapter policy. Callers may not override these maxima in a request. */
  public record Settings(
      String executable,
      int maxQueryCharacters,
      int maxScopedPaths,
      int maxContextLines,
      int maxResults,
      int maxExcerptCharacters,
      int maxOutputBytes,
      Duration timeout) {

    public Settings {
      executable = executable == null ? "" : executable;
      if (maxQueryCharacters < 1
          || maxScopedPaths < 1
          || maxContextLines < 0
          || maxResults < 1
          || maxExcerptCharacters < 1
          || maxOutputBytes < 1) {
        throw new IllegalArgumentException("search limits must be positive");
      }
      Objects.requireNonNull(timeout, "timeout");
      if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
        throw new IllegalArgumentException("timeout must be between zero and sixty seconds");
      }
    }

    public static Settings defaults() {
      return new Settings("rg", 512, 64, 3, 200, 4_096, 1_048_576, Duration.ofSeconds(5));
    }

    public static Settings unavailable() {
      return new Settings("", 512, 64, 3, 200, 4_096, 1_048_576, Duration.ofSeconds(5));
    }
  }

  private record Workspace(String id, Path root) {}

  private record SearchPlan(
      Workspace workspace,
      String literalQuery,
      List<String> relativeScopes,
      int contextLines,
      int resultLimit) {}

  private record RawOutput(byte[] bytes, boolean truncated) {}

  private record ParsedOccurrence(
      String relativePath,
      int lineNumber,
      CodeSearchRange range,
      String matchedText,
      int occurrence) {}

  private record PendingMatch(CodeSearchMatch match, int occurrence) {
    private String relativePath() {
      return match.relativePath();
    }

    private CodeSearchRange range() {
      return match.range();
    }
  }

  private record ParsedMatches(List<PendingMatch> matches, boolean excerptTruncated) {}

  private record BoundedText(String text, boolean truncated) {}

  private static final class FileLines {
    private final TreeMap<Integer, String> lines = new TreeMap<>();

    private void add(int lineNumber, String text) {
      lines.putIfAbsent(lineNumber, text);
    }

    private String excerpt(int lineNumber, int contextLines) {
      StringBuilder excerpt = new StringBuilder();
      int firstLine = Math.max(1, lineNumber - contextLines);
      int lastLine =
          lineNumber > Integer.MAX_VALUE - contextLines
              ? Integer.MAX_VALUE
              : lineNumber + contextLines;
      for (Map.Entry<Integer, String> entry :
          lines.subMap(firstLine, true, lastLine, true).entrySet()) {
        if (!excerpt.isEmpty()) {
          excerpt.append('\n');
        }
        excerpt.append(entry.getValue());
      }
      return excerpt.toString();
    }
  }

  private static final class RequestFailure extends Exception {
    private final CodeSearchDiagnosticCode code;

    private RequestFailure(CodeSearchDiagnosticCode code, String detail) {
      super(detail);
      this.code = code;
    }

    private CodeSearchDiagnosticCode code() {
      return code;
    }
  }

  private static final class OutputFailure extends Exception {
    private final CodeSearchDiagnosticCode code;

    private OutputFailure(CodeSearchDiagnosticCode code, String detail) {
      super(detail);
      this.code = code;
    }

    private CodeSearchDiagnosticCode code() {
      return code;
    }
  }

  private static final class ProcessBuilderLauncher implements RipgrepProcessLauncher {
    @Override
    public RipgrepRunningProcess start(List<String> arguments, Path workingDirectory) throws IOException {
      ProcessBuilder builder = new ProcessBuilder(arguments);
      builder.directory(workingDirectory.toFile());
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      return new ProcessBackedRipgrepProcess(builder.start());
    }
  }

  private static final class ProcessBackedRipgrepProcess implements RipgrepRunningProcess {
    private final Process process;

    private ProcessBackedRipgrepProcess(Process process) {
      this.process = process;
    }

    @Override
    public InputStream standardOutput() {
      return process.getInputStream();
    }

    @Override
    public boolean waitFor(Duration timeout) throws InterruptedException {
      return process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int exitCode() {
      return process.exitValue();
    }

    @Override
    public void destroy() {
      process.destroy();
    }

    @Override
    public void destroyForcibly() {
      process.destroyForcibly();
    }
  }
}
