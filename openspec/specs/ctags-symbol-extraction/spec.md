# ctags-symbol-extraction Specification

## Purpose
TBD - created by archiving change engineering-knowledge-ctags-symbol-extraction. Update Purpose after archive.
## Requirements
### Requirement: Constrained Java symbol-extraction seam

The system SHALL expose one symbol-extraction operation that accepts a repository-confirmed workspace, a bounded set of workspace-relative Java source files or scopes, and a result budget. The operation MUST reject an unknown workspace, a root-escaping path, unsupported language input, or invalid budget before starting Ctags.

#### Scenario: Extract symbols from an allowed Java file

- **WHEN** a caller supplies a valid registered workspace and an in-root Java source file within the configured budget
- **THEN** the Adapter returns a `SymbolExtractionResult` rather than exposing a raw ctags command or tool arguments

#### Scenario: Reject an unsupported or out-of-root source request

- **WHEN** a caller supplies a file outside the workspace or a language not supported by the initial extractor
- **THEN** the Adapter returns a deterministic invalid/unsupported diagnostic and does not run Ctags for that input

### Requirement: Versioned Universal Ctags JSON processing

The Adapter MUST invoke a repository-confirmed Universal Ctags profile with parameterized process arguments and JSON output. It MUST record or expose the effective tool version/profile, parse only valid tag records, and handle unknown record kinds or missing required fields with deterministic skips or diagnostics.

#### Scenario: Parse supported JSON tag records

- **WHEN** Universal Ctags emits valid JSON tag records for a Java source file
- **THEN** the Adapter normalizes only supported symbol records and ignores non-symbol metadata/progress records

#### Scenario: Diagnose malformed or incompatible tool output

- **WHEN** the tool is unavailable, exits unexpectedly, times out, emits malformed JSON, or omits a required field
- **THEN** the Adapter returns a structured diagnostic that identifies the outcome instead of reporting a complete empty symbol set

### Requirement: Stable symbol identity and location evidence

Each extracted symbol MUST include a core-compatible source/evidence record with normalized workspace-relative path, language, kind, and available source range. Its identity MUST use the confirmed workspace/repository identity, path, language, kind, qualified name, and signature or equivalent disambiguator when available; a line number alone MUST NOT be its primary identity.

#### Scenario: Represent a Java method with source evidence

- **WHEN** Ctags reports a Java method with name, scope, signature, path, and line range
- **THEN** the result exposes a normalized qualified symbol candidate with the corresponding source range and static-analysis evidence

#### Scenario: Preserve identity across a preceding-line edit

- **WHEN** the same qualified, signed symbol moves because unrelated lines are inserted before it
- **THEN** the Adapter can report its changed source range without relying on the old line number as the symbol's primary identity

### Requirement: Distinguish overloads and incomplete metadata honestly

The Adapter MUST distinguish overloaded or same-named symbols using signature or another confirmed semantic discriminator. If a required qualified name or signature is unavailable, it MUST use only the documented location-sensitive fallback, mark the limitation through diagnostics/metadata, and MUST NOT silently merge distinct symbols.

#### Scenario: Extract two overloaded methods

- **WHEN** Ctags reports two methods with the same qualified name but different signatures
- **THEN** the result contains two distinct symbol candidates with distinct deterministic identities

#### Scenario: Handle a record without a signature

- **WHEN** Ctags reports an otherwise valid method record without the configured signature field
- **THEN** the Adapter preserves the candidate only under its documented location-sensitive fallback and exposes a diagnostic or limitation marker rather than treating it as unambiguously stable

### Requirement: Deterministic bounded candidate results

The Adapter SHALL remove exactly equivalent duplicate tag records and sort accepted candidates deterministically by normalized path, source position, kind, qualified name, and signature before applying the observable result budget. It MUST expose incomplete/truncated status when the budget, output-size limit, or timeout prevents a complete set.

#### Scenario: Repeat an unchanged extraction

- **WHEN** the Adapter extracts the same unchanged workspace snapshot twice with the same Ctags profile and budget
- **THEN** both results contain equivalent candidates in the same deterministic order

#### Scenario: Truncate an oversized extraction

- **WHEN** the tool would emit more candidates or bytes than the configured budget permits
- **THEN** the Adapter returns the bounded result with an explicit incomplete/truncated diagnostic and cleans up the subprocess

### Requirement: Do not infer relations or change existing paths

Extracted symbols SHALL be static-analysis candidates only. The Adapter MUST NOT assert code relations, persist symbols, write Lucene data, alter existing source files, modify SQLite schemas, or change `KnowledgeBase`, `KnowledgeRepository`, HTTP/CLI, dependencies, or deployment configuration.

#### Scenario: Extract a class implementing an interface

- **WHEN** Ctags reports a class and an interface-related textual field
- **THEN** the Adapter reports available symbol metadata/evidence but does not create an `IMPLEMENTS` relation or modify a Store

