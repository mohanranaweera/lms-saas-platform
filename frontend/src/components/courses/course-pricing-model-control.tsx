"use client";

import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { CoursePricingModelFields } from "@/components/courses/course-form-fields";
import { CoursePricingModelBadge } from "@/components/courses/course-pricing-model-badge";
import { useChangeCoursePricingModel, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import {
  pricingModelChangeSchema,
  toPricingModelChangeRequest,
  type PricingModelChangeFormValues,
} from "@/lib/validation/course";

/**
 * `PATCH /api/v1/courses/{id}/pricing-model` (Wave 2) — the sole write path
 * for a course's billing model. Staff (`CREATE_EDIT`) or the owning Teacher;
 * rendered unconditionally on the Fees & Billing tab (both roles reach this
 * page) — a caller lacking permission gets a real 403 from the backend,
 * surfaced inline via this form's own `ErrorState`, same convention as every
 * other dedicated-action form in this module.
 */
export function CoursePricingModelControl({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const [saved, setSaved] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    handleSubmit,
    setValue,
    control,
    formState: { errors },
  } = useForm<PricingModelChangeFormValues>({
    resolver: zodResolver(pricingModelChangeSchema),
    defaultValues: { pricingModel: course.pricingModel },
  });

  const mutation = useChangeCoursePricingModel(courseId);
  const pricingModel = useWatch({ control, name: "pricingModel" });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await mutation.mutateAsync(toPricingModelChangeRequest(values));
      setSaved(true);
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "An unexpected error occurred. Please try again.");
    }
  });

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h3 className="text-sm font-medium text-foreground">Pricing model</h3>
          <p className="text-xs text-muted-foreground">Current model:</p>
        </div>
        <CoursePricingModelBadge pricingModel={course.pricingModel} />
      </div>

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Pricing model updated.</AlertDescription>
        </Alert>
      ) : null}
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <form
        className="flex flex-col gap-3"
        noValidate
        aria-busy={mutation.isPending}
        onSubmit={onSubmit}
      >
        <span role="status" aria-live="polite" className="sr-only">
          {mutation.isPending ? "Saving pricing model…" : ""}
        </span>
        <CoursePricingModelFields
          errors={errors}
          idPrefix={`pricing-model-${courseId}`}
          value={pricingModel}
          onChange={(value) => setValue("pricingModel", (value ?? "") as PricingModelChangeFormValues["pricingModel"], { shouldValidate: true })}
          disabled={mutation.isPending}
        />
        <Button
          type="submit"
          className="w-fit"
          disabled={mutation.isPending || pricingModel === course.pricingModel}
          aria-busy={mutation.isPending}
        >
          {mutation.isPending ? "Saving…" : "Save pricing model"}
        </Button>
      </form>
    </div>
  );
}
