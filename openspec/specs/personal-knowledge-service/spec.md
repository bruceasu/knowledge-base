# Personal knowledge service

## Requirements

### Requirement: Independent secretary API
The service SHALL implement contract 1.0 defined in `docs/handoffs/personal-secretary-knowledge-handoff.md` under `/api/v2/secretary`, preserving existing HTTP contracts. Authentication SHALL use independent revocable Bearer credentials with stable subjects, scopes and collection permissions. No endpoint SHALL require a shared disk or MCP.

#### Scenario: Unauthorized document
- WHEN a document is absent or outside the credential's collections
- THEN the service returns 404 without content or existence disclosure.

### Requirement: Recoverable note creation
The service SHALL persist an accepted request before returning 202. The stable subject and idempotency key SHALL uniquely identify an operation. Same-key different-content requests SHALL return 409. Recovery SHALL reuse the assigned document identity and file location. Saved and searchable SHALL be separate states; deletion SHALL NOT permit replay to recreate a note.

#### Scenario: Timeout and restart
- WHEN the client retries an accepted request after timeout or service restart
- THEN the original operation is returned without creating another note.

### Requirement: Managed original uploads
Each upload SHALL contain one file or ZIP of at most 100,000,000 bytes inclusive. Actual streamed bytes SHALL be bounded irrespective of Content-Length. Rejected partial uploads SHALL be cleaned. Originals SHALL survive extraction failures, and import state SHALL be persistent.

#### Scenario: Oversize upload
- WHEN streamed file content exceeds the limit by one byte
- THEN the service returns 413 and removes incomplete temporary data.

### Requirement: Bounded document extraction
The service SHALL support Markdown, text, HTML, ZIP resource bundles, text PDF and DOCX without OCR. ZIP extraction SHALL allow at most 500,000,000 total expanded bytes, 2,000 entries, 100,000,000 bytes per entry and 100:1 compression ratio. Nested archives SHALL NOT be recursively extracted. Unsafe paths and links SHALL be rejected. XML external entities and active HTML SHALL NOT execute or load remote resources.

#### Scenario: Archive exceeds its budget
- WHEN an expanded archive exceeds any approved bound
- THEN extraction fails with a bounded diagnostic while preserving the original archive.

### Requirement: Protected remote access
Browser pages, APIs, previews and attachments SHALL require Caddy/Authelia authentication, with application write-origin/CSRF checks. Only exact secretary API routes SHALL bypass browser login, and they SHALL still verify service credentials. Backend listeners SHALL not be remotely reachable around the proxy. Authentication outages SHALL fail closed.

#### Scenario: Forged identity header
- WHEN an untrusted caller provides an identity header
- THEN the caller cannot obtain authenticated browser access.

### Requirement: Evidence and rollout
FD-001 acceptance 1–8 and its design govern initial implementation. Runtime security and recovery checks not executed SHALL be recorded as unverified. MCP retirement SHALL occur only after HTTP replacements are implemented, with shared domain capabilities preserved and existing MCP stable specifications reconciled.
