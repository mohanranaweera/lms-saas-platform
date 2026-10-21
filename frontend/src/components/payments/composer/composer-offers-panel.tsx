"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { Check, ChevronLeft, ChevronRight, Clock, ExternalLink } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { cn } from "@/lib/utils";
import {
  formatCurrency,
  monthShort,
  useCheckoutCourses,
  useCheckoutOffers,
  type CheckoutCourse,
  type CheckoutCourseTag,
  type CheckoutOfferItem,
  type CheckoutOffers,
} from "@/lib/api/klass-student";
import {
  TAB_HEADINGS,
  composerErrorMessage,
  itemKey,
  monthlyLabel,
  scopeForTab,
  type ComposerTab,
  type SelectedItem,
} from "./composer-model";

/**
 * Left side of the composer: "Select a course" for the active tab's scope,
 * then that course's server-provided offers. Offer state (AVAILABLE / PAID /
 * PENDING) and amounts are the server's; PAID and PENDING items can't be
 * selected, which protects against accidental duplicate charges (the server
 * re-checks on quote and order anyway). Switching course keeps the cart: every
 * item is keyed and requested with its own course id.
 */
export function ComposerOffersPanel({
  tab,
  courseId,
  courseTitle,
  monthlyYear,
  selectedKeys,
  onSelectCourse,
  onYearChange,
  onToggleItem,
  onCloseForCheckout,
  cartCountFor,
}: {
  tab: ComposerTab;
  courseId: string | null;
  courseTitle: string | null;
  monthlyYear: number | null;
  selectedKeys: Set<string>;
  onSelectCourse: (courseId: string, title: string) => void;
  onYearChange: (year: number) => void;
  onToggleItem: (item: SelectedItem) => void;
  onCloseForCheckout: () => void;
  /** How many items of a course are already in the cart (display hint only). */
  cartCountFor?: (courseId: string) => number;
}) {
  const scope = scopeForTab(tab);
  const selectId = useId();
  const coursesQuery = useCheckoutCourses(scope);
  const courses = coursesQuery.data ?? [];
  const courseInList = courseId !== null && courses.some((course) => course.courseId === courseId);
  const [tagId, setTagId] = useState<string | null>(null);
  const tags = collectTags(courses);
  // A tag that isn't on any course of this tab's list means "no filter".
  const activeTagId = tagId !== null && tags.some((tag) => tag.id === tagId) ? tagId : null;
  // Client-side narrowing of the server's list only; the currently selected
  // course stays in the dropdown so the selection never silently disappears.
  const visibleCourses =
    activeTagId === null
      ? courses
      : courses.filter(
          (course) =>
            course.courseId === courseId || (course.tags ?? []).some((tag) => tag.id === activeTagId)
        );
  const tagMatchCount =
    activeTagId === null ? null : courses.filter((course) => (course.tags ?? []).some((tag) => tag.id === activeTagId)).length;
  const offersQuery = useCheckoutOffers(courseId, scope, tab === "MONTHLY" ? monthlyYear : null, {
    enabled: courseInList,
  });

  return (
    <div className="flex flex-col gap-4" data-testid="composer-offers-panel" data-scope={scope}>
      <h3 className="text-sm font-semibold text-foreground">{TAB_HEADINGS[tab]}</h3>

      {coursesQuery.isSuccess && tags.length > 0 ? (
        <div className="flex flex-col gap-1.5">
          <div role="group" aria-label="Filter courses by tag" className="flex flex-wrap gap-2" data-testid="composer-tags">
            {tags.map((tag) => {
              const selected = tag.id === activeTagId;
              return (
                <button
                  key={tag.id}
                  type="button"
                  aria-pressed={selected}
                  data-testid="composer-tag-chip"
                  data-tag-id={tag.id}
                  onClick={() => setTagId(selected ? null : tag.id)}
                  className={cn(
                    "inline-flex items-center gap-1 rounded-full border px-3 py-1 text-xs font-medium outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
                    selected
                      ? "border-blue-600 bg-blue-600 text-white"
                      : "border-border bg-background text-foreground hover:bg-muted"
                  )}
                >
                  {selected ? <Check className="size-3" aria-hidden="true" /> : null}
                  {tag.name}
                </button>
              );
            })}
          </div>
          <p className="text-xs text-muted-foreground" aria-live="polite" data-testid="composer-tag-filter-status">
            {tagMatchCount === null
              ? ""
              : `${tagMatchCount} ${tagMatchCount === 1 ? "course matches" : "courses match"} this tag. Select the tag again to show all courses.`}
          </p>
        </div>
      ) : null}

      <div className="flex flex-col gap-1">
        <Label htmlFor={selectId}>Select a course</Label>
        {coursesQuery.isPending ? (
          <Skeleton className="h-8 w-full" />
        ) : coursesQuery.isError ? (
          <div role="alert" className="flex flex-wrap items-center gap-2 text-sm text-destructive">
            {composerErrorMessage(coursesQuery.error)}
            <Button type="button" size="sm" variant="outline" onClick={() => coursesQuery.refetch()}>
              Try again
            </Button>
          </div>
        ) : (
          <select
            id={selectId}
            value={courseInList ? (courseId ?? "") : ""}
            onChange={(event) => {
              const next = courses.find((course) => course.courseId === event.target.value);
              if (next) onSelectCourse(next.courseId, next.title);
            }}
            disabled={courses.length === 0}
            data-testid="composer-course-select"
            className="h-9 w-full rounded-lg border border-input bg-background px-2.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-60"
          >
            <option value="" disabled>
              {courses.length === 0 ? "No data available" : "Select a course"}
            </option>
            {visibleCourses.map((course) => {
              const inCart = cartCountFor?.(course.courseId) ?? 0;
              return (
                <option key={course.courseId} value={course.courseId}>
                  {inCart > 0 ? `${course.title} (${inCart} in cart)` : course.title}
                </option>
              );
            })}
          </select>
        )}
      </div>

      {coursesQuery.isSuccess && courses.length === 0 ? (
        <NoData
          text={
            tab === "DAILY" || tab === "INSTALLMENT"
              ? "No data available. Your institute doesn't offer this payment type yet."
              : "No data available. None of your enrolled courses has this payment type."
          }
        />
      ) : null}

      {coursesQuery.isSuccess && courseId !== null && !courseInList && courses.length > 0 ? (
        <p className="text-sm text-muted-foreground" data-testid="composer-course-not-in-scope">
          {courseTitle ?? "The selected course"} has no {TAB_HEADINGS[tab].toLowerCase()}. Pick another course or
          payment type.
        </p>
      ) : null}

      {courseInList ? (
        offersQuery.isPending ? (
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-3" aria-busy="true">
            {Array.from({ length: 6 }, (_, index) => (
              <Skeleton key={index} className="h-16" />
            ))}
            <span className="sr-only" role="status">
              Loading offers…
            </span>
          </div>
        ) : offersQuery.isError ? (
          <div role="alert" className="flex flex-col items-start gap-2 text-sm text-destructive">
            {composerErrorMessage(offersQuery.error)}
            <Button type="button" size="sm" variant="outline" onClick={() => offersQuery.refetch()}>
              Try again
            </Button>
          </div>
        ) : offersQuery.data ? (
          <OffersBody
            tab={tab}
            offers={offersQuery.data}
            courseTitle={courseTitle}
            monthlyYear={monthlyYear}
            selectedKeys={selectedKeys}
            onYearChange={onYearChange}
            onToggleItem={onToggleItem}
            onCloseForCheckout={onCloseForCheckout}
          />
        ) : null
      ) : null}
    </div>
  );
}

/** Distinct tags across the listed courses (by id), sorted by name. */
function collectTags(courses: CheckoutCourse[]): CheckoutCourseTag[] {
  const byId = new Map<string, CheckoutCourseTag>();
  for (const course of courses) {
    for (const tag of course.tags ?? []) {
      if (!byId.has(tag.id)) byId.set(tag.id, tag);
    }
  }
  return [...byId.values()].sort((a, b) => a.name.localeCompare(b.name));
}

function NoData({ text }: { text: string }) {
  return (
    <p
      role="status"
      className="rounded-lg border border-dashed border-border px-4 py-6 text-center text-sm text-muted-foreground"
      data-testid="composer-no-data"
    >
      {text}
    </p>
  );
}

function StateTag({ state }: { state: CheckoutOfferItem["state"] }) {
  if (state === "PAID") {
    return (
      <span className="inline-flex items-center gap-1 text-xs font-medium text-green-700 dark:text-green-400">
        <Check className="size-3" aria-hidden="true" />
        Paid
      </span>
    );
  }
  if (state === "PENDING") {
    return (
      <span className="inline-flex items-center gap-1 text-xs font-medium text-amber-700 dark:text-amber-400">
        <Clock className="size-3" aria-hidden="true" />
        Pending
      </span>
    );
  }
  return null;
}

function OffersBody({
  tab,
  offers,
  courseTitle,
  monthlyYear,
  selectedKeys,
  onYearChange,
  onToggleItem,
  onCloseForCheckout,
}: {
  tab: ComposerTab;
  offers: CheckoutOffers;
  courseTitle: string | null;
  monthlyYear: number | null;
  selectedKeys: Set<string>;
  onYearChange: (year: number) => void;
  onToggleItem: (item: SelectedItem) => void;
  onCloseForCheckout: () => void;
}) {
  if (!offers.supported || (offers.items.length === 0 && tab !== "MONTHLY")) {
    return <NoData text="No data available for this course." />;
  }

  if (tab === "MONTHLY") {
    return (
      <MonthlyGrid
        offers={offers}
        courseTitle={courseTitle}
        monthlyYear={monthlyYear}
        selectedKeys={selectedKeys}
        onYearChange={onYearChange}
        onToggleItem={onToggleItem}
      />
    );
  }

  if (tab === "COURSE") {
    return (
      <ul className="flex flex-col gap-2" data-testid="composer-course-offers">
        {offers.items.map((item, index) => (
          <li
            key={`${item.label}-${index}`}
            className="flex flex-col gap-3 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between"
          >
            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium text-foreground">{item.label}</span>
              <span className="text-sm text-muted-foreground">{formatCurrency(item.amount, offers.currency)}</span>
              <StateTag state={item.state} />
            </div>
            {item.state === "AVAILABLE" ? (
              <Link
                href={`/student/checkout/${offers.courseId}`}
                onClick={onCloseForCheckout}
                data-testid="composer-course-checkout-link"
                className={cn(buttonVariants({ size: "sm" }), "bg-blue-600 text-white hover:bg-blue-700")}
              >
                Continue to full-course checkout
                <ExternalLink aria-hidden="true" />
              </Link>
            ) : null}
          </li>
        ))}
        <li className="text-xs text-muted-foreground">
          A full-course payment uses the course checkout page and isn&apos;t added to this summary.
        </li>
      </ul>
    );
  }

  // MODULE / SESSION: checkbox list.
  return (
    <fieldset className="flex flex-col gap-2" data-testid="composer-item-list">
      <legend className="sr-only">{tab === "MODULE" ? "Modules" : "Sessions"}</legend>
      {offers.items.map((item) => {
        const key = itemKey({ ...item, courseId: offers.courseId });
        const selectable = item.state === "AVAILABLE";
        const checked = selectedKeys.has(key);
        const inputId = `composer-item-${key}`;
        return (
          <div
            key={key}
            className={cn(
              "flex items-center gap-3 rounded-lg border px-3 py-2",
              checked ? "border-blue-600 bg-blue-50 dark:bg-blue-950" : "border-border",
              !selectable && "opacity-70"
            )}
            data-testid="composer-item"
            data-state={item.state}
          >
            <input
              id={inputId}
              type="checkbox"
              className="size-4 accent-blue-600"
              checked={checked}
              disabled={!selectable}
              onChange={() =>
                onToggleItem({
                  key,
                  label: item.label,
                  courseTitle,
                  request:
                    item.scope === "MODULE"
                      ? { courseId: offers.courseId, scope: "MODULE", moduleId: item.moduleId ?? undefined }
                      : { courseId: offers.courseId, scope: "LESSON", lessonId: item.lessonId ?? undefined },
                })
              }
            />
            <label htmlFor={inputId} className="flex flex-1 flex-wrap items-center justify-between gap-2 text-sm">
              <span className="text-foreground">{item.label}</span>
              <span className="flex items-center gap-2">
                <StateTag state={item.state} />
                <span className="font-medium text-foreground">{formatCurrency(item.amount, offers.currency)}</span>
              </span>
            </label>
          </div>
        );
      })}
    </fieldset>
  );
}

function MonthlyGrid({
  offers,
  courseTitle,
  monthlyYear,
  selectedKeys,
  onYearChange,
  onToggleItem,
}: {
  offers: CheckoutOffers;
  courseTitle: string | null;
  monthlyYear: number | null;
  selectedKeys: Set<string>;
  onYearChange: (year: number) => void;
  onToggleItem: (item: SelectedItem) => void;
}) {
  const years = [...offers.years].sort((a, b) => a - b);
  const year = monthlyYear ?? offers.items.find((item) => item.year !== null)?.year ?? years[years.length - 1] ?? null;
  if (year === null) {
    return <NoData text="No data available for this course." />;
  }
  const index = years.indexOf(year);
  const prevYear = index > 0 ? years[index - 1] : undefined;
  const nextYear = index >= 0 && index < years.length - 1 ? years[index + 1] : undefined;

  return (
    <div className="flex flex-col gap-3" data-testid="composer-monthly">
      <div className="flex items-center justify-between gap-2">
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={prevYear !== undefined ? `Previous year, ${prevYear}` : "No earlier year"}
          disabled={prevYear === undefined}
          onClick={() => prevYear !== undefined && onYearChange(prevYear)}
          data-testid="composer-year-prev"
        >
          <ChevronLeft aria-hidden="true" />
        </Button>
        <span className="text-base font-semibold text-foreground" aria-live="polite" data-testid="composer-year">
          {year}
        </span>
        <Button
          type="button"
          variant="ghost"
          size="icon-sm"
          aria-label={nextYear !== undefined ? `Next year, ${nextYear}` : "No later year"}
          disabled={nextYear === undefined}
          onClick={() => nextYear !== undefined && onYearChange(nextYear)}
          data-testid="composer-year-next"
        >
          <ChevronRight aria-hidden="true" />
        </Button>
      </div>

      <ul className="grid grid-cols-2 gap-2 sm:grid-cols-3" aria-label={`Months in ${year}`}>
        {Array.from({ length: 12 }, (_, i) => i + 1).map((month) => {
          const offer = offers.items.find((item) => item.year === year && item.month === month);
          const key = itemKey({ courseId: offers.courseId, scope: "MONTHLY", year, month });
          const selected = selectedKeys.has(key);
          const selectable = offer?.state === "AVAILABLE";
          const stateText = !offer ? "Not offered" : offer.state === "PAID" ? "Paid" : offer.state === "PENDING" ? "Pending" : null;
          return (
            <li key={month}>
              <button
                type="button"
                aria-pressed={selectable ? selected : undefined}
                disabled={!selectable}
                onClick={() =>
                  onToggleItem({
                    key,
                    label: monthlyLabel(year, month),
                    courseTitle,
                    request: { courseId: offers.courseId, scope: "MONTHLY", year, month },
                  })
                }
                data-testid="composer-month"
                data-month={month}
                data-state={offer?.state ?? "NONE"}
                aria-label={`${monthShort(month)} ${year}${offer ? `, ${formatCurrency(offer.amount, offers.currency)}` : ""}${stateText ? `, ${stateText}` : ""}`}
                className={cn(
                  "flex h-16 w-full flex-col items-start justify-center rounded-lg border px-3 text-left outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
                  selected
                    ? "border-blue-600 bg-blue-600 text-white"
                    : selectable
                      ? "border-border bg-background hover:border-blue-400"
                      : "cursor-not-allowed border-border bg-muted/40 text-muted-foreground"
                )}
              >
                <span className="text-sm font-semibold">{monthShort(month)}</span>
                <span className="text-xs">
                  {offer ? (stateText ?? formatCurrency(offer.amount, offers.currency)) : "—"}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </div>
  );
}
