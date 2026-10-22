# SecureVault API

A security-focused document management REST API. Users register, authenticate,
and upload/view/download/delete their own documents, with enforced ownership
and defense against broken object-level authorization (BOLA/IDOR).

**Status:** early skeleton — health endpoint and baseline security are in
place; registration, authentication, and document endpoints are the next
milestones. See the task list / commit history for current progress.

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

## Known limitations (current milestone)

- Registration, login, and document endpoints are not implemented yet.
- No automated tests yet.
- No CI pipeline yet.
- This project has been written against Spring Boot 4.1.1's current
  documentation but has not yet been compiled/run in the environment that
  wrote it (no Maven Central access there) - treat it as unverified until
  you run the steps above and confirm it builds and starts.

## License

Personal portfolio project.
