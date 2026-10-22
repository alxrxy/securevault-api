# SecureVault API — Project Status

Last updated: 2026-10-22 (document endpoints milestone complete)

This file is the shared source of truth on project state for Alex,
Claude, and ChatGPT. Update it after every milestone with real results —
never with a claim that hasn't been verified by actually running the
build/tests.

## Verification status: compiled, tested, and smoke-tested locally

Environment: Temurin JDK 21.0.12.1, Maven 3.10.0, Docker Desktop
(Testcontainers + Compose, `postgres:16-alpine` → PostgreSQL 16.15),
Windows 11.

Latest `mvn test` on commit `1835019` (clean tree):

```
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuditLogIntegrationTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- AuthIntegrationTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AllowedFileTypeTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- DocumentIntegrationTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitFilterTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- JwtAuthenticationFilterTest
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- LocalFilesystemStorageServiceTest
Tests run: 48, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Integration tests boot the full context against real PostgreSQL
(Testcontainers, Flyway V1 + V2, `ddl-auto: validate`). No test is skipped:
the symlink-escape storage test uses a directory junction on Windows
accounts that can't create symlinks, and real symlinks elsewhere.

### Mutation checks (tests proven to catch the bug they target)

Each was done by temporarily breaking the code, confirming failure, and
restoring:

| Broken on purpose | Test(s) that failed |
|---|---|
| `AuditService` `REQUIRES_NEW` → `REQUIRED` | `duplicateRegistrationFailureSurvivesTransactionRollback` |
| Remove rate-limit cache `maximumSize` + `expireAfterAccess` | `idleIpBucketIsEvictedAfterOneRefillPeriod`, `numberOfTrackedIpsIsBounded` |
| Remove real-path (symlink) checks in storage | `symlinkInsideRootPointingOutsideIsRejected` |
| Document lookup `findByIdAndOwnerId` → `findById` | `otherUsersDocumentIsIndistinguishableFromMissing`, `documentEventsAreAudited` |

### Manual smoke tests (real HTTP via curl, `mvn spring-boot:run`, Compose Postgres)

**Auth (2026-10-22):** health 200; register 201; duplicate register 409;
wrong password 401; login 200 with an `alg: HS256` token; `/api/users/me`
200 with token and 401 without; the 12th auth call within ~2 s got 429 +
`Retry-After` even with a different `X-Forwarded-For` on each request
(11 got through: 10/min greedy refill added one token). `audit_log` held
exactly the expected 10 rows; the 429'd request was not audited (the
filter rejects it before the service runs).

**Documents (2026-10-22):**

| Request | Result |
|---|---|
| Real PDF sent as `type=image/png;filename=../../x.pdf` | 201, `application/pdf`, filename `x.pdf` |
| HTML named `.pdf` | 415 |
| Exactly 25 MiB | 201 |
| 25 MiB + 1 byte | 413 `ApiError`, from Tomcat's real multipart limit (MockMvc can't exercise this) |
| Download | `Content-Disposition: attachment; filename="x.pdf"; filename*=UTF-8''x.pdf`, `X-Content-Type-Options: nosniff`, `Content-Type: application/pdf`, bytes identical |
| Another user's get / download / delete | 404, same body as a missing document |
| Non-UUID id | 400 |
| No token | 401 |
| On disk | `<ownerUuid>/<docUuid>.pdf` only |
| Delete | 204, then 404, and the file is removed from disk |

## Git history

The planning-session history was not preserved; this repo was initialized
fresh locally with one baseline commit containing the scaffold.

1. `1aa60e7` chore: import SecureVault MVP scaffold from planning session
2. `370bea5` fix(build): use Boot-managed Testcontainers 2.x and add Flyway Postgres module
3. `47409e8` fix(security): harden draft JwtService and JwtAuthenticationFilter
4. `a855b5d` feat(auth): add registration, login, and GET /api/users/me
5. `f8d248a` feat(security): add per-IP rate limiting on auth endpoints
6. `4c5ee53` feat(audit): record register and login events in audit_log
7. `b35e11b` docs: record verified build and test results for auth milestone
8. `bf6e5c3` test(security): verify rate-limit bucket cache eviction and size bound
9. `a4edbfb` docs: document duplicate-email 409 enumeration tradeoff
10. `430669c` feat(storage): add StorageService with path-safe local filesystem impl
11. `0f56398` feat(documents): add magic-byte detection for PDF, PNG, and JPEG
12. `1835019` feat(documents): add owner-scoped upload, list, get, download, delete

## Milestone: registration + JWT auth — done

- `POST /api/auth/register` → 201; email trimmed + lowercased; 409 on
  duplicate (documented enumeration tradeoff in README); password 12–72
  chars and ≤ 72 UTF-8 bytes (BCrypt limit)
- `POST /api/auth/login` → 200 `{accessToken, tokenType, expiresIn}`;
  unknown email and wrong password give the same 401
- `GET /api/users/me`
- JWT: HS256 only; validates signature, algorithm, expiration (30 s skew),
  issuer, subject; user must still exist
- Rate limiting: Bucket4j `com.bucket4j:bucket4j_jdk17-core:8.21.0` +
  Caffeine (max 100k IPs, expire-after-access = 1 refill period), keyed on
  `getRemoteAddr()` only; login + register share 10/min per IP
  (`AUTH_RATE_LIMIT_PER_MINUTE`); 429 + `Retry-After`
- Audit log (`V2__audit_log.sql`), `REQUIRES_NEW` writes

Scaffold problems found and fixed in this milestone: Testcontainers
downgraded to 1.21.1 via a property clash with Boot's 2.0.5; missing
`flyway-database-postgresql`; unpinned JWT algorithm; NPE (500) on a token
without `sub`; wrong exception caught for a bad secret; filter
double-registered as a servlet filter; invalid default JWT secret; 403
instead of 401 for unauthenticated requests.

## Milestone: document endpoints — done

| Endpoint | Result |
|---|---|
| `POST /api/documents` (multipart `file`) | 201 `DocumentResponse` |
| `GET /api/documents` | caller's documents, newest first |
| `GET /api/documents/{id}` | metadata |
| `GET /api/documents/{id}/download` | attachment |
| `DELETE /api/documents/{id}` | 204 |

- **Ownership (BOLA/IDOR):** every lookup is `findByIdAndOwnerId` /
  `findByOwnerId...`; someone else's document returns the same 404 as a
  missing one; denied attempts are audited with the requester as actor
  and the requested id as resource
- **Types:** PDF/PNG/JPEG by magic bytes (`AllowedFileType`); stored
  content type and extension come from the bytes, never from the client
- **Size:** 25 MB via `spring.servlet.multipart.max-file-size`, also
  enforced in `DocumentService`; empty → 400, too big → 413, wrong type → 415
- **Storage:** `StorageService` + `LocalFilesystemStorageService`; key
  `{ownerId}/{documentId}.{ext}`, all server-generated; root from
  `STORAGE_ROOT` (default `./data/storage`, gitignored); lexical check
  plus a real-path (symlink-resolved) check before every read, write, and
  delete; `CREATE_NEW` + `NOFOLLOW_LINKS` on write
- **Filenames:** display-only; path parts and control characters
  stripped, ≤ 255 chars, detected extension appended if it doesn't match
  (e.g. `evil.pdf` containing PNG bytes → `evil.pdf.png`); duplicates allowed
- **Downloads:** `Content-Disposition: attachment` (RFC 5987-encoded via
  Spring's `ContentDisposition`), detected `Content-Type`, `nosniff`
  (Spring Security default header, asserted in tests and the smoke test)
- **Audit:** `DOCUMENT_UPLOAD` / `DOCUMENT_DOWNLOAD` / `DOCUMENT_DELETE`,
  success and failure

## Decisions made during implementation

- Endpoints live under `/api/...` (matches `SecurityConfig`)
- Login and register share one rate-limit bucket per IP
- Failed logins record the targeted user id (if the email exists) as
  `resource_id` with null `actor_id`; the attempted email is never stored
- `audit_log` has no foreign keys so records outlive users/documents
- Success audit records also use `REQUIRES_NEW`; if the outer transaction
  failed to commit *after* its flush, a success row could exist for a
  rolled-back action. Accepted for MVP
- `Document` ids are assigned in Java so the storage key is known before
  the insert; if the insert fails, the stored file is deleted
- Delete removes the DB row first, then the file; a failed file delete
  leaves a logged orphan rather than a row pointing at nothing
- Magic-byte detection lives on the `AllowedFileType` enum rather than in
  a separate detector class
- `nosniff` relies on Spring Security's default headers rather than being
  set twice; the tests fail if that default is ever disabled

## Open issues / risks found (not fixed — need a decision)

- **IPv6 rate-limit granularity:** the limiter keys on the full remote
  address. One IPv6 client typically controls a whole /64 (2^64
  addresses), so it can rotate addresses and avoid the per-IP limit.
  Common fix: key IPv6 clients by their /64 prefix. Not in the spec, so
  not changed.
- **Polyglot files:** magic-byte sniffing checks only the first bytes, so
  a file can start with a valid PDF/PNG/JPEG signature and carry other
  content after it. Mitigated by attachment-only downloads with `nosniff`
  and a server-detected `Content-Type`; full format validation is out of
  MVP scope.
- **Orphaned files** are possible on partial failures (logged at WARN); no
  cleanup job.

## Next steps (in order)

1. springdoc-openapi (confirm the version on Maven Central; load Swagger UI
   and `/v3/api-docs` against the running app)
2. Security test suite pass
3. CI/CD (GitHub Actions `mvn verify` + Dependabot + Dependency Review
   Action) and docs polish

## Known constraints

- No cloud deployment target chosen yet (explicitly deferred)
- No admin role in MVP; no audit read endpoint
- No document versioning in MVP
- No per-account login lockout in MVP (IP-based rate limiting only)
- Rate-limit state is in-memory: resets on restart, not shared across instances
- Local filesystem storage only (S3 deferred)
