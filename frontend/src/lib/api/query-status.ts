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
 */
export type QueryStatusKind = "unauthenticated" | "forbidden" | "error";

export function classifyQueryError(error: unknown): QueryStatusKind {
  if (isApiClientError(error)) {
    if (error.status === 401) {
      return "unauthenticated";
    }
    if (error.status === 403) {
      return "forbidden";
    }
  }
  return "error";
}
