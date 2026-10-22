# SecureVault API — Project Status

Last updated: 2026-10-21 (handoff to local Claude Code session)

This file is the shared source of truth on project state for Alex,
Claude, and ChatGPT. Update it after every milestone with real results —
never with a claim that hasn't been verified by actually running the
build/tests.

## Verification status: NOTHING has been compiled or run yet

Everything below was written in a sandboxed environment with no access
to Maven Central, so **no `mvn compile` or `mvn test` has been run
against any of this code, ever.** Treat every file as "written against
current docs, believed correct, not yet verified" until a real local
`mvn verify` run says otherwise. The first thing to do locally is
confirm the existing tree even compiles before adding anything new.

## Committed (4 commits, verified only by `git log`, not by compiling)
1. `chore: initialize Spring Boot application`
2. `feat: configure PostgreSQL and Flyway`
3. `feat: add baseline security configuration (protected-by-default, stateless sessions)`
4. `docs: add initial README with setup instructions`

Covers: `pom.xml`, `SecurevaultApplication.java`, `docker-compose.yml`,
`application.yml`, `V1__init.sql` (app_users + documents tables),
baseline `SecurityConfig` (protected-by-default, CSRF disabled w/
justification, stateless sessions), `README.md`, `.env.example`,
`.gitignore`.

## Written but NOT yet committed (draft — review before trusting)
Part of the registration + JWT auth milestone, stopped mid-way to hand
off to local Claude Code:
- `user/entity/AppUser.java`
- `user/repository/AppUserRepository.java`
- `user/dto/UserResponse.java`
- `security/JwtService.java` — sign/verify JWTs, checks signature +
  expiration + issuer + clock skew
- `security/CustomUserDetailsService.java`
- `security/AuthenticatedUser.java` — custom principal, carries `id`
  directly for ownership checks later
- `security/JwtAuthenticationFilter.java`
- `exception/ApiError.java`
- `exception/DuplicateEmailException.java`

## Not started yet (rest of this milestone)
- `exception/GlobalExceptionHandler.java`
- `auth/dto/{RegisterRequest,LoginRequest,AuthResponse}.java`
- `auth/service/AuthService.java`
- `auth/controller/AuthController.java`
- `user/controller/UserController.java` (`GET /users/me`)
- Update `SecurityConfig` to wire in `JwtAuthenticationFilter` +
  `AuthenticationManager` bean
- Rate limiting (Bucket4j) on `/auth/login` and `/auth/register`
- Audit logging table + writes (with `REQUIRES_NEW` for failed/denied events)
- Tests: registration, login, JWT filter (valid/expired/tampered/missing)

## Architectural decisions
See `PROJECT_SPEC.md` for the full, agreed (Claude + ChatGPT + Alex)
spec — stack, auth model, file upload rules, storage layout, rate
limiting, audit logging, API docs, CI/DevSecOps. That file is the
source of truth for *decisions*; this file is the source of truth for
*current build state*.

## Next steps (in order)
1. `mvn -q compile` on the current tree — confirm what's here actually builds
2. Finish the registration + JWT auth milestone (see "Not started yet" above)
3. Run the test suite for real, record pass/fail here with actual output
4. Commit in small, meaningful chunks per the existing commit style
5. Move to the document endpoints milestone (upload/list/get/download/delete,
   ownership enforcement, `StorageService`) per `PROJECT_SPEC.md`
6. Security test suite pass
7. CI/CD (GitHub Actions + Dependabot + Dependency Review Action) and docs polish

## Known constraints
- No cloud deployment target chosen yet (explicitly deferred)
- No admin role in MVP
- No document versioning in MVP
- No per-account login lockout in MVP (IP-based rate limiting only)
