# SecureVault API — Project Status

Last updated: 2026-10-22 (security pass, API docs, CI/CD, docs: MVP complete)

This file is the shared source of truth on project state for Alex,
Claude, and ChatGPT. Update it after every milestone with real results —
never with a claim that hasn't been verified by actually running the
build/tests.

## Verification status

Environment: Temurin JDK 21.0.12.1, Maven 3.10.0, Docker Desktop
(Testcontainers + Compose, `postgres:16-alpine` → PostgreSQL 16.15),
Windows 11.

Latest `mvn -B -ntp verify` (the exact CI command) on commit `cade834`.
Only documentation has changed since.

```
Tests run: 2,  Failures: 0, Errors: 0, Skipped: 0 -- ApiDocsIntegrationTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuditLogIntegrationTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- AuthIntegrationTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AllowedFileTypeTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- DocumentIntegrationTest
Tests run: 8,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitFilterTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- JwtAuthenticationFilterTest
Tests run: 31, Failures: 0, Errors: 0, Skipped: 0 -- SecurityRegressionTest
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- LocalFilesystemStorageServiceTest
Tests run: 85, Failures: 0, Errors: 0, Skipped: 0
Building jar: target/securevault-0.1.0-SNAPSHOT.jar  (spring-boot repackage OK)
BUILD SUCCESS
```

One `ERROR`-level log line appears during the run. It is expected: it
comes from `serverErrorsDoNotLeakInternals`, which deliberately causes a
500 and checks that the response body leaks nothing.

**Not yet verified:** the GitHub Actions workflows have not run on GitHub,
because no remote repository exists yet. The YAML parses, every action is
pinned to a commit SHA that resolved via the GitHub API, and the CI
command itself passes locally. `actionlint` was not run (not installed).

### Mutation checks

For each row, the code was broken on purpose, the listed tests were
confirmed to fail, and the code was restored:

| Broken on purpose | Test(s) that failed |
|---|---|
| `AuditService` `REQUIRES_NEW` → `REQUIRED` | `duplicateRegistrationFailureSurvivesTransactionRollback` |
| Remove rate-limit cache `maximumSize` + `expireAfterAccess` | `idleIpBucketIsEvictedAfterOneRefillPeriod`, `numberOfTrackedIpsIsBounded` |
| Key rate limit on raw address instead of IPv6 /64 | `ipv6AddressesInSameSlash64ShareABucket`, `ipv4IsKeyedByFullAddressAndMappedIpv6CountsAsIpv4` |
| Remove real-path (symlink) checks in storage | `symlinkInsideRootPointingOutsideIsRejected` |
| Document lookup `findByIdAndOwnerId` → `findById` | `otherUsersDocumentIsIndistinguishableFromMissing`, `documentEventsAreAudited` |
| Rate-limit filter matching the raw URI (the code before `e49dad7`) | `loginCannotBeReachedAroundTheRateLimit` for `%6Cogin` and `%61uth` |

### Manual smoke tests (real HTTP via curl, `mvn spring-boot:run`, Compose Postgres)

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
  | Another user's get / download / delete | 404 each |
  | Bad id / no token | 400 / 401 |
  | Delete | 204, file removed from disk |

- **Rate-limit bypass fix on real Tomcat:** after exhausting the limit,
  valid credentials sent to `/api/auth/%6Cogin` and `/api/%61uth/login`
  got 429. Before the fix, the same requests logged in (MockMvc
  reproduction: 200).
- **API docs:**
  - `/swagger-ui.html` → 302 to `/swagger-ui/index.html` (200), with
    `swagger-ui-bundle.js` and `/v3/api-docs/swagger-config` both 200.
  - `/v3/api-docs` is OpenAPI 3.1.0: `bearerAuth` (http/bearer/JWT) is
    the global requirement, and register/login have `security: []`.
  - Rendered in headless Chrome (`--dump-dom` after JS ran): title
    "SecureVault API", all 6 paths as operation blocks, the Authorize
    button present, no error panel.
  - The Claude-in-Chrome extension was not connected, so this was not
    checked in an interactive browser.

## Git history

The planning-session history was not preserved; this repo was initialized
fresh locally with one baseline commit containing the scaffold.

| # | Commit | Summary |
|---|---|---|
| 1 | `1aa60e7` | chore: import SecureVault MVP scaffold from planning session |
| 2 | `370bea5` | fix(build): use Boot-managed Testcontainers 2.x and add Flyway Postgres module |
| 3 | `47409e8` | fix(security): harden draft JwtService and JwtAuthenticationFilter |
| 4 | `a855b5d` | feat(auth): add registration, login, and GET /api/users/me |
| 5 | `f8d248a` | feat(security): add per-IP rate limiting on auth endpoints |
| 6 | `4c5ee53` | feat(audit): record register and login events in audit_log |
| 7 | `b35e11b` | docs: record verified build and test results for auth milestone |
| 8 | `bf6e5c3` | test(security): verify rate-limit bucket cache eviction and size bound |
| 9 | `a4edbfb` | docs: document duplicate-email 409 enumeration tradeoff |
| 10 | `430669c` | feat(storage): add StorageService with path-safe local filesystem impl |
| 11 | `0f56398` | feat(documents): add magic-byte detection for PDF, PNG, and JPEG |
| 12 | `1835019` | feat(documents): add owner-scoped upload, list, get, download, delete |
| 13 | `2fd0a5a` | docs: record document milestone results, smoke tests, and open risks |
| 14 | `16209d8` | fix(security): rate-limit IPv6 clients by /64 prefix |
| 15 | `d0ac41b` | test: extract JWT forging helpers into TestTokens |
| 16 | `e49dad7` | fix(security): close rate-limit bypass via percent-encoded auth paths |
| 17 | `9808879` | feat(docs): add springdoc-openapi with bearer auth scheme |
| 18 | `cade834` | ci: add GitHub Actions build, dependency review, and Dependabot |

## Milestones

### Registration + JWT auth — done
Register / login / `GET /api/users/me`; HS256 JWT checked on signature,
algorithm, expiry, issuer, and subject; BCrypt; `ApiError` everywhere;
rate limiting (Bucket4j `bucket4j_jdk17-core:8.21.0` + Caffeine); audit
log with `REQUIRES_NEW`.

### Document endpoints — done
Owner-scoped upload, list, get, download, and delete:
- **Types:** PDF/PNG/JPEG identified by magic bytes.
- **Size:** 25 MB limit.
- **Storage:** server-generated `{ownerId}/{documentId}.{ext}` keys, with
  lexical and real-path checks against the storage root.
- **Downloads:** attachment + `nosniff`.
- **Audit:** upload/download/delete events, on success and failure.

### Security test pass — done

`SecurityRegressionTest` (31 cases) was written against
`PROJECT_SPEC.md` and asserts the secure behavior. Each failure was
treated as a finding.

**Gaps found:**

1. **Real vulnerability: rate-limit bypass via percent-encoding** (fixed
   in `e49dad7`). The filter compared the raw request URI, while Spring
   Security and MVC match the decoded path. So `POST /api/auth/%6Cogin`
   reached the login handler with no rate limit, allowing unlimited
   password guessing. The filter now uses `PathPatternRequestMatcher`,
   the same matching Spring Security uses. Confirmed fixed in MockMvc
   and on real Tomcat.
2. **Real weakness: IPv6 rate-limit granularity** (fixed in `16209d8`).
   One client controlling a /64 could rotate through 2^64 buckets. IPv6
   is now keyed by its /64, IPv4 is unchanged, and IPv4-mapped IPv6
   counts as IPv4.
3. **Test coverage gaps** (code was correct, now tested). Token attacks
   were only tested against `/api/users/me`; cross-user access lacked
   uppercase-UUID and client-supplied `ownerId` cases; malformed ids were
   tested on `GET` only; error-body leakage, auth path variants, and
   actuator exposure were untested.
4. **Not a vulnerability: `/actuator` discovery index** returns 200 when
   authenticated, but it lists `health` only. The test now pins that.

**Checked, no gap:**
- 13 bad-credential variants × all 5 document endpoints → 401, with no
  side effects. The variants: none, empty, garbage, lowercase scheme,
  expired, swapped subject, wrong issuer, unsigned, HS384, foreign key,
  no subject, unknown user, token in query param.
- Path variants (trailing slash, `//`, `./`, `;params`, uppercase) never
  reach the login handler.
- Uppercase spelling of a UUID is still owner-checked.
- A malformed id is 400 on every endpoint.
- An injected `ownerId` / `owner_id` param is ignored.
- Wrong part name → 400; non-multipart body → 415; a filename made only
  of control characters → `document.pdf`.

### API docs — done
- springdoc-openapi-starter-webmvc-ui **3.1.1**: confirmed as the latest
  release on Maven Central (2026-09-06), built against Boot 4.1.0, and
  verified working on 4.1.1.
- Swagger UI and `/v3/api-docs` are public. Bearer auth is documented
  globally, and register/login opt out.

### CI/CD — done (not yet run on GitHub)
- **`ci.yml`:** `mvn -B -ntp verify` on push/PR to `main`, with JDK 21
  Temurin and a Maven cache.
- **`dependency-review.yml`:** `fail-on-severity: high`.
- **`dependabot.yml`:** weekly Maven updates (minor/patch grouped) and
  weekly Actions updates.
- **Action versions:**
  - checkout v7.0.1 and dependency-review-action v5.0.0 are the latest
    releases.
  - setup-java v6.0.1: v6.1.0 was only 8 days old, so the previous
    release was used.
- **Hardening:** actions are pinned to commit SHAs, permissions are
  `contents: read`, and `persist-credentials: false`.

## Decisions made during implementation

- Endpoints live under `/api/...` (matches `SecurityConfig`)
- Login and register share one rate-limit bucket per client key
  (IPv4 address / IPv6 /64)
- The rate-limit filter matches paths with `PathPatternRequestMatcher`,
  so "rate-limited" and "routes to login" can never disagree
- Failed logins record the targeted user id (if the email exists) as
  `resource_id` with null `actor_id`; the attempted email is never stored
- `audit_log` has no foreign keys so records outlive users/documents
- Success audit rows also use `REQUIRES_NEW` (a rare commit failure after
  flush could leave a success row for a rolled-back action; accepted)
- `Document` ids are assigned in Java so the storage key is known before
  the insert; if the insert fails, the stored file is deleted
- Delete removes the row first, then the file (a failure leaves a logged
  orphan, not a dangling row)
- `nosniff` relies on Spring Security's default headers; the tests fail if
  that default is disabled
- API docs are public (documented tradeoff; switchable off via
  `springdoc.*.enabled=false`)

## Open issues / risks (documented, not fixed)

- Polyglot files: magic bytes are checked only at the start of the file;
  contained by attachment + `nosniff` + detected type
- Orphaned files on partial failure (logged); no cleanup job
- No per-user upload quota (disk exhaustion by an authenticated user)
- No token revocation or logout; 60-minute TTL
- The rate limiter is in-memory and keyed on the direct peer address;
  behind a reverse proxy it would need trusted-proxy handling
- `bearer` (lowercase scheme) is rejected; RFC 6750 allows any case
  (fail-closed, left as is)

## Next steps

1. Create the GitHub repo, push, and confirm the CI run is green. Enable
   the dependency graph (and GitHub Advanced Security if the repo is
   private) so Dependency Review works.
2. Decide on the open risks above (upload quota is the most practical).
3. Deferred per spec: S3 storage, versioning, admin role, per-account
   lockout, cloud deployment, CodeQL.

## Known constraints

- No cloud deployment target chosen yet (explicitly deferred)
- No admin role in MVP; no audit read endpoint
- No document versioning in MVP
- No per-account login lockout in MVP (rate limiting only)
- Rate-limit state is in-memory: resets on restart, not shared across instances
- Local filesystem storage only (S3 deferred)
