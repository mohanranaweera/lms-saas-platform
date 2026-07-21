# ADR-005: Maven as the Sole Backend Build Tool

## Status

Accepted (confirmed by the required stack in `CLAUDE.md`: "Maven only").

## Context

The Java/Spring Boot backend needs a build tool for dependency management,
compilation, packaging, and running tests (JUnit, Testcontainers). The two dominant
options in the Java ecosystem are Maven (declarative XML, `pom.xml`) and Gradle
(Groovy/Kotlin DSL, more flexible/programmatic build scripts). `CLAUDE.md` explicitly
mandates Maven and excludes Gradle ("Maven only").

## Decision

Use **Maven** as the only build tool for the Spring Boot backend across all 18
domain packages:

- A single root `pom.xml` for the modular monolith (module-per-domain is expressed as
  Java package structure within one build, per `.claude/rules/architecture.md`, not
  as separate Maven modules/artifacts, since the domains are not independently
  deployed — see ADR-001).
- All dependency versions (Spring Boot, Spring Security, JPA/Hibernate, Flyway,
  Testcontainers, JUnit) are managed through Maven's dependency management
  (e.g. the Spring Boot BOM via `spring-boot-starter-parent` or explicit
  `<dependencyManagement>`), not hand-pinned per module.
- No Gradle build files (`build.gradle`, `settings.gradle`) are introduced anywhere
  in `backend/`.

## Consequences

**Positive**

- One, consistent, declarative build definition — easier for CI and for engineers
  moving between domain packages to reason about than a programmatic build script.
- Maven's large, stable plugin ecosystem (Surefire/Failsafe for unit vs. integration
  test separation, the Spring Boot Maven plugin for packaging) directly supports the
  unit-test-vs-Testcontainers-integration-test split required by
  `.claude/rules/testing.md`.
- Avoids mixing two JVM build tools in one repository, which would otherwise create
  onboarding confusion and duplicate dependency-version sources of truth.

**Negative / trade-offs accepted**

- Maven's XML configuration is more verbose than Gradle's DSL for advanced/custom
  build logic — accepted as a reasonable trade-off given this project has no
  currently known requirement for complex custom build scripting.
- Because domains are not split into separate Maven modules, there is no
  build-enforced compilation boundary between domain packages — the `api`-only
  cross-module dependency rule (ADR-001) is enforced by code review, not by Maven
  module isolation. Introducing per-domain Maven modules (with `api`-module-only
  inter-dependencies enforced by the build) is a possible future refinement, but
  would itself need an ADR since it changes the build/module structure this ADR
  fixes.

## Alternatives considered

- **Gradle** — rejected: excluded explicitly by `CLAUDE.md`'s required stack
  ("Maven only"); not a decision this session can revisit without an explicit change
  to the root project instructions.
- **Per-domain Maven modules (multi-module Maven build)** — not adopted now; noted
  above as a possible future refinement rather than a rejected alternative, since it
  doesn't conflict with "Maven only," it would just add build-level enforcement of
  the already-required domain boundaries. Deferred until the single-module structure
  proves insufficient.

## Related

- `docs/architecture/modular-monolith.md`
- `.claude/rules/testing.md`
