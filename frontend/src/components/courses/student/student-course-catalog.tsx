"use client";

import { useEffect, useId, useState } from "react";
import Link from "next/link";
import { usePathname, useSearchParams } from "next/navigation";
import { ArrowRight, Check, Loader2, Search, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { EmptyState } from "@/components/states/empty-state";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { cn } from "@/lib/utils";
import {
  useCourseTags,
  useSelfEnroll,
  useStudentCatalog,
  type CatalogCourse,
  type CatalogSort,
  type CatalogView,
} from "@/lib/api/klass-student";
import { CourseCover, PriceBadge } from "./course-visuals";
import { EnrollConfirmDialog, enrollErrorMessage } from "./enroll-confirm-dialog";
import { StudentToast, useStudentToast } from "./student-toast";

const PAGE_SIZE = 20;

/** `?view=available` selects Available Courses; anything else is Enrolled. */
export function viewFromParam(value: string | null): CatalogView {
  return value?.toLowerCase() === "available" ? "AVAILABLE" : "ENROLLED";
}

function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs);
    return () => clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/**
 * Klass student course discovery (CM-S01, EP-E01..E05): Enrolled / Available
 * views, server search (debounced), Sort by, tenant tag chips and a card grid.
 * Enroll is free (ADR-028): confirm → one POST → the card leaves Available
 * only after the server confirms and the catalog is re-read. Nothing is shown
 * as enrolled optimistically, and enrollment never goes through checkout.
 */
export function StudentCourseCatalog() {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const view = viewFromParam(searchParams.get("view"));
  const searchId = useId();
  const sortId = useId();

  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 350);
  const [sort, setSort] = useState<CatalogSort>("DEFAULT");
  const [tagId, setTagId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [pagedFilters, setPagedFilters] = useState({ view, search, sort, tagId });

  // Any filter change resets to the first page (derived-state pattern, no effect).
  if (
    pagedFilters.view !== view ||
    pagedFilters.search !== search ||
    pagedFilters.sort !== sort ||
    pagedFilters.tagId !== tagId
  ) {
    setPagedFilters({ view, search, sort, tagId });
    setPage(0);
  }

  const catalogQuery = useStudentCatalog({ view, search, tagId, sort, page, size: PAGE_SIZE });
  const tagsQuery = useCourseTags();
  const enroll = useSelfEnroll();
  const toast = useStudentToast();

  const [confirmCourse, setConfirmCourse] = useState<CatalogCourse | null>(null);
  const [pendingCourseId, setPendingCourseId] = useState<string | null>(null);
  const [failures, setFailures] = useState<Record<string, string>>({});

  // The URL (`?view=`) is the single source of truth for the view: `view` above
  // is derived from useSearchParams on every render, so a pill click, the nav
  // links and back/forward all land on the same state. The switch is a shallow
  // History API update (Next keeps useSearchParams in sync with it) rather than
  // router.replace: switching view is client-only, so it needs no server round
  // trip and never touches the router's route cache. (While the page was a
  // static route, a production build's router reused the prefetched entry for
  // `/student/courses?view=available` - canonical URL included - so a
  // router.replace to `/student/courses` wrote `?view=available` straight back
  // and the Enrolled pill appeared to do nothing; see the page component.)
  const setView = (next: CatalogView) => {
    if (next === view) return;
    const params = new URLSearchParams(searchParams.toString());
    if (next === "AVAILABLE") params.set("view", "available");
    else params.delete("view");
    const qs = params.toString();
    window.history.replaceState(null, "", qs ? `${pathname}?${qs}` : pathname);
  };

  const runEnroll = async (course: CatalogCourse) => {
    if (pendingCourseId) return;
    setPendingCourseId(course.courseId);
    setFailures((current) => {
      const next = { ...current };
      delete next[course.courseId];
      return next;
    });
    try {
      await enroll.mutateAsync(course.courseId);
      toast.show({ kind: "success", text: `You're enrolled in ${course.title}. Find it under Enrolled Courses.` });
    } catch (error) {
      const message = enrollErrorMessage(error);
      setFailures((current) => ({ ...current, [course.courseId]: message }));
      toast.show({ kind: "error", text: message });
    } finally {
      setPendingCourseId(null);
    }
  };

  return (
    <div className="mx-auto flex w-full max-w-7xl flex-col gap-6" data-testid="student-course-catalog">
      <section aria-label="Search and filters" className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4 shadow-sm">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
          <div className="flex w-full flex-col gap-1 sm:max-w-md">
            <Label htmlFor={searchId}>Search courses</Label>
            <div className="relative">
              <Search className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <Input
                id={searchId}
                type="search"
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                placeholder="Search courses"
                className="pl-8"
                data-testid="catalog-search"
              />
            </div>
          </div>
          <div className="flex w-full flex-col gap-1 sm:w-48">
            <Label htmlFor={sortId}>Sort by</Label>
            <select
              id={sortId}
              value={sort}
              onChange={(event) => setSort(event.target.value as CatalogSort)}
              data-testid="catalog-sort"
              className="h-8 rounded-lg border border-input bg-background px-2.5 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <option value="DEFAULT">Default</option>
              <option value="TITLE">Title</option>
            </select>
          </div>
        </div>

        {tagsQuery.isSuccess && tagsQuery.data.length > 0 ? (
          <div role="group" aria-label="Filter by tag" className="flex flex-wrap gap-2 border-t border-border pt-3" data-testid="catalog-tags">
            {tagsQuery.data.map((tag) => {
              const selected = tag.id === tagId;
              return (
                <button
                  key={tag.id}
                  type="button"
                  aria-pressed={selected}
                  data-testid="catalog-tag-chip"
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
        ) : null}
        {tagsQuery.isError ? (
          <p className="border-t border-border pt-3 text-xs text-muted-foreground" role="status">
            Tag filters couldn&apos;t load.{" "}
            <button type="button" className="underline" onClick={() => tagsQuery.refetch()}>
              Try again
            </button>
          </p>
        ) : null}
      </section>

      <LiveRegion
        message={
          catalogQuery.isFetching
            ? "Loading courses…"
            : catalogQuery.isSuccess
              ? `${catalogQuery.data.totalElements} ${catalogQuery.data.totalElements === 1 ? "course" : "courses"} found`
              : ""
        }
      />


      <QueryStateBoundary
        query={catalogQuery}
        loadingLabel="Loading courses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {(data) => {
          if (data.content.length === 0) {
            const filtered = search.trim().length > 0 || tagId !== null;
            return (
              <div data-testid="catalog-empty">
                {filtered ? (
                  <EmptyState
                    title="No courses match your search"
                    description="Try a different search term or clear the tag filter."
                    action={{
                      label: "Clear filters",
                      onClick: () => {
                        setSearchInput("");
                        setTagId(null);
                      },
                    }}
                  />
                ) : view === "ENROLLED" ? (
                  <EmptyState
                    title="You haven't enrolled in any courses yet"
                    description="Browse Available Courses and enroll for free to get started."
                    action={{ label: "See available courses", onClick: () => setView("AVAILABLE") }}
                  />
                ) : (
                  <EmptyState
                    title="No courses are open for enrollment"
                    description="You're already in every open course, or your institute hasn't opened new ones yet. Check back soon."
                  />
                )}
              </div>
            );
          }

          return (
            <div className="flex flex-col gap-4">
              <ul
                aria-label={view === "ENROLLED" ? "Enrolled Courses" : "Available Courses"}
                aria-busy={catalogQuery.isFetching}
                className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4"
                data-testid="catalog-grid"
              >
                {data.content.map((course) => (
                  <CatalogCard
                    key={course.courseId}
                    course={course}
                    view={view}
                    pending={pendingCourseId === course.courseId}
                    anyPending={pendingCourseId !== null}
                    failure={failures[course.courseId] ?? null}
                    onEnroll={() => setConfirmCourse(course)}
                    onRetry={() => runEnroll(course)}
                  />
                ))}
              </ul>

              {data.totalPages > 1 ? (
                <nav aria-label="Course list pages" className="flex items-center justify-between gap-3">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={data.page <= 0}
                    onClick={() => setPage((current) => Math.max(current - 1, 0))}
                  >
                    Previous
                  </Button>
                  <span className="text-sm text-muted-foreground">
                    Page {data.page + 1} of {data.totalPages}
                  </span>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={data.page + 1 >= data.totalPages}
                    onClick={() => setPage((current) => current + 1)}
                  >
                    Next
                  </Button>
                </nav>
              ) : null}
            </div>
          );
        }}
      </QueryStateBoundary>

      <EnrollConfirmDialog
        courseTitle={confirmCourse?.title ?? null}
        open={confirmCourse !== null}
        onOpenChange={(open) => {
          if (!open) setConfirmCourse(null);
        }}
        onConfirm={() => {
          const course = confirmCourse;
          setConfirmCourse(null);
          if (course) void runEnroll(course);
        }}
      />

      <StudentToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}

function CatalogCard({
  course,
  view,
  pending,
  anyPending,
  failure,
  onEnroll,
  onRetry,
}: {
  course: CatalogCourse;
  view: CatalogView;
  pending: boolean;
  anyPending: boolean;
  failure: string | null;
  onEnroll: () => void;
  onRetry: () => void;
}) {
  const href = `/student/courses/${course.courseId}`;
  return (
    <li
      className="flex h-full flex-col overflow-hidden rounded-xl border border-border bg-card shadow-sm"
      data-testid="catalog-course-card"
      data-course-id={course.courseId}
    >
      <div className="relative">
        <CourseCover src={course.coverImageUrl} title={course.title} />
        <PriceBadge
          priceDisplay={course.priceDisplay}
          pricingModel={course.pricingModel}
          className="absolute bottom-3 left-3 bg-white/95 dark:bg-blue-950/95"
        />
      </div>
      <div className="flex flex-1 flex-col gap-2 p-4">
        <h2 className="text-base font-semibold leading-snug text-foreground" data-testid="catalog-course-title">
          {course.title}
        </h2>
        {course.teacherName ? (
          <p className="flex items-center gap-1.5 text-sm text-muted-foreground">
            <UserRound className="size-4" aria-hidden="true" />
            <span className="sr-only">Teacher:</span>
            {course.teacherName}
          </p>
        ) : null}
        {failure ? (
          <div role="alert" className="mt-1 flex flex-col items-start gap-1 text-xs text-destructive" data-testid="catalog-enroll-error">
            <span>{failure}</span>
            <button type="button" className="font-medium underline" onClick={onRetry} disabled={anyPending}>
              Try again
            </button>
          </div>
        ) : null}
      </div>
      <div className="flex items-center justify-end gap-3 border-t border-border px-4 py-3">
        {view === "ENROLLED" ? (
          <Link
            href={href}
            aria-label={`Open course ${course.title}`}
            data-testid="catalog-open-course"
            className="inline-flex items-center gap-1 text-sm font-semibold text-blue-700 hover:underline dark:text-blue-300"
          >
            Open course
            <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
        ) : (
          <>
            <Link
              href={href}
              aria-label={`View course ${course.title}`}
              data-testid="catalog-view-course"
              className="text-sm font-medium text-blue-700 hover:underline dark:text-blue-300"
            >
              View course
            </Link>
            <Button
              type="button"
              onClick={onEnroll}
              disabled={anyPending}
              aria-busy={pending}
              aria-label={pending ? `Enrolling in ${course.title}` : `Enroll in ${course.title}`}
              data-testid="catalog-enroll"
              className="min-w-20 bg-blue-600 text-white hover:bg-blue-700"
            >
              {pending ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : "Enroll"}
            </Button>
          </>
        )}
      </div>
    </li>
  );
}
