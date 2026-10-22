# SecureVault API

A security-focused document management REST API. Users register, authenticate,
and upload/view/download/delete their own documents, with enforced ownership
and defense against broken object-level authorization (BOLA/IDOR).

**Status:** registration, JWT login, per-IP auth rate limiting, audit
logging, and owner-scoped document upload/list/get/download/delete are
implemented, covered by integration tests, and smoke-tested by hand. See
`PROJECT_STATUS.md` for details.

## API

| Method | Path | Auth | Notes |
|---|---|---|---|
| POST | `/api/auth/register` | public | `{email, password}` → 201 |
| POST | `/api/auth/login` | public | → `{accessToken, tokenType, expiresIn}` |
| GET | `/api/users/me` | bearer | |
| POST | `/api/documents` | bearer | multipart field `file`; PDF/PNG/JPEG, ≤ 25 MB |
| GET | `/api/documents` | bearer | your documents only |
| GET | `/api/documents/{id}` | bearer | metadata |
| GET | `/api/documents/{id}/download` | bearer | attachment |
| DELETE | `/api/documents/{id}` | bearer | → 204 |
| GET | `/actuator/health` | public | |

## Stack

Java 21, Spring Boot 4.1.1, Spring Security, Spring Data JPA, PostgreSQL,
Flyway, Docker Compose, JWT (JJWT), JUnit 5 + Testcontainers.

## Prerequisites

- Java 21 (`java -version`)
- Maven (`mvn -version`) or use `./mvnw` once the wrapper is added
- Docker + Docker Compose

## Getting started

```bash
cp .env.example .env
# edit .env - at minimum set a real JWT_SECRET (openssl rand -base64 48)

docker compose --env-file .env up -d      # starts Postgres

# Maven does not read .env - export the variables first (JWT_SECRET has no
# default and the app refuses to start without it)
set -a; . ./.env; set +a
mvn spring-boot:run                        # starts the app on :8080

curl http://localhost:8080/actuator/health
# expect: {"status":"UP"}
```

## Security decisions so far

- **Auth strategy: stateless JWT**, not server-side sessions. Chosen because
  this is a pure REST API (no server-rendered pages), which makes stateless
  tokens the simpler fit, and because it sidesteps CSRF entirely: CSRF
  exploits a browser's automatic cookie attachment, and a bearer token in an
  `Authorization` header is never attached automatically. See the comment in
  `SecurityConfig` for the full reasoning.
- **Protected by default.** Every endpoint requires authentication unless
  explicitly added to the public allow-list in `SecurityConfig`.
- **Ownership enforced in every query.** Documents are always looked up by
  `(id, owner_id)`; another user's document returns the same 404 as a
  missing one, so ids can't be probed.
- **Uploads are identified by content, not by what the client claims.**
  Only PDF, PNG, and JPEG magic bytes are accepted; the stored type and
  extension come from the bytes. Files are stored as
  `{ownerId}/{documentId}.{ext}` (never a user-supplied name), and every
  storage access verifies the symlink-resolved path stays inside the root.
- **Downloads are always attachments** with the detected `Content-Type`
  and `X-Content-Type-Options: nosniff`.
- **Schema owned by Flyway, not Hibernate.** `ddl-auto: validate` means
  Hibernate checks entities against the schema at startup but never
  generates or alters tables itself.

## Running tests

```bash
mvn test    # requires Docker running (Testcontainers starts PostgreSQL)
```

## Known tradeoffs

- **Registration reveals whether an email is registered.** `POST
  /api/auth/register` returns `409 Conflict` for an existing email, so
  anyone can probe which addresses have accounts. Login does *not* leak
  this (unknown email and wrong password give the same 401, with matching
  timing). This is accepted for the MVP because the alternative - always
  returning the same response and notifying the address owner out of band
  - needs an email-delivery flow that is out of scope. Mitigations in
  place: the per-IP rate limit on `/api/auth/register` slows bulk probing,
  and every duplicate attempt is recorded in `audit_log`
  (`REGISTER`/`FAILURE`).

## Known limitations (current milestone)

- No CI pipeline yet.
- Rate limiting is in-memory (resets on restart, not shared across
  instances) and keys on the full client address, so an IPv6 client
  controlling a /64 can rotate addresses.
- File type checks look at leading magic bytes only; downloads are
  attachment + nosniff to contain polyglot files.
- Local filesystem storage only; no document versioning.

## License

Personal portfolio project.
