# SecureVault API — Project Status

Last updated: 2026-10-22 (registration + JWT auth milestone complete)

This file is the shared source of truth on project state for Alex,
Claude, and ChatGPT. Update it after every milestone with real results —
never with a claim that hasn't been verified by actually running the
build/tests.

## Verification status: compiled and tested locally

Environment: Temurin JDK 21.0.12.1, Maven 3.10.0, Docker Desktop
(Testcontainers, `postgres:16-alpine` → PostgreSQL 16.15), Windows 11.

Latest `mvn test` on commit `4c5ee53`:

```
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuditLogIntegrationTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- AuthIntegrationTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- AuthRateLimitTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- JwtAuthenticationFilterTest
Tests run: 26, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

All tests are integration tests: full Spring context, real PostgreSQL via
Testcontainers, Flyway applying V1 + V2 with `ddl-auto: validate`.

**Not yet verified:** running the app outside tests (`mvn spring-boot:run`
against the Docker Compose Postgres). The test suite boots the same
context against real Postgres, but nobody has started it by hand yet.

## Git history

The planning-session history was not preserved. This repo was initialized
fresh locally with one baseline commit containing the scaffold, followed
by this milestone's commits:

1. `1aa60e7` chore: import SecureVault MVP scaffold from planning session
2. `370bea5` fix(build): use Boot-managed Testcontainers 2.x and add Flyway Postgres module
3. `47409e8` fix(security): harden draft JwtService and JwtAuthenticationFilter
4. `a855b5d` feat(auth): add registration, login, and GET /api/users/me
5. `f8d248a` feat(security): add per-IP rate limiting on auth endpoints
6. `4c5ee53` feat(audit): record register and login events in audit_log

## Problems found in the scaffold (and fixed)

`mvn -q compile` on the untouched scaffold **succeeded** (exit 0, no
output), and all pinned versions resolved (Spring Boot 4.1.1 → Spring
7.0.9 / Security 7.1.1 / Hibernate 7.4.5 / Flyway 12.4.0; JJWT 0.13.0).
These problems would only have shown up at runtime or in tests:

- **Testcontainers pinned to 1.21.1** via the `testcontainers.version`
  property — the same property Boot 4.1.1 uses to manage **2.0.5**, so it
  silently downgraded. Now Boot-managed 2.x with the renamed artifacts
  (`testcontainers-postgresql`, `testcontainers-junit-jupiter`).
- **Missing `flyway-database-postgresql`** — Flyway 10+ needs it for
  PostgreSQL; startup would fail with "Unsupported Database".
- **JwtService:** algorithm wasn't pinned (`signWith(key)` picks HS384 for
  a 48-byte key); a token with no `sub` caused an NPE → 500; bad-base64
  secret handling caught the wrong exception type.
- **JwtAuthenticationFilter** was a `@Component`, so Boot would also
  register it as a plain servlet filter outside the security chain.
- **Default `JWT_SECRET`** in `application.yml` wasn't valid base64, so
  the app could never start with it. Removed; startup now fails fast if
  `JWT_SECRET` is unset.
- Unauthenticated requests returned a bare **403**; now 401 + `ApiError`.

## Done in this milestone

- `POST /api/auth/register` → 201 `UserResponse`; email trimmed +
  lowercased; 409 on duplicate (including the unique-constraint race);
  password 12–72 chars and ≤ 72 UTF-8 bytes (BCrypt limit)
- `POST /api/auth/login` → 200 `{accessToken, tokenType, expiresIn}`;
  unknown email and wrong password both give the same 401
- `GET /api/users/me` (authenticated)
- JWT: HS256 only, validates signature, algorithm, expiration (30s
  skew), issuer, and subject; user must still exist
- `GlobalExceptionHandler` + `RestAuthenticationEntryPoint` — all errors
  use `ApiError`, no internals leaked
- Rate limiting: Bucket4j `com.bucket4j:bucket4j_jdk17-core:8.21.0`
  (verified on Maven Central; the old `bucket4j-core` artifact stopped at
  8.10.1) + Caffeine (bounded size, expire-after-access). Per
  `getRemoteAddr()`, login + register share one bucket per IP, default
  10/min (`AUTH_RATE_LIMIT_PER_MINUTE`), 429 + `Retry-After`
- Audit log: `V2__audit_log.sql`, `AuditService.record` with
  `REQUIRES_NEW`; logs REGISTER/LOGIN success and failure. Mutation-
  checked: switching to `REQUIRED` makes the rollback-survival test fail

## Decisions made during implementation

- Endpoints live under `/api/...` (matches the existing `SecurityConfig`)
- Login and register share one rate-limit bucket per IP
- Failed logins record the targeted user id (if the email exists) as
  `resource_id`, with null `actor_id`; the attempted email is never stored
- `audit_log` has no foreign keys so records outlive users/documents
- Success audit records also use `REQUIRES_NEW`; in the unlikely case the
  outer transaction fails to commit *after* its flush, a success row
  could exist for a rolled-back registration. Accepted for MVP.

## Next steps (in order)

1. Smoke-test the app by hand against Docker Compose Postgres
2. Document endpoints milestone (upload/list/get/download/delete,
   ownership enforcement, `StorageService`) per `PROJECT_SPEC.md`,
   including document audit events
3. springdoc-openapi (confirm the version on Maven Central and load Swagger UI first)
4. Security test suite pass
5. CI/CD (GitHub Actions + Dependabot + Dependency Review Action) and docs polish

## Known constraints

- No cloud deployment target chosen yet (explicitly deferred)
- No admin role in MVP; no audit read endpoint
- No document versioning in MVP
- No per-account login lockout in MVP (IP-based rate limiting only)
- Rate-limit state is in-memory: resets on restart, not shared across instances
