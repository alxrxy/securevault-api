# SecureVault API — Agreed Architecture & Implementation Spec

Finalized between Claude and ChatGPT with Alex on 2026-10-21. This is the
source of truth going into local implementation with Claude Code — if a
new decision is needed mid-build, record it here and in PROJECT_STATUS.md
rather than letting it live only in chat history.

## Stack (locked)
- Java 21, Spring Boot 4.1.1, Spring Framework 7, Spring Security 7 (lambda DSL only)
- PostgreSQL + Flyway (`ddl-auto: validate` — Flyway owns the schema)
- `spring-boot-starter-webmvc` (not the deprecated `-web` starter)
- JJWT 0.13.0 for JWT signing/verification
- Docker Compose for local Postgres

## Auth (locked)
- Stateless JWT via `Authorization: Bearer` header
- Token validated on: signature, expiration, issuer, and algorithm
- BCrypt password hashing
- CSRF disabled (justified: no cookie-based auth, nothing for CSRF to exploit)
- No admin role for MVP; `role` column kept on `app_users` for future use
- Custom `AuthenticatedUser(id, email, role)` principal so ownership checks
  compare `principal.id()` directly to `documents.owner_id` — no extra lookup

## File uploads
- Max size: 25 MB
- Allowed types for MVP: PDF, PNG, JPEG only
- Validate actual file content (magic-byte sniffing), never trust the
  client's `Content-Type` header alone
- Downloads: served as attachments, with `X-Content-Type-Options: nosniff`
- Duplicate filenames: **allowed** — no uniqueness constraint. Storage is
  keyed by server-generated UUID, so there's no technical collision to
  guard against; the original filename is display-only metadata.
- No versioning in MVP (explicit scope cut, documented as future work)

## Storage
- `StorageService` interface; local filesystem implementation for MVP
  (S3 implementation deferred to a later portfolio project)
- Layout: `storage-root/{ownerUuid}/{documentUuid}.{ext}` — filenames on
  disk are always server-generated, never derived from user input
- Storage root lives outside any web-served directory
- Before any read/write: resolve the canonical real path and verify it is
  still under the storage root (defends against symlink escapes, not just
  `../` string tricks)
- Authorization is enforced independently of the filesystem path — always
  check `owner_id` in the database, never infer ownership from where a
  file happens to live

## Rate limiting
- Bucket4j (verify current version/API before pinning — don't guess it)
- In-memory, per-IP, applied to `/auth/login` and `/auth/register`
- Pair with a bounded/expiring cache (e.g. Caffeine) so per-IP buckets for
  addresses that stop making requests get evicted — otherwise it's a slow
  memory leak
- Use `request.getRemoteAddr()` directly — do NOT trust `X-Forwarded-For`
  or other proxy headers (no reverse proxy in front of this yet, so the
  header is client-settable and would make the limiter bypassable)
- Exceeding the limit returns HTTP 429
- No per-account lockout in MVP (explicit scope cut — avoids the
  complexity of failed-attempt tracking/reset flows and the
  user-enumeration risk a lockout message can introduce)
- Documented limitation: in-memory state means this doesn't survive a
  restart and doesn't work across multiple instances; a Redis-backed
  implementation is the stated answer for horizontal scaling

## Audit logging
- New table: `audit_log` (actor_id, action, resource_type, resource_id,
  outcome, timestamp, ip, ...)
- Log auth events (login success/failure) and document events
  (upload/download/delete)
- **Audit writes for failed/denied actions must survive the rollback of
  the action they're logging** — use `Propagation.REQUIRES_NEW` on the
  audit-write method, or an `AFTER_ROLLBACK` transactional event listener.
  A naive same-transaction insert disappears along with the failure it's
  supposed to record.
- Never log JWTs, passwords, or file contents
- No read endpoint yet (no admin role in MVP) — it just needs to exist
  and be populated correctly

## API documentation
- springdoc-openapi for an auto-generated OpenAPI spec + Swagger UI
- `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.1` is the
  version ChatGPT found documented against Spring Boot 4 — confirm this
  against the live Maven Central listing before pinning it in `pom.xml`,
  and actually load Swagger UI + `/v3/api-docs` against the running Boot
  4.1.1 app before considering this done, since neither of us has
  compiled it yet.

## CI / DevSecOps
- GitHub Actions: `mvn verify` (compile + full test suite, including
  Testcontainers — works out of the box on GitHub-hosted runners, Docker
  is preinstalled)
- Dependabot (`.github/dependabot.yml`) for ongoing dependency vulnerability alerts
- GitHub's Dependency Review Action on PRs, failing the build on
  high/critical vulnerable dependencies
- CodeQL / static analysis: explicitly deferred to post-MVP

## Deferred / explicit scope cuts for MVP
- Admin role and any admin endpoints
- Document versioning
- Per-account login lockout
- Cloud deployment (provider choice not yet made)
- CodeQL / static analysis

## How to use this file
Treat this as context for your local Claude Code session alongside the
existing repo state (don't regenerate what's already built — see
PROJECT_STATUS.md for exactly what's done, in-progress/unverified, and
not started). Implement in small testable milestones, commit after each,
update PROJECT_STATUS.md with real `mvn test` results after each one, and
only come back for another architecture review if something genuinely
consequential comes up — not for every small implementation choice.
