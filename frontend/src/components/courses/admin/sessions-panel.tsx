"use client";

import { useState } from "react";
import Link from "next/link";
import { CalendarClock, FolderOpen, Pencil, Plus, Search, Trash2 } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ReorderableTable, TableSkeleton, moveItem, type ReorderableColumn } from "@/components/courses/admin/reorderable-table";
import { SessionFormDialog } from "@/components/courses/admin/session-form-dialog";
import { formatZonedDateTime } from "@/components/courses/admin/tenant-time";
import { useCourse, useDeleteCourseLesson } from "@/lib/api/courses";
import {
  LESSON_PAYMENT_TYPE_LABELS,
  formatAmount,
  useKlassLessons,
  useKlassModules,
  useLessonResourceTypes,
  useReorderLessons,
  useTenantTimeZone,
  type KlassLesson,
} from "@/lib/api/klass-admin";

const SESSION_CONFLICT_HINT = "Sessions that students have bought, or that are on an order, cannot be deleted.";

/**
 * Klass-style Sessions table for one module (CM-A04/A05): search, ADD
 * SESSION modal, persisted drag/keyboard ordering, Edit/Delete and a link to
 * the existing lesson materials editor. IMPORT SESSIONS and the QR/link
 * actions are not rendered (D20).
 */
export function SessionsPanel({ courseId, moduleId }: { courseId: string; moduleId: string }) {
  const courseQuery = useCourse(courseId);
  const modulesQuery = useKlassModules(courseId);
  const lessonsQuery = useKlassLessons(courseId, moduleId);
  const resourceTypesQuery = useLessonResourceTypes();
  const reorder = useReorderLessons(courseId, moduleId);
  const remove = useDeleteCourseLesson(courseId, moduleId);
  const timeZone = useTenantTimeZone();
  const toast = useAdminToast();
  const [search, setSearch] = useState("");
  const [optimisticRows, setOptimisticRows] = useState<KlassLesson[] | null>(null);
  const [formState, setFormState] = useState<{ open: boolean; lesson: KlassLesson | null; key: number }>({
    open: false,
    lesson: null,
    key: 0,
  });
  const [deleteTarget, setDeleteTarget] = useState<KlassLesson | null>(null);

  if (courseQuery.status === "pending" || modulesQuery.status === "pending") {
    return <TableSkeleton label="Loading module…" />;
  }

  const headerQuery = {
    status:
      courseQuery.status === "error" || modulesQuery.status === "error"
        ? ("error" as const)
        : ("success" as const),
    data:
      courseQuery.data && modulesQuery.data ? { course: courseQuery.data, modules: modulesQuery.data } : undefined,
    error: courseQuery.error ?? modulesQuery.error,
    refetch: () => Promise.all([courseQuery.refetch(), modulesQuery.refetch()]),
  };

  const resourceLabel = (type: string | null) =>
    type ? (resourceTypesQuery.data?.find((option) => option.type === type)?.label ?? type) : "—";

  return (
    <QueryStateBoundary
      query={headerQuery}
      loadingLabel="Loading module…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
    >
      {({ course, modules }) => {
        const currentModule = modules.find((candidate) => candidate.id === moduleId);
        if (!currentModule) {
          return (
            <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center" data-testid="sessions-module-missing">
              <p className="text-sm font-medium text-foreground">Module not found</p>
              <p className="max-w-md text-sm text-muted-foreground">
                This module doesn&apos;t exist in &ldquo;{course.name}&rdquo;, or it was deleted.
              </p>
              <Link href={`/tenant-admin/courses/${courseId}/modules`} className={buttonVariants({ variant: "outline", size: "sm" })}>
                Back to modules
              </Link>
            </div>
          );
        }

        const openForm = (lesson: KlassLesson | null) =>
          setFormState((current) => ({ open: true, lesson, key: current.key + 1 }));
        const tz = timeZone.timeZone;

        return (
          <div className="flex flex-col gap-5">
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-3">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
                  <CalendarClock className="size-5" aria-hidden="true" />
                </span>
                <h1 className="text-xl font-semibold text-foreground" data-testid="sessions-heading">
                  Sessions
                </h1>
              </div>
              <Breadcrumbs
                items={[
                  { label: "Courses", href: "/tenant-admin/courses" },
                  { label: course.name, href: `/tenant-admin/courses/${courseId}` },
                  { label: currentModule.title, href: `/tenant-admin/courses/${courseId}/modules` },
                  { label: "Sessions" },
                ]}
              />
            </div>

            <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
              <div className="relative w-full sm:max-w-sm">
                <label htmlFor="sessions-search" className="sr-only">
                  Search sessions
                </label>
                <Input
                  id="sessions-search"
                  type="search"
                  placeholder="Search"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  className="pr-8"
                  data-testid="sessions-search"
                />
                <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
              </div>
              <Button type="button" onClick={() => openForm(null)} data-testid="sessions-add-session">
                <Plus aria-hidden="true" />
                Add session
              </Button>
            </div>

            <p className="text-xs text-muted-foreground">
              Times shown in {timeZone.source === "tenant" ? "the institute's timezone" : "your browser's timezone"} ({tz}).
            </p>

            {lessonsQuery.status === "pending" ? (
              <TableSkeleton label="Loading sessions…" />
            ) : (
              <QueryStateBoundary
                query={lessonsQuery}
                loadingLabel="Loading sessions…"
                loginPath="/login"
                permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
              >
                {(lessons) => {
                  const sorted = optimisticRows ?? [...lessons].sort((a, b) => a.sequence - b.sequence);
                  const term = search.trim().toLowerCase();
                  const rows = term
                    ? sorted.filter(
                        (lesson) =>
                          lesson.title.toLowerCase().includes(term) ||
                          (lesson.lessonLabel ?? "").toLowerCase().includes(term)
                      )
                    : sorted;

                  if (lessons.length === 0) {
                    return (
                      <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center" data-testid="sessions-empty">
                        <p className="text-sm font-medium text-foreground">No sessions in this module yet</p>
                        <p className="max-w-md text-sm text-muted-foreground">
                          Add a session for each class, recording or document students should open.
                        </p>
                        <Button type="button" size="sm" onClick={() => openForm(null)}>
                          <Plus aria-hidden="true" />
                          Add session
                        </Button>
                      </div>
                    );
                  }
                  if (rows.length === 0) {
                    return (
                      <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-10 text-center" data-testid="sessions-no-match">
                        <p className="text-sm font-medium text-foreground">No sessions match &ldquo;{search.trim()}&rdquo;</p>
                        <Button type="button" size="sm" variant="outline" onClick={() => setSearch("")}>
                          Clear search
                        </Button>
                      </div>
                    );
                  }

                  const handleMove = (from: number, to: number) => {
                    const next = moveItem(rows, from, to);
                    if (next === rows) return;
                    setOptimisticRows(next);
                    reorder.mutate(
                      next.map((lesson) => lesson.id),
                      {
                        onSuccess: () => toast.show({ kind: "success", text: "Session order saved." }),
                        onError: (error) =>
                          toast.show({ kind: "error", text: `Session order was not saved: ${mutationErrorMessage(error)}` }),
                        onSettled: () => setOptimisticRows(null),
                      }
                    );
                  };

                  const columns: ReorderableColumn<KlassLesson>[] = [
                    {
                      key: "name",
                      header: "Name",
                      hideOnCard: true,
                      cell: (lesson) => (
                        <span className="flex flex-col">
                          <span className="font-medium text-foreground">{lesson.title}</span>
                          {lesson.lessonLabel ? <span className="text-xs">{lesson.lessonLabel}</span> : null}
                        </span>
                      ),
                    },
                    { key: "type", header: "Type", cell: (lesson) => resourceLabel(lesson.resourceType) },
                    {
                      key: "from",
                      header: "From",
                      className: "whitespace-nowrap",
                      cell: (lesson) => formatZonedDateTime(lesson.startsAt, tz) || "—",
                    },
                    {
                      key: "to",
                      header: "To",
                      className: "whitespace-nowrap",
                      cell: (lesson) => formatZonedDateTime(lesson.endsAt, tz) || "—",
                    },
                    {
                      key: "paymentType",
                      header: "Payment Type",
                      cell: (lesson) => LESSON_PAYMENT_TYPE_LABELS[lesson.paymentType] ?? lesson.paymentType,
                    },
                    {
                      key: "price",
                      header: `Price (${course.currency || "LKR"})`,
                      headerClassName: "text-right",
                      className: "text-right tabular-nums",
                      cell: (lesson) => formatAmount(lesson.price) ?? "N/A",
                    },
                    {
                      key: "expired",
                      header: "Expired?",
                      cell: (lesson) => (lesson.expired ? <span data-testid="session-expired">Yes</span> : "No"),
                    },
                  ];

                  const refreshing = lessonsQuery.isFetching && !reorder.isPending;
                  return (
                    <div
                      className={cn("flex flex-col gap-2 transition-opacity", refreshing && "opacity-60")}
                      aria-busy={refreshing || undefined}
                      data-testid="sessions-list"
                    >
                      {term ? <p className="text-xs text-muted-foreground">Clear the search to reorder sessions.</p> : null}
                      <ReorderableTable
                        rows={rows}
                        rowKey={(lesson) => lesson.id}
                        rowLabel={(lesson) => lesson.title}
                        columns={columns}
                        caption={`Sessions of ${currentModule.title}`}
                        reorderEnabled={!term}
                        reorderPending={reorder.isPending}
                        onMove={handleMove}
                        rowTestId="session-row"
                        actions={(lesson) => (
                          <>
                            <Link
                              href={`/tenant-admin/courses/${courseId}/content#session-${lesson.id}`}
                              className={buttonVariants({ variant: "ghost", size: "icon-sm" })}
                              aria-label={`Session materials editor (for ${lesson.title})`}
                              title="Session materials editor"
                              data-testid="session-action-content"
                            >
                              <FolderOpen aria-hidden="true" />
                            </Link>
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`Edit ${lesson.title}`}
                              title="Edit"
                              onClick={() => openForm(lesson)}
                              data-testid="session-action-edit"
                            >
                              <Pencil className="text-primary" aria-hidden="true" />
                            </Button>
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`Delete ${lesson.title}`}
                              title="Delete"
                              onClick={() => {
                                remove.reset();
                                setDeleteTarget(lesson);
                              }}
                              data-testid="session-action-delete"
                            >
                              <Trash2 className="text-destructive" aria-hidden="true" />
                            </Button>
                          </>
                        )}
                      />
                      <p className="text-right text-sm text-muted-foreground">
                        {rows.length} of {lessons.length} sessions
                      </p>
                    </div>
                  );
                }}
              </QueryStateBoundary>
            )}

            <SessionFormDialog
              key={formState.key}
              courseId={courseId}
              moduleId={moduleId}
              open={formState.open}
              lesson={formState.lesson}
              timeZone={timeZone}
              nextSequence={(lessonsQuery.data ?? []).reduce((max, lesson) => Math.max(max, lesson.sequence), 0) + 1}
              onOpenChange={(open) => setFormState((current) => ({ ...current, open }))}
              onSaved={(saved, mode) => {
                setFormState((current) => ({ ...current, open: false }));
                toast.show({
                  kind: "success",
                  text: mode === "create" ? `Session “${saved.title}” created.` : `Session “${saved.title}” saved.`,
                });
              }}
            />
            <ConfirmDeleteDialog
              open={deleteTarget !== null}
              onOpenChange={(open) => {
                if (!open) setDeleteTarget(null);
              }}
              title={`Delete “${deleteTarget?.title ?? ""}”?`}
              description="This deletes the session. Sessions with attached materials, purchases or orders cannot be deleted."
              confirmLabel="Delete session"
              pending={remove.isPending}
              error={remove.isError ? mutationErrorMessage(remove.error, SESSION_CONFLICT_HINT) : null}
              onConfirm={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                remove.mutate(target.id, {
                  onSuccess: () => {
                    setDeleteTarget(null);
                    toast.show({ kind: "success", text: `Session “${target.title}” deleted.` });
                  },
                });
              }}
            />
            <AdminToast message={toast.message} onDismiss={toast.dismiss} />
          </div>
        );
      }}
    </QueryStateBoundary>
  );
}
