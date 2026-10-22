# SecureVault API — Project Status

Last updated: 2026-10-22 — **MVP complete; final housekeeping done**

This file is the shared source of truth on project state for Alex,
Claude, and ChatGPT. Update it after every milestone with real results —
never with a claim that hasn't been verified by actually running the
build/tests.

## Final state

Where each fact comes from:
- **Verified locally (Claude)**: run on this machine during the session.
- **Reported by Alex (GitHub)**: read by Alex from the GitHub Actions /
  pull-request pages. Claude cannot see GitHub and did not confirm these.
- **Verified by Alex**: checked by Alex personally, on GitHub or on Alex's
  own machine. Claude did not observe these checks.
- **Unverified**: nobody has checked yet.

| What | Source | Result |
|---|---|---|
| Build + full test suite | Verified locally (Claude) | Fresh `mvn -B -ntp verify` on `b7ff45d` (after both Dependabot merges): **95 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS. Later commits change only docs |
| Git state | Verified locally (Claude) | Local `main` fast-forwarded to `origin/main` = `b7ff45d`; the housekeeping commits on top are local and **not pushed** |
| CI (`ci.yml`) and CodeQL (`codeql.yml`) on `main` | Reported by Alex (GitHub) | Green after the last push and after both Dependabot merges |
| Dependabot config | Reported by Alex (GitHub) | Accepted the new `dependabot.yml`; opened **one** combined CodeQL group PR (#6, init + analyze 4.38.3 → 4.39.0) |
| PR #6 (CodeQL group) | Reported by Alex (GitHub) | Passed CI, CodeQL and Dependency Review; merged |
| PR #1 (setup-java 6.0.1 → 6.1.0) | Reported by Alex (GitHub); diff checked locally | Merged. The merged diff bumps both `ci.yml` and `codeql.yml` |
| Java 24 / Java 26 Docker PRs (#3, #2) | Reported by Alex (GitHub) | Closed unmerged |
| Secret scanning + push protection | Reported by Alex (GitHub) | Enabled in repo settings |
| Mermaid diagrams render on GitHub | Verified by Alex (GitHub) | Both README diagrams (request flow and data model) render correctly on the repo front page |
| End-to-end demo (`demo/demo.ps1`) | Verified by Alex (local) | Alex ran it against the Docker stack with a 1MB quota: **17/17 PASS**. An earlier FAIL on the quota step was caused by a stale `STORAGE_QUOTA_PER_USER` variable in Alex's PowerShell session. A shell variable overrides `.env` in Compose interpolation, so the app got the wrong quota. Not an app or Compose bug |
| Docker ignore rules in practice | **Unverified** | Whether `eclipse-temurin` still gets Java 21 patch/digest PRs only shows up on later Dependabot runs |

### Earlier verification (unchanged since the resume-readiness pass)

| What | Status | Evidence |
|---|---|---|
| Docker image builds | ✅ Verified locally | `docker compose build` OK; image `securevault-api:local` is 415 MB |
| Container runs hardened | ✅ Verified locally | Healthy; `id` → `uid=10001(app)`; `touch /app/x` → read-only file system; only `/data/storage` writable |
| Container serves health + Swagger UI | ✅ Verified locally | `/actuator/health` 200 `UP`; `/swagger-ui.html` 302 → `index.html` 200; `/v3/api-docs` 6 paths + `bearerAuth` |
| End-to-end demo (`demo/demo.ps1`) | ✅ Verified on Windows PowerShell 5.1 | **17/17 PASS**, exit 0, against the Docker stack (`STORAGE_QUOTA_PER_USER=1MB`) |
| Demo on PowerShell 7+ | ⏳ Not tested | `pwsh` not installed here |
| `demo/securevault.http` | ⏳ Not executed | Needs the VS Code REST Client extension; not available here |
| Storage quota over real HTTP | ✅ Verified | Demo step: 409 after 2 × 400 KB chunks with a 1 MB quota |
| `DB_PASSWORD` / `JWT_SECRET` fail-fast | ✅ Verified | App without `DB_PASSWORD` → `Could not resolve placeholder 'DB_PASSWORD'`, with no DB connection attempt; Compose → `required variable DB_PASSWORD is missing a value`; `RequiredSecretsInitializerTest` |
| Fresh clone, README Option A (Docker) | ✅ Verified | New clone in a temp dir, new volumes: both migrations applied, container healthy, health 200 |
| Fresh clone, README Option B (Maven, Git Bash) | ✅ Verified | Postgres via Compose, `set -a; . ./.env; set +a`, `mvn spring-boot:run` → `Started SecurevaultApplication` |
| Fresh clone `.env` under standard bash (Linux/WSL) | ✅ Verified after fix | Was broken (CRLF → values ending in `\r`); fixed in `5d70bf9` and re-checked in a `bash:5.2` container on a new clone |
| Full Option B on Linux/macOS | ⏳ Not run | Only the `.env` sourcing step was tested under Linux bash |
| Screenshots | ✅ Taken by Alex | 8 images in `docs/screenshots/`, linked in the README; the captions match each image's content |
| Swagger UI in an interactive browser | ✅ Shown by Alex's screenshots | Earlier I had only checked it headlessly |
| `actionlint` on workflows | ⏳ Not run | Not installed. `ci.yml` and `dependency-review.yml` are proven by green runs |

Environment: Temurin JDK 21.0.12.1, Maven 3.10.0, Docker Desktop
(Testcontainers + Compose, `postgres:16-alpine` → PostgreSQL 16.15),
Windows 11, Windows PowerShell 5.1. CI runs on GitHub-hosted `ubuntu-latest`.

### Latest local `mvn -B -ntp verify` (commit `b7ff45d`, 2026-10-22)

```
Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- ApiDocsIntegrationTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuditLogIntegrationTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- AuthIntegrationTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- RequiredSecretsInitializerTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AllowedFileTypeTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- DocumentIntegrationTest
Tests run: 6,  Failures: 0, Errors: 0, Skipped: 0 -- StorageQuotaIntegrationTest
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitFilterTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- JwtAuthenticationFilterTest
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0 -- SecurityRegressionTest
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- LocalFilesystemStorageServiceTest
Tests run: 95, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
Total time:  48.751 s
```

The build also prints two `WARNING`s: Maven can't fetch
`/.meta/prefixes.txt` from two Flyway/Redgate repositories (HTTP 401).
These are optional metadata lookups, and the build succeeds regardless.

One `ERROR`-level log line appears during the run. It is expected: it
comes from `serverErrorsDoNotLeakInternals`, which deliberately causes a
500 and checks that the response body leaks nothing.

### Demo output (`demo/demo.ps1` against the Docker stack)

```
[PASS] Register alice                         201  alice-e4bc761b@example.com
[PASS] Register bob                           201
[PASS] Register alice again (any case) -> 409 409  An account with this email already exists
[PASS] Login alice                            200  JWT issued
[PASS] Login bob                              200  JWT issued
[PASS] Upload report.pdf (alice)              201
[PASS] List documents (alice)                 200  1 document(s)
[PASS] Download (alice)                       200  attachment; filename="report.pdf"; filename*=UTF-8''report.pdf; nosniff=nosniff; bytes identical=True
[PASS] Bob reads alice's document -> 404      404  Document not found
[PASS] Bob downloads alice's document -> 404  404
[PASS] Bob deletes alice's document -> 404    404
[PASS] Bob's list is empty                    200  []
[PASS] HTML disguised as invoice.pdf -> 415   415  Only PDF, PNG, and JPEG files are allowed
[PASS] Upload 25 MB + 1 byte -> 413           413  File exceeds the upload size limit
[PASS] Exceed storage quota -> 409 (after 2 chunk(s)) 409  Upload would exceed your storage quota (821248 of 1048576 bytes used, this file is 409600 bytes)
[PASS] Delete report.pdf (alice)              204
[PASS] Wrong passwords -> 429 (attempt 7)     429  Retry-After: 5s

All steps behaved as expected.
```

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
| Remove the per-user row lock in the quota check | `concurrentUploadsCannotOvershootQuota` (3/3 runs; all 8 concurrent 300-byte uploads succeeded against a 1,024-byte quota) |

## Problems found and fixed in the resume-readiness pass

1. **Committed test signing key** (`fd2908b`). It was replaced with a key
   generated at runtime. The old test-only key remains in git history; it
   never protected anything real.
2. **`DB_PASSWORD` silently defaulted to `changeme`** (`52b7651`).
   Removing the default alone was not enough: Spring Boot's binder kept
   the literal `${DB_PASSWORD}` as the password and failed later with a
   misleading `password authentication failed`. The new
   `RequiredSecretsInitializer` marks both secrets as required, so
   startup stops before any bean is created. Compose uses
   `${DB_PASSWORD:?…}`.
3. **Demo script bugs, caught on first run** (fixed before committing):
   - PowerShell unrolled the enumerable `MultipartFormDataContent`, so
     uploads went out as plain `application/pdf` and got 415 from Spring's
     `consumes` check.
   - `GetValues` threw on a missing header.
   - Windows PowerShell 5.1's `ConvertFrom-Json` doesn't unroll arrays, so
     list counts were wrong.
4. **Binary files at risk from `autocrlf`** (`784bce8`): `.gitattributes`
   marks pdf/png/jpg as binary. `demo/sample.pdf` is pure ASCII, so Git
   would otherwise have treated it as text and converted its line
   endings, breaking its byte offsets.
5. **Fresh-clone trip: CRLF `.env`** (`5d70bf9`). See the table above;
   `.env.example` is now always checked out with LF.
6. **Rate limiting under Docker**: every host request arrives from the
   bridge gateway (`172.18.0.1` in `audit_log`), so all clients share one
   bucket. Documented as a limitation; trusted-proxy handling is listed in
   Future improvements.
7. **Demo PowerShell 7 claim** (`c2ab0cd`): corrected to "tested on 5.1
   only".

## What's in the MVP

| Area | Delivered |
|---|---|
| Auth | Register / login / `GET /api/users/me`; BCrypt; HS256 JWT checked on signature, algorithm, expiry, issuer, subject; user must exist |
| Rate limiting | Bucket4j + Caffeine (bounded, expiring). Keyed on IPv4 address / IPv6 /64, never `X-Forwarded-For`. Paths matched like Spring Security. 429 + `Retry-After` |
| Documents | Owner-scoped upload, list, get, download, delete; another user's document = same 404 as missing |
| Upload safety | PDF/PNG/JPEG by magic bytes; 25 MB per file (413); server-generated storage keys; lexical + real-path checks; sanitized filenames |
| Storage quota | Per user (`STORAGE_QUOTA_PER_USER`, default 1GB); 409 when exceeded; enforced under a row lock |
| Downloads | Attachment, RFC 5987 filename, detected `Content-Type`, `nosniff` |
| Audit | `audit_log` for register/login/upload/download/delete, success and failure; `REQUIRES_NEW` |
| Secrets | No defaults for `JWT_SECRET` / `DB_PASSWORD`; fail-fast; test key generated at runtime |
| Container | Multi-stage Dockerfile, non-root uid 10001, digest-pinned bases, healthcheck; Compose app service with read-only root fs, `cap_drop: ALL`, `no-new-privileges` |
| API docs | springdoc-openapi 3.1.1; Swagger UI + `/v3/api-docs`; bearer auth documented |
| CI/CD | `ci.yml` (`mvn verify`), `dependency-review.yml` (high/critical), `codeql.yml` (default suite), Dependabot (Maven; Actions with CodeQL grouped; runtime image held on Java 21); actions SHA-pinned; least-privilege permissions |
| Demos | `demo/demo.ps1` (17 checked steps), `demo/securevault.http` + `sample.pdf`, 8 screenshots |
| Docs | README: setup (Docker and Maven), config, demo, API + error contract, screenshots, Mermaid flow + ER diagrams, security design, threat model, testing guide, CI/CD, tradeoffs, limitations, future improvements |

## Decisions made during implementation

- Endpoints live under `/api/...`
- Login and register share one rate-limit bucket per client key
- Over-quota uploads return **409**, not 413 (413 means this file is too
  large) or 507 (implies a server-side storage failure)
- Required secrets have no dev fallback; the dev values live in `.env`,
  copied from `.env.example`, rather than in a Spring `dev` profile
- The Docker build skips tests (they need Docker; CI runs them)
- CodeQL uses the default query suite only, not `security-extended`, to
  keep false positives down
- Actions and base images use the newest release that is at least two
  weeks old, pinned by SHA/digest; Dependabot proposes newer ones
- `audit_log` has no foreign keys; the quota counts `documents.size_bytes`
- API docs are public (documented; can be switched off)
- Docker images stay on Java 21 (LTS): Dependabot ignores
  `eclipse-temurin` versions `>= 22`
- The `maven` build image is bumped manually: its tag shows the JDK only
  in a suffix that Dependabot ignore rules can't filter, so it is
  excluded from Dependabot entirely (digest refreshes included)
- All `github/codeql-action/*` updates arrive as one grouped Dependabot PR,
  because every CodeQL step in a workflow must run the same version

## Known limitations

These are documented in the README, along with Future improvements.

- Polyglot files (leading magic bytes only)
- Orphaned files on partial failure (logged; no cleanup; not counted in
  the quota)
- Per-user quota only; no global cap or per-account overrides
- Uploads aren't rate-limited
- No token revocation or logout
- The rate limiter is in-memory and keyed on the direct peer. Behind a
  proxy, or in Docker, all clients share one bucket
- `bearer` (lowercase scheme) is rejected (fail-closed)
- Old test-only JWT key still in git history (not a credential)

## Next steps

1. Push the housekeeping commits and confirm that CI and CodeQL are green.
2. Bump the `maven` build image by hand from time to time. Alternatively,
   move the build stage to an `eclipse-temurin:21-jdk` image plus a Maven
   wrapper, so Dependabot can track it.
3. Optional: run the demo on PowerShell 7, and try `securevault.http` in
   VS Code.
4. Future improvements as listed in the README (next most useful:
   documenting error responses in OpenAPI, and trusted-proxy rate
   limiting).
