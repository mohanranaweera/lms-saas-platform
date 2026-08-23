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
 * only.
 */
export function LiveRegion({ message }: { message: string }) {
  return (
    <span role="status" aria-live="polite" className="sr-only">
      {message}
    </span>
  );
}
