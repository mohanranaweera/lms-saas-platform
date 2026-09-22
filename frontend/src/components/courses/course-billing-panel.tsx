"use client";

import { Info } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { CoursePriceChangeForm } from "@/components/courses/course-price-change-form";
import { CoursePricingModelControl } from "@/components/courses/course-pricing-model-control";
import { CourseBillingConfigurationForm } from "@/components/courses/course-billing-configuration-form";
import { CourseBillingPeriodPanel } from "@/components/courses/course-billing-period-panel";
import { useCourseBillingConfiguration } from "@/lib/api/course-billing";
import { isApiClientError } from "@/lib/api/error";
import { classifyQueryError } from "@/lib/api/query-status";
import { PermissionDeniedState } from "@/components/states/permission-denied-state";
import type { ApiClientError } from "@/lib/api/error";
import type { CourseResponse } from "@/lib/api/courses";

/**
 * Fees & Billing tab content — the only tab whose content fully depends on
 * `course.pricingModel` (Wave 2):
 *
 * - `FREE`: no fee configuration at all — a plain "this course is free"
 *   notice (never a form, never an implied checkout amount).
 * - `ONE_TIME`: the pre-Wave-2 flow, unchanged (`CoursePriceChangeForm`).
 * - `MONTHLY`/`SESSION`: billing configuration (session rate only for
 *   `SESSION`) + billing-period history/add-period.
 * - `CUSTOM`: same billing configuration/period UI, plus an explicit note
 *   that checkout for this course is a manual/staff process this wave — no
 *   self-serve custom-amount checkout endpoint ships yet, so this
 *   deliberately does NOT build or imply one.
 *
 * `GET .../billing-configuration` 404s ("not configured yet") when no
 * configuration row exists — that specific 404 is treated as "not
 * configured", never routed through the generic error/permission-denied
 * branches below (which are reserved for a REAL failure: network error, 500,
 * or an actual 401/403).
 */
export function CourseBillingPanel({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const needsBillingConfiguration =
    course.pricingModel === "MONTHLY" || course.pricingModel === "SESSION" || course.pricingModel === "CUSTOM";

  const configurationQuery = useCourseBillingConfiguration(courseId, {
    enabled: needsBillingConfiguration,
  });

  const configurationNotFound =
    configurationQuery.status === "error" &&
    isApiClientError(configurationQuery.error) &&
    configurationQuery.error.status === 404;

  return (
    <div className="flex flex-col gap-6">
      <CoursePricingModelControl courseId={courseId} course={course} />

      {course.pricingModel === "FREE" ? (
        <Alert role="status">
          <Info aria-hidden="true" />
          <AlertDescription>
            This course is free — no fee configuration needed. Checkout always resolves to a $0
            amount for students.
          </AlertDescription>
        </Alert>
      ) : null}

      {course.pricingModel === "ONE_TIME" ? <CoursePriceChangeForm courseId={courseId} course={course} /> : null}

      {needsBillingConfiguration ? (
        <>
          {course.pricingModel === "CUSTOM" ? (
            <Alert role="status">
              <Info aria-hidden="true" />
              <AlertDescription>
                Custom pricing has no self-serve checkout yet — a student cannot check out this
                course through the normal storefront flow this wave. Checkout is completed
                through a manual/staff process instead.
              </AlertDescription>
            </Alert>
          ) : null}

          {configurationQuery.status === "pending" ? (
            <LoadingState label="Loading billing configuration…" />
          ) : configurationQuery.status === "error" && !configurationNotFound ? (
            classifyQueryError(configurationQuery.error) === "forbidden" ? (
              <PermissionDeniedState error={configurationQuery.error as ApiClientError} />
            ) : (
              <ErrorState
                message={
                  isApiClientError(configurationQuery.error)
                    ? configurationQuery.error.message
                    : "Something went wrong loading the billing configuration."
                }
                onRetry={() => configurationQuery.refetch()}
              />
            )
          ) : (
            // `CourseBillingConfigurationForm` stays at this one stable JSX
            // position/shape (a `Fragment` with the form as its first child)
            // across the "not configured yet" <-> "configured" transition —
            // deliberately NOT two structurally different branches (a bare
            // element vs. a `Fragment`) here, since React would otherwise key
            // the same component type differently across those branches and
            // remount it, discarding its own `saved` confirmation state right
            // when a successful create/update flips `configurationNotFound`
            // from true to false (found the hard way: the "Billing
            // configuration saved." confirmation was disappearing instantly
            // after a successful create).
            <>
              <CourseBillingConfigurationForm
                courseId={courseId}
                pricingModel={course.pricingModel}
                configuration={configurationNotFound ? null : (configurationQuery.data ?? null)}
              />
              {!configurationNotFound ? <CourseBillingPeriodPanel courseId={courseId} /> : null}
            </>
          )}
        </>
      ) : null}
    </div>
  );
}
