# Git Workflow Rules

These rules make root `CLAUDE.md`'s Safety and Development-workflow rules concrete
(never push directly to `main`; never merge without human approval; commit one logical
change).

## Branching

- All work happens on a branch off `main`; never commit directly to `main`.
- Name branches `<type>/<short-description>`, where `<type>` is one of `feat`, `fix`,
  `refactor`, `docs`, `test`, `chore` — matching the module/domain where practical
  (e.g. `feat/payment-slip-duplicate-check`, `fix/tenant-isolation-course-list`).

## Commits

- Commit messages use the imperative mood ("add", "fix", "refactor" — not "added" or
  "adds") and state the *why* when it isn't obvious from the diff.
- One logical change per commit: a commit should correspond to one step of the
  development workflow that's actually complete (e.g. "backend: add payment slip
  duplicate check" is one commit; don't bundle an unrelated frontend change or an
  unrelated module's migration into it).
- Do not bundle backend and frontend changes into the same commit unless the task
  explicitly said "full-stack implementation approved" — mirror the same boundary the
  implementation skills enforce.
- Do not skip commit hooks (`--no-verify`) or amend/force-push a commit that has
  already been pushed and reviewed.

## Pull requests

- Every change lands via a PR into `main`; no direct pushes, ever.
- A PR is ready for review only once `definition-of-done` passes: tests green, tenant
  isolation and security review complete where applicable, and documentation updated
  (or explicitly stated as not needed).
- The PR description states which change-controlled areas (if any) were touched and
  links the approving ADR under `docs/adr` — a PR touching a change-controlled area
  with no linked approval should not be merged.
- At least one human approval is required before merge; the agent never merges its own
  PR, and never approves production deployment as part of a merge.
