# SecureVault API

A security-focused document management REST API. Users register, authenticate,
and upload/view/download/delete their own documents, with enforced ownership
and defense against broken object-level authorization (BOLA/IDOR).

**Status:** registration, JWT login, per-IP auth rate limiting, and audit
logging are implemented and covered by integration tests. Document
endpoints are the next milestone. See `PROJECT_STATUS.md` for details.

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
- **Schema owned by Flyway, not Hibernate.** `ddl-auto: validate` means
  Hibernate checks entities against the schema at startup but never
  generates or alters tables itself.

## Running tests

```bash
mvn test    # requires Docker running (Testcontainers starts PostgreSQL)
```

## Known limitations (current milestone)

- Document endpoints are not implemented yet.
- No CI pipeline yet.
- Rate limiting is in-memory (resets on restart, not shared across instances).

## License

Personal portfolio project.
