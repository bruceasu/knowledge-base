package me.asu.kb.adapter.symbol;

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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * A bounded, shell-free Adapter for the fixed Universal Ctags Java JSON profile.
 *
 * <p>The Adapter returns in-memory static-analysis candidates only. It does not write SQLite or
 * Lucene data, create relations, or alter HTTP/CLI/search paths.
 */
public final class CtagsSymbolExtractor {
  /** The versioned profile encoded by this Adapter. */
  public static final String PROFILE_ID = "universal-ctags-json-java-v1";

  private static final Duration TERMINATION_GRACE = Duration.ofMillis(100);
  private static final int PROFILE_OUTPUT_BYTES = 16_384;
  private static final int MAX_FILES = 64;
  private static final int MAX_RESULTS = 200;
  private static final int MAX_OUTPUT_BYTES = 1_048_576;
  private static final Comparator<ExtractedSymbol> SYMBOL_ORDER =
      Comparator.comparing(ExtractedSymbol::relativePath)
          .thenComparingInt(symbol -> symbol.range().startLine())
          .thenComparingInt(symbol -> symbol.range().endLine().orElse(Integer.MAX_VALUE))
          .thenComparing(symbol -> symbol.kind().ctagsName())
          .thenComparing(symbol -> symbol.qualifiedName().orElse(""))
          .thenComparing(symbol -> symbol.signature().orElse(""));

  private final Map<String, Path> workspaceRoots;
  private final Settings settings;
  private final CtagsProcessLauncher launcher;

  /** Uses the supplied explicit executable and immutable resource policy. */
  public CtagsSymbolExtractor(Map<String, Path> workspaceRoots, Settings settings) {
    this(workspaceRoots, settings, new ProcessBuilderLauncher());
  }

  CtagsSymbolExtractor(
      Map<String, Path> workspaceRoots, Settings settings, CtagsProcessLauncher launcher) {
    this.workspaceRoots = canonicalWorkspaceRoots(workspaceRoots);
    this.settings = Objects.requireNonNull(settings, "settings");
    this.launcher = Objects.requireNonNull(launcher, "launcher");
  }

  /**
   * Extracts candidates from explicit Java files in one registered workspace.
   *
   * <p>All request and real-path checks run before the Adapter invokes its launcher.
   */
  public SymbolExtractionResult extract(SymbolExtractionRequest request) {
    if (settings.executable().isBlank()) {
      return failure(
          unverifiedProfile(),
          new AdapterFailure(
              SymbolExtractionDiagnosticCode.TOOL_UNAVAILABLE,
              "ctags is not configured"));
    }
    final ExtractionPlan plan;
    try {
      plan = validate(request);
    } catch (AdapterFailure failure) {
      return failure(unverifiedProfile(), failure);
    }

    final CtagsToolProfile profile;
    long deadlineNanos = deadlineFromNow(settings.timeout());
    try {
      profile = probeProfile(plan.workspace(), deadlineNanos);
    } catch (AdapterFailure failure) {
      return failure(unverifiedProfile(), failure);
    }

    return runExtraction(plan, profile, deadlineNanos);
  }

  private ExtractionPlan validate(SymbolExtractionRequest request) throws AdapterFailure {
    if (request == null) {
      throw new AdapterFailure(SymbolExtractionDiagnosticCode.INVALID_REQUEST, "request is required");
    }

    String workspaceId = request.workspaceId();
    if (workspaceId == null
        || !workspaceId.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
        || !workspaceRoots.containsKey(workspaceId)) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.UNKNOWN_WORKSPACE, "workspace is not registered");
    }
    Workspace workspace = new Workspace(workspaceId, workspaceRoots.get(workspaceId));

    if (!SymbolLanguage.JAVA.ctagsName().equals(request.language())) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.UNSUPPORTED_LANGUAGE,
          "only the Java Ctags profile is supported");
    }
    if (request.relativeJavaFiles().isEmpty()) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.INVALID_SCOPE, "at least one Java file is required");
    }
    if (request.relativeJavaFiles().size() > settings.maxFiles()) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.REQUEST_LIMIT_EXCEEDED,
          "Java file count exceeds the configured limit");
    }

    int resultLimit = request.maxResults() == 0 ? settings.maxResults() : request.maxResults();
    if (resultLimit < 1 || resultLimit > settings.maxResults()) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.REQUEST_LIMIT_EXCEEDED,
          "result limit exceeds the configured limit");
    }

    LinkedHashSet<String> files = new LinkedHashSet<>();
    for (String rawFile : request.relativeJavaFiles()) {
      files.add(validateFile(rawFile, workspace));
    }
    if (files.isEmpty()) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.INVALID_SCOPE, "at least one Java file is required");
    }
    return new ExtractionPlan(workspace, List.copyOf(files), resultLimit);
  }

  private String validateFile(String rawFile, Workspace workspace) throws AdapterFailure {
    if (rawFile == null || rawFile.isBlank()) {
      throw new AdapterFailure(SymbolExtractionDiagnosticCode.INVALID_SCOPE, "Java file must not be blank");
    }
    final Path file;
    try {
      file = Path.of(rawFile);
    } catch (InvalidPathException error) {
      throw new AdapterFailure(SymbolExtractionDiagnosticCode.INVALID_SCOPE, "Java file path is invalid");
    }
    if (file.isAbsolute() || containsParentSegment(file)) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.INVALID_SCOPE, "Java file must stay inside its workspace");
    }
    final Path candidate;
    try {
      candidate = workspace.root().resolve(file).normalize();
    } catch (IllegalArgumentException error) {
      throw new AdapterFailure(SymbolExtractionDiagnosticCode.INVALID_SCOPE, "Java file path is invalid");
    }
    if (!candidate.startsWith(workspace.root()) || !Files.isRegularFile(candidate)) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.INVALID_SCOPE, "Java file is unavailable in its workspace");
    }
    try {
      Path resolved = candidate.toRealPath();
      if (!resolved.startsWith(workspace.root()) || !Files.isRegularFile(resolved)) {
        throw new AdapterFailure(
            SymbolExtractionDiagnosticCode.INVALID_SCOPE, "Java file escapes its workspace");
      }
      String relative = relativePath(workspace.root(), resolved);
      if (!relative.endsWith(".java")) {
        throw new AdapterFailure(
            SymbolExtractionDiagnosticCode.UNSUPPORTED_LANGUAGE,
            "only .java source files are supported");
      }
      return relative;
    } catch (IOException error) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.INVALID_SCOPE, "Java file cannot be resolved");
    }
  }

  private CtagsToolProfile probeProfile(Workspace workspace, long deadlineNanos)
      throws AdapterFailure {
    String versionOutput =
        probeOutput(
            workspace,
            List.of(settings.executable(), "--options=NONE", "--version"),
            deadlineNanos,
            "version");
    String version = firstNonBlankLine(versionOutput);
    if (version == null
        || !version.startsWith("Universal Ctags")
        || !versionOutput.contains("+json")) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.INCOMPATIBLE_PROFILE,
          "ctags must be Universal Ctags with JSON output support");
    }
    String languages =
        probeOutput(
            workspace,
            List.of(settings.executable(), "--options=NONE", "--list-languages"),
            deadlineNanos,
            "language");
    if (!supportsJava(languages)) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.INCOMPATIBLE_PROFILE,
          "ctags profile does not support the Java parser");
    }
    return new CtagsToolProfile(PROFILE_ID, Optional.of(version));
  }

  private String probeOutput(
      Workspace workspace, List<String> arguments, long deadlineNanos, String probeName)
      throws AdapterFailure {
    final CtagsRunningProcess process;
    try {
      remainingNanos(deadlineNanos);
      process = launcher.start(arguments, workspace.root());
    } catch (TimeoutException error) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.TIMEOUT,
          "ctags " + probeName + " probe exceeded the total timeout");
    } catch (IOException | SecurityException error) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.TOOL_UNAVAILABLE, "ctags executable is unavailable");
    }

    try {
      RawOutput output = readOutput(process, deadlineNanos, PROFILE_OUTPUT_BYTES);
      if (output.truncated()) {
        terminate(process);
        throw new AdapterFailure(
            SymbolExtractionDiagnosticCode.INCOMPATIBLE_PROFILE,
            "ctags " + probeName + " output exceeded the profile limit");
      }
      if (!waitFor(process, remainingDuration(deadlineNanos))) {
        terminate(process);
        throw new AdapterFailure(
            SymbolExtractionDiagnosticCode.TIMEOUT,
            "ctags " + probeName + " probe exceeded the total timeout");
      }
      if (process.exitCode() != 0) {
        throw new AdapterFailure(
            SymbolExtractionDiagnosticCode.UNEXPECTED_EXIT,
            "ctags " + probeName + " probe exited with code " + process.exitCode());
      }
      return decodeUtf8(output.bytes());
    } catch (TimeoutException error) {
      terminate(process);
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.TIMEOUT,
          "ctags " + probeName + " probe output exceeded the total timeout");
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      terminate(process);
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.TIMEOUT, "ctags " + probeName + " probe was interrupted");
    } catch (AdapterFailure failure) {
      terminate(process);
      throw failure;
    }
  }

  private SymbolExtractionResult runExtraction(
      ExtractionPlan plan, CtagsToolProfile profile, long deadlineNanos) {
    final CtagsRunningProcess process;
    try {
      remainingNanos(deadlineNanos);
      process = launcher.start(argumentsFor(plan), plan.workspace().root());
    } catch (TimeoutException error) {
      return failure(
          profile,
          new AdapterFailure(
              SymbolExtractionDiagnosticCode.TIMEOUT,
              "ctags extraction did not start before the total timeout"));
    } catch (IOException | SecurityException error) {
      return failure(
          profile,
          new AdapterFailure(
              SymbolExtractionDiagnosticCode.TOOL_UNAVAILABLE, "ctags executable is unavailable"));
    }

    try {
      RawOutput output = readOutput(process, deadlineNanos, settings.maxOutputBytes());
      if (output.truncated()) {
        terminate(process);
        return parsedResult(plan, profile, output, true);
      }
      if (!waitFor(process, remainingDuration(deadlineNanos))) {
        terminate(process);
        return failure(
            profile,
            new AdapterFailure(
                SymbolExtractionDiagnosticCode.TIMEOUT, "ctags did not finish before the timeout"));
      }
      if (process.exitCode() != 0) {
        return failure(
            profile,
            new AdapterFailure(
                SymbolExtractionDiagnosticCode.UNEXPECTED_EXIT,
                "ctags exited with code " + process.exitCode()));
      }
      return parsedResult(plan, profile, output, false);
    } catch (TimeoutException error) {
      terminate(process);
      return failure(
          profile,
          new AdapterFailure(
              SymbolExtractionDiagnosticCode.TIMEOUT, "ctags output exceeded the timeout"));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      terminate(process);
      return failure(
          profile,
          new AdapterFailure(
              SymbolExtractionDiagnosticCode.TIMEOUT, "ctags extraction was interrupted"));
    } catch (AdapterFailure failure) {
      terminate(process);
      return failure(profile, failure);
    }
  }

  private List<String> argumentsFor(ExtractionPlan plan) {
    List<String> arguments = new ArrayList<>();
    arguments.add(settings.executable());
    arguments.add("--options=NONE");
    arguments.add("--output-format=json");
    arguments.add("--sort=no");
    arguments.add("--fields=+n+K+S+e+l+p+z");
    arguments.add("--languages=Java");
    arguments.add("--kinds-Java=+acefgimpr");
    arguments.add("-f");
    arguments.add("-");
    for (String relativeFile : plan.relativeJavaFiles()) {
      arguments.add("./" + relativeFile);
    }
    return List.copyOf(arguments);
  }

  private SymbolExtractionResult parsedResult(
      ExtractionPlan plan, CtagsToolProfile profile, RawOutput output, boolean outputTruncated) {
    try {
      return parseOutput(plan, profile, output, outputTruncated);
    } catch (AdapterFailure failure) {
      return failure(profile, failure);
    }
  }

  private SymbolExtractionResult parseOutput(
      ExtractionPlan plan, CtagsToolProfile profile, RawOutput output, boolean outputTruncated)
      throws AdapterFailure {
    byte[] parseableBytes = outputTruncated ? completeLinePrefix(output.bytes()) : output.bytes();
    String parseable = decodeUtf8(parseableBytes);
    ParseAccumulator parsed = new ParseAccumulator(!outputTruncated);
    if (outputTruncated) {
      parsed.diagnostics().add(
          new SymbolExtractionDiagnostic(
              SymbolExtractionDiagnosticCode.OUTPUT_TRUNCATED,
              "ctags stdout exceeded the configured limit"));
    }

    for (String rawLine : parseable.split("\\n", -1)) {
      String line = stripLineEnding(rawLine);
      if (line.isBlank()) {
        continue;
      }
      final Map<String, Object> record;
      try {
        record = jsonObject(line);
      } catch (RecordFailure failure) {
        parsed.addFailure(failure);
        continue;
      }
      Object typeValue = record.get("_type");
      if (!(typeValue instanceof String type) || type.isBlank()) {
        parsed.addFailure(
            new RecordFailure(
                SymbolExtractionDiagnosticCode.MISSING_REQUIRED_FIELD,
                "ctags record is missing _type"));
        continue;
      }
      if (!"tag".equals(type)) {
        continue;
      }
      try {
        parsed.tags().add(parseTag(record, plan));
      } catch (RecordFailure failure) {
        parsed.addFailure(failure);
      }
    }

    Map<String, String> packageNames = new LinkedHashMap<>();
    for (ParsedTag tag : parsed.tags()) {
      if (tag.kind() == SymbolKind.PACKAGE) {
        packageNames.putIfAbsent(tag.relativePath(), tag.name());
      }
    }

    LinkedHashSet<ExtractedSymbol> unique = new LinkedHashSet<>();
    for (ParsedTag tag : parsed.tags()) {
      NormalizedSymbol normalized = toExtractedSymbol(tag, plan.workspace(), packageNames);
      if (unique.add(normalized.symbol()) && normalized.symbol().identity().locationSensitive()) {
        parsed.diagnostics().add(
            new SymbolExtractionDiagnostic(
                SymbolExtractionDiagnosticCode.LOCATION_SENSITIVE_IDENTITY,
                "ctags metadata requires a location-sensitive identity fallback"));
      }
    }
    List<ExtractedSymbol> symbols = new ArrayList<>(unique);
    symbols.sort(SYMBOL_ORDER);
    if (symbols.size() > plan.resultLimit()) {
      symbols = new ArrayList<>(symbols.subList(0, plan.resultLimit()));
      parsed.diagnostics().add(
          new SymbolExtractionDiagnostic(
              SymbolExtractionDiagnosticCode.RESULT_TRUNCATED,
              "ctags candidates exceeded the requested result limit"));
      parsed.setComplete(false);
    }
    return new SymbolExtractionResult(symbols, profile, parsed.diagnostics(), parsed.complete());
  }

  private ParsedTag parseTag(Map<String, Object> record, ExtractionPlan plan) throws RecordFailure {
    String name = requiredString(record, "name");
    String relativePath = normalizedOutputPath(requiredString(record, "path"), plan);
    String languageName = requiredString(record, "language");
    SymbolLanguage language =
        SymbolLanguage.fromCtagsName(languageName)
            .orElseThrow(
                () ->
                    new RecordFailure(
                        SymbolExtractionDiagnosticCode.MALFORMED_OUTPUT,
                        "ctags emitted an unsupported record language"));
    String kindName = requiredString(record, "kind");
    SymbolKind kind =
        SymbolKind.fromCtagsName(kindName)
            .orElseThrow(
                () ->
                    new RecordFailure(
                        SymbolExtractionDiagnosticCode.UNKNOWN_KIND,
                        "ctags emitted an unsupported Java kind: " + kindName));
    int line = requiredPositiveInt(record, "line");
    Optional<Integer> endLine = optionalEndLine(record, line);
    Optional<String> signature = optionalString(record, "signature");
    Optional<String> scope = optionalString(record, "scope");
    Optional<String> scopeKind = optionalString(record, "scopeKind");
    return new ParsedTag(
        relativePath, language, kind, name, line, endLine, signature, scope, scopeKind);
  }

  private NormalizedSymbol toExtractedSymbol(
      ParsedTag tag, Workspace workspace, Map<String, String> packageNames) {
    SymbolSourceRange range = new SymbolSourceRange(tag.line(), tag.endLine());
    String packageName = packageNames.get(tag.relativePath());
    Optional<String> qualifiedName = qualifiedName(tag, packageName);
    SymbolIdentity identity = symbolIdentity(workspace, tag, qualifiedName, range);
    Evidence evidence =
        new Evidence(
            EvidenceType.STATIC_ANALYSIS,
            SourceRef.of("engineering-workspace", workspace.id() + ":" + tag.relativePath()),
            evidenceLocation(tag.relativePath(), range),
            1.0d);
    return new NormalizedSymbol(
        new ExtractedSymbol(
        identity,
        tag.kind(),
        tag.language(),
        tag.name(),
        qualifiedName,
        tag.signature(),
        tag.scope(),
        tag.scopeKind(),
        tag.relativePath(),
        range,
        evidence));
  }

  private static SymbolIdentity symbolIdentity(
      Workspace workspace,
      ParsedTag tag,
      Optional<String> qualifiedName,
      SymbolSourceRange range) {
    boolean locationSensitive =
        qualifiedName.isEmpty()
            || (tag.kind() == SymbolKind.METHOD && tag.signature().isEmpty());
    String discriminator =
        locationSensitive
            ? "location:"
                + range.startLine()
                + ":"
                + range.endLine().map(Object::toString).orElse("")
            : "signature:" + tag.signature().orElse("");
    StringBuilder encoded = new StringBuilder("ctags-symbol-v1");
    appendLengthPrefixed(encoded, workspace.id());
    appendLengthPrefixed(encoded, tag.relativePath());
    appendLengthPrefixed(encoded, tag.language().ctagsName());
    appendLengthPrefixed(encoded, tag.kind().ctagsName());
    appendLengthPrefixed(encoded, qualifiedName.orElse(""));
    appendLengthPrefixed(encoded, discriminator);
    return new SymbolIdentity(encoded.toString(), locationSensitive);
  }

  private static void appendLengthPrefixed(StringBuilder target, String value) {
    target
        .append('|')
        .append(value.getBytes(StandardCharsets.UTF_8).length)
        .append(':')
        .append(value);
  }

  private static Optional<String> qualifiedName(ParsedTag tag, String packageName) {
    if (tag.kind() == SymbolKind.PACKAGE) {
      return Optional.of(tag.name());
    }
    if (tag.kind().requiresScope() && tag.scope().isEmpty()) {
      return Optional.empty();
    }

    String scope = tag.scope().orElse("");
    String prefix;
    if (packageName == null || packageName.isBlank()) {
      prefix = scope;
    } else if (scope.isBlank() || scope.equals(packageName)) {
      prefix = packageName;
    } else if (scope.startsWith(packageName + ".")) {
      prefix = scope;
    } else {
      prefix = packageName + "." + scope;
    }
    return Optional.of(prefix.isBlank() ? tag.name() : prefix + "." + tag.name());
  }

  private String normalizedOutputPath(String rawPath, ExtractionPlan plan) throws RecordFailure {
    final Path outputPath;
    try {
      outputPath = Path.of(rawPath);
    } catch (InvalidPathException error) {
      throw malformed("ctags emitted an invalid source path");
    }
    if (outputPath.isAbsolute() || containsParentSegment(outputPath)) {
      throw malformed("ctags emitted a root-escaping source path");
    }
    final Path candidate;
    try {
      candidate = plan.workspace().root().resolve(outputPath).normalize();
    } catch (IllegalArgumentException error) {
      throw malformed("ctags emitted an invalid source path");
    }
    if (!candidate.startsWith(plan.workspace().root()) || !Files.isRegularFile(candidate)) {
      throw malformed("ctags emitted an unavailable source path");
    }
    try {
      Path resolved = candidate.toRealPath();
      if (!resolved.startsWith(plan.workspace().root()) || !Files.isRegularFile(resolved)) {
        throw malformed("ctags emitted a root-escaping source path");
      }
      String relative = relativePath(plan.workspace().root(), resolved);
      if (!plan.relativeJavaFiles().contains(relative)) {
        throw malformed("ctags emitted a source file outside the request");
      }
      return relative;
    } catch (IOException error) {
      throw malformed("ctags emitted an unresolved source path");
    }
  }

  private RawOutput readOutput(CtagsRunningProcess process, long deadlineNanos, int byteLimit)
      throws TimeoutException, InterruptedException, AdapterFailure {
    ThreadFactory factory =
        runnable -> {
          Thread thread = new Thread(runnable, "ctags-symbol-output");
          thread.setDaemon(true);
          return thread;
        };
    ExecutorService reader = Executors.newSingleThreadExecutor(factory);
    try {
      Future<RawOutput> future = reader.submit(() -> readBounded(process.standardOutput(), byteLimit));
      try {
        return future.get(remainingNanos(deadlineNanos), TimeUnit.NANOSECONDS);
      } catch (ExecutionException error) {
        Throwable cause = error.getCause();
        if (cause instanceof AdapterFailure failure) {
          throw failure;
        }
        throw new AdapterFailure(
            SymbolExtractionDiagnosticCode.MALFORMED_OUTPUT, "ctags stdout could not be read");
      }
    } finally {
      reader.shutdownNow();
    }
  }

  private static RawOutput readBounded(InputStream output, int byteLimit) throws AdapterFailure {
    try (InputStream input = output; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4_096];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        int remaining = byteLimit - bytes.size();
        if (read > remaining) {
          if (remaining > 0) {
            bytes.write(buffer, 0, remaining);
          }
          return new RawOutput(bytes.toByteArray(), true);
        }
        bytes.write(buffer, 0, read);
      }
      return new RawOutput(bytes.toByteArray(), false);
    } catch (IOException error) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.MALFORMED_OUTPUT, "ctags stdout could not be read");
    }
  }

  private static String decodeUtf8(byte[] bytes) throws AdapterFailure {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException error) {
      throw new AdapterFailure(
          SymbolExtractionDiagnosticCode.ENCODING_ERROR, "ctags emitted invalid UTF-8 output");
    }
  }

  private static Map<String, Object> jsonObject(String line) throws RecordFailure {
    try {
      Map<String, Object> parsed = OKJSON.toMap(line, OKJSON.OPTIONS_STRICT_POLICY);
      if (parsed == null) {
        throw malformed("ctags emitted malformed JSON");
      }
      return parsed;
    } catch (RuntimeException error) {
      throw malformed("ctags emitted malformed JSON");
    }
  }

  private static String requiredString(Map<String, Object> record, String field)
      throws RecordFailure {
    if (!record.containsKey(field)) {
      throw new RecordFailure(
          SymbolExtractionDiagnosticCode.MISSING_REQUIRED_FIELD,
          "ctags record is missing " + field);
    }
    Object value = record.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw malformed("ctags emitted an invalid " + field);
    }
    return text;
  }

  private static Optional<String> optionalString(Map<String, Object> record, String field)
      throws RecordFailure {
    if (!record.containsKey(field)) {
      return Optional.empty();
    }
    Object value = record.get(field);
    if (!(value instanceof String text) || text.isBlank()) {
      throw malformed("ctags emitted an invalid " + field);
    }
    return Optional.of(text);
  }

  private static int requiredPositiveInt(Map<String, Object> record, String field)
      throws RecordFailure {
    if (!record.containsKey(field)) {
      throw new RecordFailure(
          SymbolExtractionDiagnosticCode.MISSING_REQUIRED_FIELD,
          "ctags record is missing " + field);
    }
    Object value = record.get(field);
    if (!(value instanceof Number number)) {
      throw malformed("ctags emitted an invalid " + field);
    }
    long parsed = number.longValue();
    if (parsed < 1 || parsed > Integer.MAX_VALUE || parsed != number.doubleValue()) {
      throw malformed("ctags emitted an invalid " + field);
    }
    return (int) parsed;
  }

  private static Optional<Integer> optionalEndLine(Map<String, Object> record, int startLine)
      throws RecordFailure {
    if (!record.containsKey("end")) {
      return Optional.empty();
    }
    Object value = record.get("end");
    if (!(value instanceof Number number)) {
      throw malformed("ctags emitted an invalid end");
    }
    long parsed = number.longValue();
    if (parsed < startLine || parsed > Integer.MAX_VALUE || parsed != number.doubleValue()) {
      throw malformed("ctags emitted an invalid end");
    }
    return Optional.of((int) parsed);
  }

  private static String evidenceLocation(String relativePath, SymbolSourceRange range) {
    return range
        .endLine()
        .map(end -> relativePath + ":L" + range.startLine() + "-L" + end)
        .orElse(relativePath + ":L" + range.startLine());
  }

  private static String firstNonBlankLine(String value) {
    for (String line : value.split("\\R")) {
      if (!line.isBlank()) {
        return line;
      }
    }
    return null;
  }

  private static boolean supportsJava(String languages) {
    for (String language : languages.split("\\R")) {
      if (SymbolLanguage.JAVA.ctagsName().equals(language.trim())) {
        return true;
      }
    }
    return false;
  }

  private static byte[] completeLinePrefix(byte[] bytes) {
    for (int index = bytes.length - 1; index >= 0; index--) {
      if (bytes[index] == '\n') {
        return java.util.Arrays.copyOf(bytes, index + 1);
      }
    }
    return new byte[0];
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
    return root.relativize(path).toString().replace('\\', '/');
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

  private static SymbolExtractionResult failure(CtagsToolProfile profile, AdapterFailure failure) {
    return new SymbolExtractionResult(
        List.of(),
        profile,
        List.of(new SymbolExtractionDiagnostic(failure.code(), failure.getMessage())),
        false);
  }

  private static CtagsToolProfile unverifiedProfile() {
    return new CtagsToolProfile(PROFILE_ID, Optional.empty());
  }

  private static RecordFailure malformed(String detail) {
    return new RecordFailure(SymbolExtractionDiagnosticCode.MALFORMED_OUTPUT, detail);
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

  private static Duration remainingDuration(long deadlineNanos) throws TimeoutException {
    return Duration.ofNanos(remainingNanos(deadlineNanos));
  }

  private static boolean waitFor(CtagsRunningProcess process, Duration timeout)
      throws InterruptedException {
    return process.waitFor(timeout);
  }

  private static void terminate(CtagsRunningProcess process) {
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

  /** Immutable resource ceiling and explicit executable configuration for this Adapter. */
  public record Settings(
      String executable, int maxFiles, int maxResults, int maxOutputBytes, Duration timeout) {

    public Settings {
      executable = executable == null ? "" : executable;
      try {
        if (!executable.isBlank() && !Path.of(executable).isAbsolute()) {
          throw new IllegalArgumentException("ctags executable must be an explicit absolute path");
        }
      } catch (InvalidPathException error) {
        throw new IllegalArgumentException("ctags executable path is invalid", error);
      }
      if (maxFiles < 1
          || maxFiles > MAX_FILES
          || maxResults < 1
          || maxResults > MAX_RESULTS
          || maxOutputBytes < 1
          || maxOutputBytes > MAX_OUTPUT_BYTES) {
        throw new IllegalArgumentException("ctags limits exceed the fixed profile ceiling");
      }
      Objects.requireNonNull(timeout, "timeout");
      if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(60)) > 0) {
        throw new IllegalArgumentException("timeout must be between zero and sixty seconds");
      }
    }

    /** Creates the documented resource policy for one explicitly configured Ctags executable. */
    public static Settings defaults(String executable) {
      return new Settings(executable, MAX_FILES, MAX_RESULTS, MAX_OUTPUT_BYTES, Duration.ofSeconds(5));
    }

    public static Settings unavailable() {
      return new Settings("", MAX_FILES, MAX_RESULTS, MAX_OUTPUT_BYTES, Duration.ofSeconds(5));
    }
  }

  private record Workspace(String id, Path root) {}

  private record ExtractionPlan(Workspace workspace, List<String> relativeJavaFiles, int resultLimit) {}

  private record RawOutput(byte[] bytes, boolean truncated) {}

  private record ParsedTag(
      String relativePath,
      SymbolLanguage language,
      SymbolKind kind,
      String name,
      int line,
      Optional<Integer> endLine,
      Optional<String> signature,
      Optional<String> scope,
      Optional<String> scopeKind) {}

  private record NormalizedSymbol(ExtractedSymbol symbol) {}

  private static final class ParseAccumulator {
    private final List<ParsedTag> tags = new ArrayList<>();
    private final List<SymbolExtractionDiagnostic> diagnostics = new ArrayList<>();
    private boolean complete;

    private ParseAccumulator(boolean complete) {
      this.complete = complete;
    }

    private List<ParsedTag> tags() {
      return tags;
    }

    private List<SymbolExtractionDiagnostic> diagnostics() {
      return diagnostics;
    }

    private boolean complete() {
      return complete;
    }

    private void setComplete(boolean complete) {
      this.complete = complete;
    }

    private void addFailure(RecordFailure failure) {
      diagnostics.add(new SymbolExtractionDiagnostic(failure.code(), failure.getMessage()));
      complete = false;
    }
  }

  private static class AdapterFailure extends Exception {
    private final SymbolExtractionDiagnosticCode code;

    private AdapterFailure(SymbolExtractionDiagnosticCode code, String detail) {
      super(detail);
      this.code = code;
    }

    SymbolExtractionDiagnosticCode code() {
      return code;
    }
  }

  private static final class RecordFailure extends AdapterFailure {
    private RecordFailure(SymbolExtractionDiagnosticCode code, String detail) {
      super(code, detail);
    }
  }

  private static final class ProcessBuilderLauncher implements CtagsProcessLauncher {
    @Override
    public CtagsRunningProcess start(List<String> arguments, Path workingDirectory) throws IOException {
      ProcessBuilder builder = new ProcessBuilder(arguments);
      builder.directory(workingDirectory.toFile());
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      return new ProcessBackedCtagsProcess(builder.start());
    }
  }

  private static final class ProcessBackedCtagsProcess implements CtagsRunningProcess {
    private final Process process;

    private ProcessBackedCtagsProcess(Process process) {
      this.process = process;
    }

    @Override
    public InputStream standardOutput() {
      return process.getInputStream();
    }

    @Override
    public boolean waitFor(Duration timeout) throws InterruptedException {
      return process.waitFor(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
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
