import { isApiClientError } from "./error";

/**
 * Narrows a React Query error into a UI-relevant kind, driven strictly by the
 * real HTTP status on a server-verified `ApiClientError` — never a guess.
 *
 * The critical invariant: an unrecognized failure (unknown status code, a
 * network failure, a non-`ApiClientError` throwable, a 5xx) must classify as
 * `"error"`, never `"forbidden"`. `"forbidden"` renders `PermissionDeniedState`,
 * which tells the user "you don't have permission" — misreporting a server
 * outage or unexpected failure as a permission problem would be actively
 * misleading and would hide a real bug behind reassuring-sounding copy. Only
 * a genuine, positively-identified 403 response may classify as `"forbidden"`.
 *
 * `"not_found"`: some backend endpoints (e.g. `GET /v1/staff/{id}`)
 * deliberately return an identical-shaped 403 or 404 for "this id doesn't
 * belong to your tenant" — by design, specifically so a client can't infer
 * which case occurred. A 404 always classifies as `"not_found"`. A 403 only
 * classifies as `"not_found"` when the caller opts in via
 * `options.treatForbiddenAsNotFound` (id-addressed single-resource lookups);
 * every other caller (e.g. list/create endpoints, where a 403 legitimately
 * means "no access to this feature at all") keeps getting `"forbidden"` as
 * before.
 */
export type QueryStatusKind = "unauthenticated" | "forbidden" | "not_found" | "error";

export interface ClassifyQueryErrorOptions {
  /** When true, a 403 classifies as `"not_found"` instead of `"forbidden"` —
   * for id-addressed lookups where the backend intentionally makes 403 and
   * 404 indistinguishable. Defaults to false. */
  treatForbiddenAsNotFound?: boolean;
}

export function classifyQueryError(
  error: unknown,
  options?: ClassifyQueryErrorOptions
): QueryStatusKind {
  if (isApiClientError(error)) {
    if (error.status === 401) {
      return "unauthenticated";
    }
    if (error.status === 404) {
      return "not_found";
    }
    if (error.status === 403) {
      return options?.treatForbiddenAsNotFound ? "not_found" : "forbidden";
    }
  }
  return "error";
}
