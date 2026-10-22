# SecureVault API

[![CI](https://github.com/alxrxy/securevault-api/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/alxrxy/securevault-api/actions/workflows/ci.yml)

A security-focused document management REST API. Users register, log in
with a JWT, and upload, list, view, download, and delete **their own**
documents. The project is built around defending the usual weak spots of
file-handling APIs: broken object-level authorization (BOLA/IDOR), path
traversal, content-type spoofing, credential brute force, and information
leakage through errors.

**Status:** MVP complete. 91 tests pass (`mvn verify`, real PostgreSQL
via Testcontainers) locally and in GitHub Actions. The flows were
smoke-tested by hand over real HTTP. See [`PROJECT_STATUS.md`](PROJECT_STATUS.md) for
verified results and [`PROJECT_SPEC.md`](PROJECT_SPEC.md) for the agreed
design.

## Stack

Java 21 · Spring Boot 4.1.1 (Spring Framework 7, Spring Security 7) ·
Spring Data JPA / Hibernate 7 · PostgreSQL 16 · Flyway · JJWT 0.13 ·
Bucket4j + Caffeine · springdoc-openapi 3.1 · JUnit 5 + Testcontainers ·
Docker Compose · GitHub Actions

## Getting started

Prerequisites: Java 21, Maven 3.9+, Docker (with Compose).

```bash
cp .env.example .env
# Edit .env. At minimum set JWT_SECRET:  openssl rand -base64 48

docker compose --env-file .env up -d     # PostgreSQL on :5432

# Maven doesn't read .env, so export it first. JWT_SECRET has no default
# and the app refuses to start without it.
set -a; . ./.env; set +a                 # bash/zsh
mvn spring-boot:run                      # app on :8080

curl http://localhost:8080/actuator/health   # {"groups":[...],"status":"UP"}
```

PowerShell equivalent of the export step:
`Get-Content .env | Where-Object { $_ -match '^\w+=' } | ForEach-Object { $k, $v = $_ -split '=', 2; Set-Item "env:$k" $v }`

Flyway creates the schema on first start (`V1__init.sql`, `V2__audit_log.sql`).

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | **none (required)** | Base64 HMAC key, ≥ 32 bytes decoded. `openssl rand -base64 48` |
| `JWT_EXPIRATION_MINUTES` | `60` | Access-token lifetime |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | `localhost` / `5432` / `securevault` | PostgreSQL location |
| `DB_USERNAME` / `DB_PASSWORD` | `securevault` / `changeme` | PostgreSQL credentials (change outside local dev) |
| `AUTH_RATE_LIMIT_PER_MINUTE` | `10` | Login + register requests per client (IPv4 address or IPv6 /64) |
| `STORAGE_ROOT` | `./data/storage` | Where uploaded files are written; keep it outside any web-served directory |
| `STORAGE_QUOTA_PER_USER` | `1GB` | Total bytes of documents one user may store (`500MB`, `5GB`, ...) |
| `SERVER_PORT` | `8080` | HTTP port |

### Running the tests

```bash
mvn verify      # compile + all tests + package; Docker must be running
```

## API

Interactive docs are at **`/swagger-ui.html`** and the OpenAPI spec is at
**`/v3/api-docs`**. In Swagger UI, click **Authorize** and paste a token
from `/api/auth/login`.

| Method | Path | Auth | Success | Notes |
|---|---|---|---|---|
| POST | `/api/auth/register` | public | 201 | `{email, password}`; password 12–72 chars (≤ 72 UTF-8 bytes) |
| POST | `/api/auth/login` | public | 200 | → `{accessToken, tokenType: "Bearer", expiresIn}` |
| GET | `/api/users/me` | bearer | 200 | Current user (never includes the password hash) |
| POST | `/api/documents` | bearer | 201 | `multipart/form-data`, field `file`; PDF/PNG/JPEG only, ≤ 25 MB, within your storage quota |
| GET | `/api/documents` | bearer | 200 | Your documents, newest first |
| GET | `/api/documents/{id}` | bearer | 200 | Metadata |
| GET | `/api/documents/{id}/download` | bearer | 200 | Always an attachment |
| DELETE | `/api/documents/{id}` | bearer | 204 | Removes the row and the stored file, freeing its quota |
| GET | `/actuator/health` | public | 200 | The only actuator endpoint exposed |

Every error returns the same shape, with no stack traces or internal
details:

```json
{"timestamp":"…","status":404,"error":"Not Found","message":"Document not found","path":"/api/documents/…"}
```

| Status | When |
|---|---|
| 400 | Invalid input, malformed id, empty file, malformed multipart |
| 401 | Missing/invalid token, or wrong email/password on login |
| 404 | Document doesn't exist **or belongs to someone else** (deliberately indistinguishable) |
| 409 | Email already registered, **or** the upload would push you over your storage quota |
| 413 | A single file is over 25 MB |
| 415 | File content isn't PDF, PNG, or JPEG |
| 429 | Too many login/register requests from your address (`Retry-After` header) |

**413 vs. 409 on upload:** 413 means *this file* is too large (over the
25 MB per-file limit), whatever you have stored. 409 means the file is
acceptable on its own, but your stored documents plus this one would
exceed `STORAGE_QUOTA_PER_USER`. The message says how much you've used.
Deleting documents frees quota.

Example:

```bash
B=http://localhost:8080
curl -s -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"a-long-passphrase"}' $B/api/auth/register
TOKEN=$(curl -s -H 'Content-Type: application/json' -d '{"email":"me@example.com","password":"a-long-passphrase"}' $B/api/auth/login | jq -r .accessToken)
curl -s -H "Authorization: Bearer $TOKEN" -F "file=@report.pdf" $B/api/documents
curl -s -H "Authorization: Bearer $TOKEN" $B/api/documents
```

## Architecture

```
HTTP request
  │
  ├─ Spring Security filter chain ─────────────────────────────────────────
  │    AuthRateLimitFilter    per-client token bucket on login/register → 429
  │    JwtAuthenticationFilter  Bearer token → AuthenticatedUser(id,email,role)
  │    authorizeHttpRequests  public allow-list, everything else authenticated
  │    RestAuthenticationEntryPoint  → 401 ApiError
  │
  ├─ Controllers (auth, user, document)  read the principal; never trust
  │                                      client-supplied owner ids
  ├─ Services
  │    AuthService      register / login (AuthenticationManager + BCrypt)
  │    DocumentService  validate → sniff type → store file → insert row;
  │                     every lookup is WHERE id = ? AND owner_id = ?
  │    AuditService     writes audit_log in its own transaction (REQUIRES_NEW)
  ├─ StorageService ── LocalFilesystemStorageService  {ownerId}/{docId}.{ext}
  └─ PostgreSQL (Flyway-owned schema): app_users, documents, audit_log
```

| Package | Contents |
|---|---|
| `auth` | Register/login controller, DTOs, `AuthService` |
| `user` | `AppUser` entity, repository, `GET /api/users/me` |
| `document` | `Document` entity, owner-scoped repository, `DocumentService`, controller, `AllowedFileType` (magic bytes) |
| `storage` | `StorageService` interface and the path-safe local filesystem implementation |
| `security` | `SecurityConfig`, `JwtService`, JWT filter, rate-limit filter, entry point |
| `audit` | `audit_log` entity, repository, `AuditService` |
| `exception` | `ApiError` and `GlobalExceptionHandler` |
| `config` | OpenAPI metadata (bearer scheme) |

## Security design

- **Stateless JWT, not sessions.** This is a pure REST API, and a bearer
  token in the `Authorization` header is never attached automatically by
  a browser. That is why CSRF protection is disabled; the full reasoning
  is in `SecurityConfig`. Tokens are HS256 only. Each one is checked for
  signature, algorithm, expiry (30 s clock skew), issuer, and subject, and
  the user must still exist.
- **Protected by default.** Only the endpoints in an explicit allow-list
  are public.
- **Ownership is enforced in the query, not afterwards.** There is no code
  path that loads a document by id alone. The owner always comes from the
  token, never from the request.
- **Uploads are identified by their bytes.** The client's `Content-Type`
  and filename extension are ignored. The stored type and the on-disk
  extension come from magic-byte detection.
- **Storage paths are server-generated** and checked twice: once
  lexically, and once against the real path after following symlinks.
  The original filename is display-only and is sanitized.
- **Downloads are always attachments,** with the detected `Content-Type`
  and `X-Content-Type-Options: nosniff`. The filename is RFC 5987-encoded.
- **Audit trail.** Login, registration, upload, download, and delete
  events are recorded on success and failure, with the actor, target, and
  IP. Failure records survive the rollback of the action they describe.
  Tokens, passwords, attempted emails, and file contents are never logged.
- **Schema owned by Flyway** (`ddl-auto: validate`).

## Threat model

**Assets:** users' documents, account credentials, and the JWT signing key.
**Actors:** anonymous internet clients, authenticated users acting
against other users, and an attacker with a stolen token.
**Trust boundary:** the HTTP interface. Nothing in a request (headers,
filenames, content types, ids, `X-Forwarded-For`) is trusted.

| Threat | Mitigation | Verified by |
|---|---|---|
| Accessing another user's document by id (BOLA/IDOR) | Owner-scoped queries; 404 identical to a missing document; denied attempts audited | `SecurityRegressionTest`, `DocumentIntegrationTest` (fail if ownership is removed) |
| Forged or altered tokens (`alg: none`, algorithm switch, wrong key, swapped subject, expired, wrong issuer) | Pinned HS256 plus full claim validation; failures return a generic 401 | 13 credential variants × every document endpoint |
| Password brute force / credential stuffing | Per-client bucket (IPv4 address, IPv6 /64) matched on the decoded path; `X-Forwarded-For` ignored | `AuthRateLimitTest`, `AuthRateLimitFilterTest`, path-variant tests |
| Login user enumeration | Unknown email and wrong password give the same 401; Spring's `DaoAuthenticationProvider` runs a dummy BCrypt check for unknown emails (timing not measured here) | `AuthIntegrationTest` |
| Path traversal / symlink escape in storage | Server-generated keys; lexical check plus real-path check; `CREATE_NEW` and `NOFOLLOW_LINKS` on write | `LocalFilesystemStorageServiceTest` |
| Malicious content served from the API origin (stored XSS) | Allow-list of types by magic bytes; attachment-only downloads; `nosniff`; detected `Content-Type`; extension forced to match the type | `AllowedFileTypeTest`, `DocumentIntegrationTest` |
| Header injection via filename | Control characters stripped; RFC 5987 `Content-Disposition` | `DocumentIntegrationTest` |
| Information leakage in errors | Fixed client messages; details logged server-side only; only `health` exposed on actuator | `serverErrorsDoNotLeakInternals`, `onlyHealthActuatorEndpointIsExposed` |
| Repudiation | `audit_log` written with `REQUIRES_NEW` | `AuditLogIntegrationTest` (fails if propagation is `REQUIRED`) |
| Resource exhaustion (huge uploads, filling the disk, rate-limiter memory) | 25 MB per file (servlet + service); per-user quota enforced under a row lock so concurrent uploads can't overshoot; bucket cache bounded and expiring | `StorageQuotaIntegrationTest` (fails without the lock), plus a manual 25 MB / 25 MB + 1 check |
| Vulnerable dependencies | Dependabot; Dependency Review blocks high/critical on PRs | CI config |

## Known tradeoffs

- **Registration reveals whether an email is registered.** `POST
  /api/auth/register` returns `409 Conflict` for an existing email, so
  anyone can probe which addresses have accounts. Login does *not* leak
  this. The tradeoff is accepted for the MVP because the alternative
  (always return the same response and notify the address owner out of
  band) needs an email-delivery flow that is out of scope. Mitigations:
  the per-client rate limit on `/api/auth/register` slows bulk probing,
  and every duplicate attempt is recorded in `audit_log`
  (`REGISTER`/`FAILURE`).
- **API docs are public.** `/swagger-ui.html` and `/v3/api-docs` describe
  the API without authentication. Every operation they list is still
  protected. To turn them off, set `springdoc.api-docs.enabled=false` and
  `springdoc.swagger-ui.enabled=false`.

## Known limitations

- **Polyglot files.** Type detection checks the leading magic bytes only,
  so a file can begin with a valid PDF/PNG/JPEG signature and carry other
  content after it. This is contained by attachment-only downloads,
  `nosniff`, and the server-detected `Content-Type`. Full format
  validation or re-encoding is out of MVP scope.
- **Orphaned files.** If the database insert fails after a file is
  stored, the file is deleted. If deleting a file fails after its row was
  removed, the file stays on disk and a warning is logged. There is no
  cleanup job.
- **The storage quota is per user, not global.** It caps each account,
  but many accounts can still fill the disk together, and registration
  is only rate-limited per client address. Usage is counted from
  document metadata. Orphaned files (see above) are not counted. The
  same quota applies to every user; there are no per-account overrides.
- **Uploads aren't rate-limited.** Only login and register are. Uploads
  are bounded by the 25 MB per-file limit and the quota.
- **No token revocation.** Tokens stay valid until they expire (default
  60 minutes). There is no logout or refresh token. Rotating `JWT_SECRET`
  invalidates every token.
- **The rate limit is in-memory.** It resets on restart and isn't shared
  across instances (a Redis-backed Bucket4j store would be the fix). It
  keys on the direct client address. Behind a reverse proxy, every client
  would share the proxy's address until trusted-proxy handling is added.
- **Bearer scheme case.** The `Authorization` scheme must be written
  exactly `Bearer`. A lowercase `bearer` is rejected (fail-closed),
  although RFC 6750 allows any case.
- **Single node, local filesystem storage.** S3 storage, document
  versioning, an admin role, per-account lockout, and cloud deployment are
  deferred (see `PROJECT_SPEC.md`).
- **CI caveat.** The Dependency Review Action needs the repository's
  dependency graph enabled. On private repositories it also needs GitHub
  Advanced Security.

## License

Personal portfolio project.
