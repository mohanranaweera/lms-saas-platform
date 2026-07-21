# Accessibility Checklist

Status: Draft
Related rules: `.claude/rules/ui-ux.md` §4, `frontend/CLAUDE.md` (accessible form labels baseline)

## Purpose

Concrete, checkable expansion of the accessibility bar in `.claude/rules/ui-ux.md`
§4. Every page/flow must be reviewed against this checklist before being marked
complete — not just the generic "accessible form labels" baseline.

## 1. Keyboard navigability

- [ ] Every interactive flow is fully operable via keyboard alone: multi-step
  forms (course creation, enrollment/checkout, payment slip upload), modals/
  dialogs, dropdown/menu components, data-table row actions, drag-and-drop
  material/lesson ordering.
- [ ] Modals/dialogs **trap focus** while open (Tab/Shift+Tab cannot escape the
  dialog to the page behind it).
- [ ] Closing a modal/dialog **returns focus** to the element that triggered it.
- [ ] Tab order follows visual/logical reading order on every screen — no
  focus jumping caused by absolute positioning or out-of-DOM-order rendering.
- [ ] **Drag-and-drop material/lesson ordering** (Teacher > Materials Manager,
  module 7) has a keyboard-operable equivalent — e.g. explicit "Move up" /
  "Move down" buttons or arrow-key reordering with an accessible instruction —
  not drag-only interaction.
- [ ] Data-table row actions (edit/delete/approve/reject/reset) are reachable
  and triggerable via keyboard (Tab to row action, Enter/Space to activate),
  not mouse-hover-only affordances.

## 2. Screen-reader-usable async status

- [ ] Loading states expose `aria-busy="true"` on the busy region and/or wrap
  it in an `aria-live="polite"` region, so a screen-reader user is told
  something is in progress.
- [ ] Error messages/toasts resulting from async operations use `role="alert"`
  or `aria-live="assertive"` so failures are announced, not just visually
  rendered.
- [ ] Applies at minimum to these app-specific flows:
  - Payment submission / checkout (`Student > Payments > Checkout`,
    `Payment Slip Upload`) — submitting state announced; success/failure
    announced.
  - Exam submission (`Student > Exams > Exam Taking`) — especially important
    given time-limited context; submission-in-progress and confirmation must
    be announced, not only shown via a spinner icon.
  - Save/import operations (course save, bulk student import, bulk messaging
    send) — announced start and completion/failure.
  - Payment slip approval/rejection (Tenant Admin) — reviewer action result
    announced.
- [ ] A shared toast/live-region wrapper (see `design-system.md` §4.4) is used
  consistently — do not hand-roll `aria-live` regions per page.

## 3. Tenant brand color contrast

- [ ] When a tenant configures a custom brand color (Branding Settings), the
  system runs a WCAG AA contrast check **at configuration time** — not only in
  the preview panel — against the text colors the brand color will pair with
  (e.g. white/black foreground text).
- [ ] On a failing contrast result, the system either auto-selects an
  accessible foreground color or flags the tenant's chosen color as failing,
  before/at the point of saving — silently accepting and rendering a
  low-contrast tenant UI is not acceptable.
- [ ] This check must be re-validated for both light-mode and dark-mode
  variants of the tenant color (per `design-system.md` §3) — a color passing
  in light mode does not guarantee it passes in dark mode.
- [ ] Any client-side contrast check is a UX safeguard/preview aid only; final
  acceptance/storage of the branding configuration is backend-validated (see
  `.claude/rules/frontend.md`).

## 4. Icon-only controls

- [ ] Every icon-only button (common in admin tables: edit, delete, reset
  device, approve/reject payment, expand row) has an `aria-label` describing
  its action — a `title` attribute alone is not sufficient (title is not
  reliably exposed to all screen readers/touch interfaces).
- [ ] `aria-label` text is specific to the row/entity where practical (e.g.
  `aria-label="Reset device for student Jane Doe"` rather than a generic
  `aria-label="Reset device"`), so screen-reader users navigating a table by
  controls can distinguish rows without relying on visual/table-position
  context.

## 5. Status indicators (color must not be the only signal)

- [ ] Payment status badges (e.g. Pending, Submitted, Under Review, Approved,
  Rejected, Refunded) pair color with text and/or an icon.
- [ ] Exam status badges (Draft, Scheduled, Published, Closed) pair color with
  text and/or an icon.
- [ ] Device status badges (Active, Reset Pending/Cooldown, Blocked) pair
  color with text and/or an icon.
- [ ] Tenant status badges (Trial, Active, Suspended, Cancelled, Pending
  Approval) pair color with text and/or an icon.
- [ ] The shared status-badge component (`design-system.md` §4.3) is the only
  implementation of this pattern — no module hand-rolls its own color-only
  badge.

## 6. Accessible form labels (baseline, expanded)

- [ ] Every form input has a programmatically associated `<label>` (via
  `htmlFor`/`id`, or an accessible name via `aria-label`/`aria-labelledby`
  where a visible label isn't used) — placeholder text alone is never a
  substitute for a label.
- [ ] Validation errors from React Hook Form + Zod are associated to their
  field via `aria-describedby`, and the field sets `aria-invalid="true"` when
  in an error state.
- [ ] Required fields are marked both visually and via `aria-required`/
  `required` (not color/asterisk alone).
- [ ] Grouped inputs (e.g. radio groups for enrollment options, checkbox
  groups for permission assignment) use `fieldset`/`legend` or an equivalent
  accessible grouping pattern so the group's purpose is announced.
- [ ] File upload inputs (payment slip upload, material upload, bulk student
  import) have a clear accessible label stating what is expected (accepted
  formats, size limit) rather than relying on a visually-adjacent hint alone.

## Open questions

- Concrete tooling choice for automated contrast checking (e.g. a specific
  JS color-contrast library) is not decided — flagged for a technical spike
  before implementing §3.
- Whether an automated accessibility CI check (e.g. axe-core integrated into
  Playwright runs) is adopted as a gate is not yet decided — recommend raising
  this alongside `.claude/rules/testing.md`'s Playwright conventions, but it is
  out of scope for this document to mandate a specific tool.
