# SecureVault API — Project Status

Last updated: 2026-10-22 — **MVP complete**

This file is the shared source of truth on project state for Alex,
Claude, and ChatGPT. Update it after every milestone with real results —
never with a claim that hasn't been verified by actually running the
build/tests.

## Verification summary

| What | Status | How it was verified |
|---|---|---|
| Build + full test suite | ✅ Verified locally | `mvn -B -ntp verify` → 91/91, BUILD SUCCESS on `d7995d6` (clean tree) |
| CI on GitHub (`ci.yml`) | ✅ Verified on GitHub | Push run on `main` at `dfc0650` **success**; also **success** on Dependabot PR #1 (checked via the GitHub Actions API) |
| Dependency Review (`dependency-review.yml`) | ✅ Verified on GitHub | **success** on Dependabot PR #1, after Alex enabled the dependency graph |
| Dependabot (Maven + Actions) | ✅ Verified on GitHub | Both update jobs ran with **success**; opened PR #1 (`actions/setup-java` 6.0.1 → 6.1.0, SHA pin updated), which is still open for Alex to merge or close |
| Quota commits (`d7995d6` and later) on GitHub CI | ⏳ Not yet verified | Committed locally, not pushed; verified locally only |
| Manual smoke tests (auth, documents, API docs) | ✅ Verified locally | curl against `mvn spring-boot:run` + Compose Postgres; details below |
| Swagger UI renders | ✅ Verified (headless) | Headless Chrome DOM dump after JS ran. Not checked in an interactive browser |
| Storage quota over real HTTP | ⏳ Not verified | Covered by integration tests only; no manual smoke test |
| `actionlint` on workflows | ⏳ Not run | Tool not installed; workflows proven by green runs instead |

Environment: Temurin JDK 21.0.12.1, Maven 3.10.0, Docker Desktop
(Testcontainers + Compose, `postgres:16-alpine` → PostgreSQL 16.15),
Windows 11. CI runs on GitHub-hosted `ubuntu-latest`.

### Latest local `mvn -B -ntp verify` (commit `d7995d6`)

```
Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- ApiDocsIntegrationTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuditLogIntegrationTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- AuthIntegrationTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AllowedFileTypeTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- DocumentIntegrationTest
Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- StorageQuotaIntegrationTest
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitFilterTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- JwtAuthenticationFilterTest
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0 -- SecurityRegressionTest
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- LocalFilesystemStorageServiceTest
Tests run: 91, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

One `ERROR`-level log line appears during the run. It is expected: it
comes from `serverErrorsDoNotLeakInternals`, which deliberately causes a
500 and checks that the response body leaks nothing.

### Mutation checks

For each row, the code was broken on purpose, the listed tests were
confirmed to fail, and the code was restored:

| Broken on purpose | Test(s) that failed |
|---|---|
| `AuditService` `REQUIRES_NEW` → `REQUIRED` | `duplicateRegistrationFailureSurvivesTransactionRollback` |
| Remove rate-limit cache `maximumSize` + `expireAfterAccess` | `idleIpBucketIsEvictedAfterOneRefillPeriod`, `numberOfTrackedIpsIsBounded` |
| Key rate limit on raw address instead of IPv6 /64 | `ipv6AddressesInSameSlash64ShareABucket`, `ipv4IsKeyedByFullAddressAndMappedIpv6CountsAsIpv4` |
| Rate-limit filter matching the raw URI (behavior before `0a344cc`) | `loginCannotBeReachedAroundTheRateLimit` for `%6Cogin` and `%61uth` |
| Remove real-path (symlink) checks in storage | `symlinkInsideRootPointingOutsideIsRejected` |
| Document lookup `findByIdAndOwnerId` → `findById` | `otherUsersDocumentIsIndistinguishableFromMissing`, `documentEventsAreAudited` |
| Remove the per-user row lock in the quota check | `concurrentUploadsCannotOvershootQuota` (3/3 runs). All 8 concurrent 300-byte uploads succeeded: 2,400 bytes against a 1,024-byte quota |

### Manual smoke tests (real HTTP via curl, `mvn spring-boot:run`, Compose Postgres)

These were run before the quota feature existed. Quota behavior is
covered by integration tests only.

- **Auth:** health 200; register 201; duplicate 409; wrong password 401;
  login 200 (`alg: HS256`); `/api/users/me` 200 with a token and 401
  without; 429 + `Retry-After` once over the limit, even with a changing
  `X-Forwarded-For`; `audit_log` rows matched the requests exactly.
- **Documents:**

  | Request | Result |
  |---|---|
  | Real PDF sent with a spoofed `image/png` type and a `../../` filename | 201, `application/pdf`, filename `x.pdf` |
  | HTML named `.pdf` | 415 |
  | Exactly 25 MiB / 25 MiB + 1 byte | 201 / 413 `ApiError`, from Tomcat's real multipart limit |
  | Download | attachment, `nosniff`, `application/pdf`, bytes identical |
  | Another user's get / download / delete | 404 |
  | Bad id / no token | 400 / 401 |
  | Delete | 204, file removed from disk |

- **Rate-limit bypass fix on real Tomcat:** after exhausting the limit,
  valid credentials sent to `/api/auth/%6Cogin` and `/api/%61uth/login`
  got 429. Before the fix, the same requests logged in (MockMvc
  reproduction: 200).
- **API docs:**
  - `/swagger-ui.html` → 302 to `/swagger-ui/index.html` (200), with its
    assets returning 200.
  - `/v3/api-docs` is OpenAPI 3.1.0, with global `bearerAuth` and
    register/login `security: []`.
  - Headless Chrome render: title, all 6 paths, the Authorize button,
    and no error panel.

## Git history

The planning-session history wasn't preserved. The repo was initialized
fresh locally, and Alex later rewrote history so every commit uses the
GitHub noreply address, which changed every hash. Use `git log` for the
current history; this file only quotes hashes it cites as evidence.

## What's in the MVP

| Area | Delivered |
|---|---|
| Auth | Register / login / `GET /api/users/me`; BCrypt; HS256 JWT checked on signature, algorithm, expiry, issuer, subject; user must exist |
| Rate limiting | Bucket4j `bucket4j_jdk17-core:8.21.0` + Caffeine (bounded, expiring). Keyed on IPv4 address / IPv6 /64, never `X-Forwarded-For`. Paths matched like Spring Security (closes the percent-encoding bypass). 429 + `Retry-After` |
| Documents | Owner-scoped upload, list, get, download, delete; another user's document = same 404 as missing |
| Upload safety | PDF/PNG/JPEG by magic bytes; 25 MB per file (413); server-generated `{ownerId}/{documentId}.{ext}`; lexical + real-path storage checks; sanitized display filenames |
| Storage quota | Per user, `STORAGE_QUOTA_PER_USER` (default 1GB). Over quota → **409**. Enforced under a row lock (`SELECT ... FOR UPDATE` on `app_users`) so concurrent uploads can't overshoot. Rejections audited; delete frees quota |
| Downloads | Attachment, RFC 5987 filename, detected `Content-Type`, `nosniff` |
| Audit | `audit_log` for register/login/upload/download/delete, success and failure; `REQUIRES_NEW` |
| Errors | Uniform `ApiError`; no internals leaked |
| API docs | springdoc-openapi 3.1.1 (latest on Maven Central; built against Boot 4.1.0, verified on 4.1.1); Swagger UI + `/v3/api-docs`; bearer auth documented |
| CI/CD | `mvn verify` workflow, Dependency Review (fails on high/critical), Dependabot; actions SHA-pinned, `contents: read` |
| Docs | README: setup, config, API + error contract (incl. 413 vs 409), architecture, threat model, tradeoffs, limitations; CI badge |

### Security test pass: gaps found

1. **Real vulnerability (fixed, `0a344cc`):** rate-limit bypass via
   percent-encoded auth paths. `/api/auth/%6Cogin` reached login without
   any limit.
2. **Real weakness (fixed, `8f78d5e`):** IPv6 clients could rotate
   addresses within their /64 to avoid the limit.
3. **Real weakness (fixed, `d7995d6`):** no storage quota. A naive
   check-then-insert would have been racy; the mutation check showed
   8/8 concurrent uploads overshooting without the lock.
4. **Coverage gaps (code was correct, now tested):**
   - Token attacks on every document endpoint, not just `/api/users/me`.
   - Cross-user access with uppercase UUIDs and injected `ownerId`.
   - Malformed ids on every endpoint.
   - Error-body leakage, auth path variants, and actuator exposure.

## Decisions made during implementation

- Endpoints live under `/api/...`
- Login and register share one rate-limit bucket per client key
- Over-quota uploads return **409 Conflict**, not 413 (413 = this file is
  too large) or 507 (implies a server-side storage failure)
- The quota counts `documents.size_bytes`; orphaned files aren't counted
- Failed logins record the targeted user id (if the email exists) as
  `resource_id` with null `actor_id`; the attempted email is never stored
- `audit_log` has no foreign keys, so records outlive users/documents
- Success audit rows also use `REQUIRES_NEW` (a rare commit failure after
  flush could leave a success row for a rolled-back action; accepted)
- `Document` ids are assigned in Java so the storage key is known before
  the insert; the file is deleted if the insert fails
- Delete removes the row first, then the file
- `nosniff` relies on Spring Security's default headers (asserted in tests)
- API docs are public (documented; switchable off)

## Known limitations (documented in README)

- Polyglot files: only leading magic bytes are checked; contained by
  attachment + `nosniff` + detected type
- Orphaned files on partial failure (logged; no cleanup job; not counted
  in quota)
- The quota is per user with no global cap; many accounts could still
  fill the disk together. One quota value for everyone
- Uploads aren't rate-limited (bounded by the per-file limit and quota)
- No token revocation or logout; 60-minute TTL
- The rate limiter is in-memory, single-node, and keyed on the direct
  peer address; it needs trusted-proxy handling behind a reverse proxy
- `bearer` (lowercase scheme) is rejected (fail-closed)

## Next steps

1. Push the new local commits (badge, quota, docs) and confirm CI is
   green on them.
2. Decide on Dependabot PR #1 (setup-java 6.1.0; both checks green).
3. Optional: a manual smoke test of the quota over real HTTP.
4. Deferred per spec: S3 storage, versioning, admin role, per-account
   lockout, cloud deployment, CodeQL.

## Known constraints

- No cloud deployment target chosen yet (explicitly deferred)
- No admin role in MVP; no audit read endpoint
- No document versioning in MVP
- No per-account login lockout in MVP (rate limiting only)
- Local filesystem storage only (S3 deferred)
