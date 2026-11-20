"use client";

import { useEffect, useState } from "react";
import { Controller, useFieldArray, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { BookOpen, CheckCircle2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { EmptyState } from "@/components/states/empty-state";
import { isApiClientError } from "@/lib/api/error";
import { cn } from "@/lib/utils";
import { useSaveStorefrontProducts, type StorefrontAdmin } from "@/lib/api/teacher-storefronts";
import { storefrontProductsSchema, type StorefrontProductsFormValues } from "@/lib/validation/teacher-storefront";

/**
 * Rows = every course the teacher is assigned to (`assignableCourses`) plus
 * anything already listed; listed ones first, by sort order. Listing is an
 * explicit choice per course (ADR-037 decision 5) - nothing is listed by
 * default, and the backend rejects a course the teacher isn't assigned to.
 */
export function toProductRows(storefront: StorefrontAdmin): StorefrontProductsFormValues["items"] {
  const listed = [...storefront.products]
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((product) => ({
      courseId: product.courseId,
      title: product.courseTitle ?? "Untitled course",
      listed: true,
      published: product.published,
      sortOrder: product.sortOrder,
    }));
  const listedIds = new Set(listed.map((row) => row.courseId));
  const nextOrder = listed.reduce((max, row) => Math.max(max, row.sortOrder + 1), 0);
  const unlisted = (storefront.assignableCourses ?? [])
    .filter((course) => !listedIds.has(course.courseId))
    .sort((a, b) => a.title.localeCompare(b.title))
    .map((course, index) => ({
      courseId: course.courseId,
      title: course.title,
      listed: false,
      published: false,
      sortOrder: nextOrder + index,
    }));
  return [...listed, ...unlisted];
}

export function StorefrontProductsForm({ teacherId, storefront }: { teacherId: string; storefront: StorefrontAdmin }) {
  const save = useSaveStorefrontProducts(teacherId);
  const [saved, setSaved] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const form = useForm<StorefrontProductsFormValues>({
    resolver: zodResolver(storefrontProductsSchema),
    defaultValues: { items: toProductRows(storefront) },
  });
  const { control, reset, register } = form;
  const { fields } = useFieldArray({ control, name: "items", keyName: "fieldId" });
  const items = useWatch({ control, name: "items" }) ?? [];
  const created = Boolean(storefront.id);

  useEffect(() => {
    reset({ items: toProductRows(storefront) });
  }, [reset, storefront]);

  const onSubmit = form.handleSubmit(async (values) => {
    setSaved(false);
    setServerError(null);
    try {
      await save.mutateAsync({
        items: values.items
          .filter((item) => item.listed)
          .map((item) => ({ courseId: item.courseId, published: item.published, sortOrder: item.sortOrder })),
      });
      setSaved(true);
    } catch (error) {
      if (isApiClientError(error) && error.code === "STOREFRONT_COURSE_NOT_ASSIGNED") {
        setServerError("One of these courses isn't assigned to this teacher any more. Reload the page and try again.");
      } else if (isApiClientError(error) && error.status === 403) {
        setServerError("You don't have permission to manage teacher storefronts.");
      } else {
        setServerError(isApiClientError(error) && error.message ? error.message : "We couldn't save the course list. Try again.");
      }
    }
  });

  const publishedCount = items.filter((item) => item?.listed && item.published).length;

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex flex-col gap-4 rounded-xl border border-border bg-card p-5 shadow-sm"
      aria-labelledby="storefront-products-heading"
      data-testid="storefront-products-form"
    >
      <div>
        <h2 id="storefront-products-heading" className="text-base font-semibold text-foreground">
          Courses on this storefront
        </h2>
        <p className="text-sm text-muted-foreground">
          Choose which of this teacher&apos;s courses the storefront sells. Only published courses that are also public
          on your institute site are shown to visitors.
        </p>
      </div>

      {!created ? (
        <p className="rounded-lg border border-border bg-muted/50 px-4 py-3 text-sm text-muted-foreground" data-testid="storefront-products-locked">
          Save the storefront address first, then choose its courses.
        </p>
      ) : fields.length === 0 ? (
        <EmptyState
          icon={<BookOpen className="size-6" aria-hidden="true" />}
          title="No assigned courses"
          description="This teacher isn't assigned to any course yet. Assign them to a course from the course's Teachers settings, then list it here."
        />
      ) : (
        <>
          <p className="text-sm text-muted-foreground" aria-live="polite">
            {publishedCount} {publishedCount === 1 ? "course" : "courses"} published
          </p>
          <ul className="flex flex-col divide-y divide-border rounded-lg border border-border">
            {fields.map((field, index) => {
              const row = items[index];
              const listed = row?.listed ?? false;
              const sortError = form.formState.errors.items?.[index]?.sortOrder?.message;
              const baseId = `storefront-product-${index}`;
              return (
                <li key={field.fieldId} className="flex flex-col gap-3 p-4 md:flex-row md:items-center md:gap-6" data-testid="storefront-product-row">
                  <div className="flex min-w-0 flex-1 items-start gap-3">
                    <input
                      id={`${baseId}-listed`}
                      type="checkbox"
                      className="mt-0.5 size-4 shrink-0 accent-primary focus-visible:ring-3 focus-visible:ring-ring/50"
                      {...register(`items.${index}.listed`)}
                    />
                    <Label htmlFor={`${baseId}-listed`} className="min-w-0 break-words font-medium">
                      {field.title}
                      <span className="sr-only"> - list on this storefront</span>
                    </Label>
                  </div>
                  <div className="flex flex-wrap items-center gap-4 pl-7 md:pl-0">
                    <div className="flex items-center gap-2">
                      <Controller
                        control={control}
                        name={`items.${index}.published`}
                        render={({ field: published }) => (
                          <Switch
                            id={`${baseId}-published`}
                            checked={listed && published.value}
                            onCheckedChange={published.onChange}
                            onBlur={published.onBlur}
                            disabled={!listed || save.isPending}
                            aria-label={`Published: ${field.title}`}
                          />
                        )}
                      />
                      <span className="text-sm text-muted-foreground" aria-hidden="true">
                        Published
                      </span>
                    </div>
                    <div className="flex items-center gap-2">
                      <Label htmlFor={`${baseId}-order`} className="text-sm font-normal text-muted-foreground">
                        Order
                      </Label>
                      <Input
                        id={`${baseId}-order`}
                        type="number"
                        inputMode="numeric"
                        min={0}
                        max={9999}
                        step={1}
                        className={cn("h-8 w-20", !listed && "opacity-50")}
                        // readOnly, not disabled: RHF drops values of disabled inputs.
                        readOnly={!listed || save.isPending}
                        aria-disabled={!listed || undefined}
                        aria-label={`Sort order: ${field.title}`}
                        aria-invalid={sortError ? true : undefined}
                        aria-describedby={sortError ? `${baseId}-order-error` : undefined}
                        {...register(`items.${index}.sortOrder`, { valueAsNumber: true })}
                      />
                    </div>
                  </div>
                  {sortError ? (
                    <p id={`${baseId}-order-error`} className="text-sm text-destructive md:basis-full">
                      {sortError}
                    </p>
                  ) : null}
                </li>
              );
            })}
          </ul>
        </>
      )}

      {saved ? (
        <Alert role="status" data-testid="storefront-products-saved">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Course list saved.</AlertDescription>
        </Alert>
      ) : null}
      {serverError ? (
        <p role="alert" className="text-sm text-destructive" data-testid="storefront-products-error">
          {serverError}
        </p>
      ) : null}

      {created && fields.length > 0 ? (
        <div className="flex justify-end">
          <Button type="submit" disabled={save.isPending} aria-busy={save.isPending}>
            {save.isPending ? "Saving…" : "Save courses"}
          </Button>
        </div>
      ) : null}
    </form>
  );
}
