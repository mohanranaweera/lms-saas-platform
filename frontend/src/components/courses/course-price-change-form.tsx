"use client";

import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { ErrorState } from "@/components/states/error-state";
import { useChangeCoursePrice, type CourseResponse } from "@/lib/api/courses";
import { isApiClientError } from "@/lib/api/error";
import { priceChangeSchema, PRICE_HELPER_TEXT, type PriceChangeFormValues } from "@/lib/validation/course";

/**
 * The only way to change a course's price after creation (`PATCH
 * /api/v1/courses/{id}/price` — not part of the general edit form, see
 * `CourseUpdateRequest`'s deliberate exclusion). Available to staff
 * (`CREATE_EDIT`) or the owning Teacher; rendered on both the Teacher edit
 * page and the Tenant Admin course detail page.
 */
export function CoursePriceChangeForm({ courseId, course }: { courseId: string; course: CourseResponse }) {
  const [saved, setSaved] = useState(false);
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<PriceChangeFormValues>({
    resolver: zodResolver(priceChangeSchema),
    defaultValues: { price: String(course.price) },
  });

  const mutation = useChangeCoursePrice(courseId);

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    try {
      await mutation.mutateAsync({ price: Number(values.price) });
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error)) {
        const priceError = error.fieldErrors.find((fieldError) => fieldError.field === "price");
        if (priceError) {
          setError("price", { type: "server", message: priceError.message });
          return;
        }
        setPageError(error.message);
        return;
      }
      setPageError("An unexpected error occurred. Please try again.");
    }
  });

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h3 className="text-sm font-medium text-foreground">Change price</h3>
        <p className="text-xs text-muted-foreground">
          Current price: {course.price.toFixed(2)}. This is the only way to change a
          course&apos;s price after creation.
        </p>
      </div>

      {saved ? (
        <Alert role="status">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Price updated.</AlertDescription>
        </Alert>
      ) : null}
      {pageError ? <ErrorState message={pageError} onRetry={() => setPageError(null)} /> : null}

      <form
        className="flex flex-col gap-2 sm:flex-row sm:items-end"
        noValidate
        aria-busy={mutation.isPending}
        onSubmit={onSubmit}
      >
        <span role="status" aria-live="polite" className="sr-only">
          {mutation.isPending ? "Saving price…" : ""}
        </span>
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor={`price-change-${courseId}`}>New price</Label>
          <Input
            id={`price-change-${courseId}`}
            inputMode="decimal"
            autoComplete="off"
            disabled={mutation.isPending}
            aria-invalid={!!errors.price}
            aria-describedby={
              [`price-change-${courseId}-helper`, errors.price ? `price-change-${courseId}-error` : undefined]
                .filter(Boolean)
                .join(" ") || undefined
            }
            {...register("price")}
          />
          <p id={`price-change-${courseId}-helper`} className="text-xs text-muted-foreground">
            {PRICE_HELPER_TEXT}
          </p>
          {errors.price ? (
            <p id={`price-change-${courseId}-error`} role="alert" className="text-xs text-destructive">
              {errors.price.message}
            </p>
          ) : null}
        </div>
        <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save price"}
        </Button>
      </form>
    </div>
  );
}
