# ADR-004: Separate Next.js Frontend and Spring Boot Backend Applications

## Status

Accepted (confirmed by the required stack in `CLAUDE.md`).

## Context

The required stack lists Next.js/React/TypeScript for the frontend and Java
21/Spring Boot for the backend as two separate technology stacks. An alternative
would be a single full-stack framework (e.g. server-rendered Java views, or a
Node.js backend to keep one language across the stack) to reduce the number of
runtimes and deployment units.

The project also enforces a development-workflow rule (`CLAUDE.md`, "Development
workflow") that backend and frontend for a module are implemented and tested in
separate steps, and a git-workflow rule that backend and frontend changes are not
bundled into the same commit unless full-stack work is explicitly authorized — both
of which presuppose a real separation between the two codebases, not just a logical
one.

## Decision

Run the frontend and backend as **two separately deployable applications**:

- Backend: Java 21, Spring Boot, Maven-built, exposing a versioned REST API
  (see `docs/api/api-conventions.md`) consumed by the frontend and, later, potentially
  by mobile clients.
- Frontend: Next.js/React/TypeScript, npm-managed, consuming the backend API through
  a single typed API client layer (per `.claude/rules/frontend.md`) — no server-side
  code sharing (no shared ORM entities, no shared runtime) between the two.
- The two applications communicate only over HTTP(S) through the documented API
  contract (`docs/api/`); there is no in-process or shared-database-bypass channel
  between frontend and backend code.
- Each is built, tested, and deployed independently (own Dockerfile/build pipeline),
  though both are orchestrated together via Docker Compose/Nginx for a given
  environment (see `docs/architecture/deployment-architecture.md`).

## Consequences

**Positive**

- Enables the "implement backend only, test, then implement frontend only, then test"
  workflow already mandated by `CLAUDE.md`'s development workflow — a real
  boundary at the process/deployment level reinforces the procedural boundary.
- Frontend and backend can use the best-fit ecosystem for each concern (React/Next.js
  server components and Tailwind/shadcn-ui for UI; Spring ecosystem for transactional
  business logic, JPA, Flyway, Spring Security) rather than compromising on a single
  full-stack framework.
- The API contract becomes an explicit, reviewable artifact (`docs/api/`) rather than
  an implicit shared-code coupling — this is what makes an API mismatch detectable
  at the client boundary, per `.claude/rules/frontend.md`.
- Independent scaling: the Next.js frontend and the stateless Spring Boot instances
  can be scaled separately behind Nginx based on their actual load profiles.

**Negative / trade-offs accepted**

- Two toolchains, two dependency ecosystems (Maven + npm), two sets of CI steps, and
  two languages (Java + TypeScript) to maintain, versus a single-stack alternative.
- Any API contract change must be coordinated across both codebases explicitly (see
  `docs/api/versioning.md`) — there is no compiler-enforced shared-type safety across
  the boundary; type mismatches surface at runtime unless caught by the API contract
  review step.

## Alternatives considered

- **Single full-stack framework (e.g. Node.js backend + Next.js, or server-rendered
  Java templates)** — rejected: contradicts the explicitly required stack in
  `CLAUDE.md` (Java 21/Spring Boot backend is mandatory), and would remove the clean
  backend/frontend workflow separation the project's development process depends on.

## Related

- `docs/architecture/solution-architecture.md`
- `docs/api/api-conventions.md`
- `.claude/rules/frontend.md`, `.claude/rules/git-workflow.md`
