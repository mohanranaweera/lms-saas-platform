import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { courseKeys, type PageResponse } from "@/lib/api/courses";

/**
 * Typed client + React Query hooks for Wave 2's course billing-configuration/
 * billing-period endpoints (`/api/v1/courses/{courseId}/billing-configuration`,
 * `/api/v1/courses/{courseId}/billing-periods` — see `CourseBillingController`).
 * Only meaningful for a course whose `pricingModel` is `MONTHLY`, `SESSION`, or
 * `CUSTOM` — `ONE_TIME` keeps using `useChangeCoursePrice`
 * (`course-price-change-form.tsx`) unchanged, and `FREE` needs no fee
 * configuration at all.
 *
 * Every call goes through `useAuth().authorizedFetch`, matching every other
 * protected endpoint in this app.
 */

/** Mirrors `CourseBillingConfigurationResponse`. */
export interface CourseBillingConfigurationResponse {
  id: string;
  courseId: string;
  sessionRate: number | null;
  currency: string;
  requiresManualQuote: boolean;
  createdAt: string;
  updatedAt: string;
}

/**
 * Mirrors `CourseBillingConfigurationRequest`. `sessionRate` is only
 * meaningful (backend-validated, rejected otherwise) when the course's
 * current `pricingModel` is `SESSION`; `requiresManualQuote: true` is only
 * meaningful when it is `CUSTOM` — see `BillingConfigurationService
 * #validateAgainstPricingModel`'s javadoc. This client never silently coerces
 * either; the caller is responsible for only sending what applies.
 */
export interface CourseBillingConfigurationRequest {
  sessionRate?: number;
  currency: string;
  requiresManualQuote: boolean;
}

/** Mirrors `CourseBillingPeriodResponse`. `effectiveTo` is `null` for the current/open period. */
export interface CourseBillingPeriodResponse {
  id: string;
  billingConfigurationId: string;
  amount: number;
  currency: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  createdAt: string;
}

/**
 * Mirrors `CourseBillingPeriodRequest`. `effectiveFrom`, if omitted, resolves
 * server-side to "now" — never client-trusted for anything but a genuinely
 * future-dated period.
 */
export interface CourseBillingPeriodRequest {
  amount: number;
  currency: string;
  effectiveFrom?: string;
}

/**
 * `GET /api/v1/courses/{courseId}/billing-configuration`. 404s
 * (`NOT_FOUND`) when no configuration has been created yet for this course —
 * callers must treat that specific case as "not configured yet", not a real
 * error (see `course-billing-panel.tsx`'s `classifyBillingConfigurationQuery`
 * helper).
 */
export function useCourseBillingConfiguration(courseId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: courseKeys.billingConfiguration(courseId),
    queryFn: () =>
      authorizedFetch<CourseBillingConfigurationResponse>(
        "tenant",
        `/v1/courses/${courseId}/billing-configuration`
      ),
    enabled: (options?.enabled ?? true) && courseId.length > 0,
    retry: false,
  });
}

/**
 * `POST /api/v1/courses/{courseId}/billing-configuration` — creates the
 * course's one-and-only billing configuration row, or updates it in place if
 * one already exists (idempotent upsert, per `BillingConfigurationService
 * #createOrUpdateConfiguration`'s javadoc).
 */
export function useCreateOrUpdateBillingConfiguration(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseBillingConfigurationRequest) =>
      authorizedFetch<CourseBillingConfigurationResponse>(
        "tenant",
        `/v1/courses/${courseId}/billing-configuration`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: (updated) => {
      queryClient.setQueryData(courseKeys.billingConfiguration(courseId), updated);
    },
  });
}

/**
 * `GET /api/v1/courses/{courseId}/billing-periods` — paginated, newest
 * `effectiveFrom` first (server default sort). 404s the same way the
 * configuration read does when no configuration exists yet.
 */
export function useCourseBillingPeriods(
  courseId: string,
  page: number,
  options?: { enabled?: boolean; size?: number }
) {
  const { authorizedFetch } = useAuth();
  const size = options?.size ?? 10;
  return useQuery({
    queryKey: courseKeys.billingPeriods(courseId, page),
    queryFn: () =>
      authorizedFetch<PageResponse<CourseBillingPeriodResponse>>(
        "tenant",
        `/v1/courses/${courseId}/billing-periods?page=${page}&size=${size}`
      ),
    enabled: (options?.enabled ?? true) && courseId.length > 0,
    retry: false,
    placeholderData: keepPreviousData,
  });
}

/**
 * `POST /api/v1/courses/{courseId}/billing-periods` — closes the current
 * open period (if any) and inserts the new one, in one backend transaction.
 * NEVER retroactive: the closed period's own `amount`/`effectiveFrom` are
 * never touched (append-only history) — see this hook's callers for the
 * required "this does not change past periods/payments" UI copy.
 */
export function useAddCourseBillingPeriod(courseId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CourseBillingPeriodRequest) =>
      authorizedFetch<CourseBillingPeriodResponse>(
        "tenant",
        `/v1/courses/${courseId}/billing-periods`,
        { method: "POST", body: JSON.stringify(body) }
      ),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: courseKeys.detail(courseId) });
      queryClient.invalidateQueries({ queryKey: [...courseKeys.detail(courseId), "billing-periods"] });
    },
  });
}
