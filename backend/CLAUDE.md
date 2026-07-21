# Backend Instructions

This directory is a separate Spring Boot application built with Maven.

Use:
- Java 21
- Maven Wrapper
- Spring Boot
- PostgreSQL
- Redis
- Flyway
- JUnit
- Testcontainers

Never use Gradle.

Run backend commands from this directory using:

`.\mvnw.cmd`

Keep controllers thin.

Business logic belongs in services.

Use request and response DTOs.

Do not expose JPA entities directly.

Every tenant-owned query must apply tenant isolation.

Database changes must be implemented as new Flyway migrations.

Never edit an already-shared migration.

Every security-sensitive change requires tests.

Do not modify the frontend directory during a backend-only task.
