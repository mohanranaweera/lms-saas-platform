# Frontend Rules

These rules are specific to structure, data flow, and forms in the Next.js app. They apply
whenever touching `frontend/`, in addition to — and without repeating — the stack list and
baseline state/accessibility requirements already in `frontend/CLAUDE.md`.

## Route and folder structure

- Group App Router routes by role/audience so the boundary is visible in the folder structure
  itself, not just in runtime checks: e.g. `app/(student)/`, `app/(teacher)/`,
  `app/(tenant-admin)/`, `app/(platform-admin)/`, `app/(public)/` for the tenant storefront and
  marketing/login pages.
- Shared, generic UI (shadcn/ui-based primitives, layout shells) lives in `components/ui/` and
  similar shared directories. Feature-specific components are co-located under the route group
  that owns them, not dumped into one flat `components/` folder.
- A component or page that needs data/behavior from more than one role group is a signal to
  extract a shared component into a common directory — not to import across role-group
  boundaries directly.

## Data fetching

- All server state (anything from the backend API) goes through React Query — no ad hoc
  `fetch`/`useEffect` data-fetching in components.
- Prefer fetching initial data in server components where reasonable; use React Query on the
  client for interactive refetching, mutations, and optimistic updates.
- Use a single typed API client layer (generated or hand-written types matching the backend
  contract) rather than scattering raw `fetch` calls with inline response shapes across
  components — this is what makes an API mismatch detectable at the client boundary instead of
  as a runtime surprise deep in a component.
- If the API client's expected shape doesn't match what the backend actually returns, that is
  the "API mismatch" this project requires reporting before touching `backend/` — do not paper
  over it with an `as` cast or optional-chaining workaround that hides the mismatch.

## Forms

- Every form uses React Hook Form + a Zod schema. The schema is the single source of truth for
  client-side validation rules for that form.
- Client-side (Zod) validation is a UX convenience only — it is never a substitute for backend
  validation, and a form must handle a backend validation error (422/400) even when the Zod
  schema passed, since the two are allowed to drift or the backend may enforce additional
  server-only rules (e.g. tenant-scoped uniqueness).

## Contribution to frontend.md — UX-driven technical rules

- Loading, empty, error, and permission-denied states must be built as shared, reusable
  components/patterns (e.g. a small state-component library or a React Query status-mapping
  helper) rather than reimplemented ad hoc per page — this is what keeps empty-state content
  quality and accessibility attributes (`aria-live`, `aria-busy`, `role="alert"`) consistent
  across modules instead of drifting per page.
- The permission-denied state must be driven only by a server-verified signal — a 401/403
  response, or a permission/role value returned by the backend in the authenticated
  session/profile payload — never computed purely from a client-stored role string or by
  guessing from the route. Client-side route guards exist for UX convenience (avoid a flash of
  wrong content) only; they are never the source of truth for whether an action is allowed.
- Tenant theme/branding values (logo, colors, favicon) must be fetched from a tenant-scoped
  config API/endpoint at request or session time, cached with tenant-id-scoped keys, and never
  hardcoded per environment/build or shipped as static assets shared across tenants.
- Async operation status (loading/success/error) should be surfaced through one consistent
  pattern (e.g. React Query status mapped to a shared toast/live-region wrapper) so accessible
  announcements and error copy don't get reinvented — and inconsistently implemented — per
  module.
- Data tables used across Tenant Admin and Platform Admin screens should share a common
  responsive table component (supporting card-view fallback and/or column visibility toggling)
  rather than each module hand-rolling its own table markup and mobile behavior.
- If client-side contrast-checking of tenant-supplied brand colors is implemented, treat it as a
  UX safeguard/preview aid only — final acceptance and storage of a tenant's branding
  configuration remains a backend-validated setting, not something the client can silently
  approve or reject on its own.
