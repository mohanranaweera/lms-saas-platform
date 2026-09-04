/**
 * Minimal shared `aria-live="polite"` announcer for async operation status
 * (submitting/pending/success copy), following the pattern first hand-rolled
 * in `components/courses/course-price-change-form.tsx`. No provider, queue,
 * or stacking system — this project's toast library is intentionally absent
 * (see MVP-010 plan notes); render an empty string to announce nothing.
 *
 * Failure/error announcements still go through `role="alert"` elements
 * (`components/states/error-state.tsx`, `components/ui/alert.tsx`'s default
 * `role="alert"`) — this component is for non-error, polite status updates
 * only, UNLESS `assertive` is passed (see below).
 *
 * `assertive` switches this to `role="alert"`/`aria-live="assertive"` for the
 * rare case where a single interrupting region is deliberately consolidating
 * what would otherwise be several competing `role="alert"` announcements at
 * once (see `components/attendance/mark-attendance-panel.tsx`'s batch
 * mark-failure summary) — prefer the default polite mode unless you have
 * this specific "one region replacing N" need; do not default to assertive
 * for ordinary error copy, which should keep using the dedicated `Alert`/
 * `ErrorState` components instead.
 */
export function LiveRegion({
  message,
  assertive = false,
}: {
  message: string;
  assertive?: boolean;
}) {
  return (
    <span
      role={assertive ? "alert" : "status"}
      aria-live={assertive ? "assertive" : "polite"}
      className="sr-only"
    >
      {message}
    </span>
  );
}
