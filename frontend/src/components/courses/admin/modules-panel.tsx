"use client";

import { useState } from "react";
import Link from "next/link";
import { FolderOpen, ListTree, Pencil, Plus, Search, Trash2 } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { ModuleFormDialog } from "@/components/courses/admin/module-form-dialog";
import { ReorderableTable, TableSkeleton, moveItem, type ReorderableColumn } from "@/components/courses/admin/reorderable-table";
import { useCourse, useDeleteCourseModule } from "@/lib/api/courses";
import { formatAmount, useKlassModules, useReorderModules, type KlassModule } from "@/lib/api/klass-admin";

const MODULE_CONFLICT_HINT =
  "Modules that students have bought, or that are on an order, cannot be deleted.";

/**
 * Klass-style "Modules of <course>" table (CM-A02/A03): search (client-side
 * over the course's modules), ADD MODULE modal, persisted drag/keyboard
 * ordering, Sessions/Edit/Delete actions. IMPORT MODULES is not rendered
 * (D20). The pre-existing module/lesson/material editor is kept at
 * `/tenant-admin/courses/[courseId]/content` and linked from here.
 */
export function ModulesPanel({ courseId }: { courseId: string }) {
  const courseQuery = useCourse(courseId);
  const modulesQuery = useKlassModules(courseId);
  const reorder = useReorderModules(courseId);
  const remove = useDeleteCourseModule(courseId);
  const toast = useAdminToast();
  const [search, setSearch] = useState("");
  const [optimisticRows, setOptimisticRows] = useState<KlassModule[] | null>(null);
  const [formState, setFormState] = useState<{ open: boolean; module: KlassModule | null; key: number }>({
    open: false,
    module: null,
    key: 0,
  });
  const [deleteTarget, setDeleteTarget] = useState<KlassModule | null>(null);

  if (courseQuery.status === "pending") {
    return <TableSkeleton label="Loading course…" />;
  }

  return (
    <QueryStateBoundary
      query={courseQuery}
      loadingLabel="Loading course…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
    >
      {(course) => {
        const openForm = (module: KlassModule | null) =>
          setFormState((current) => ({ open: true, module, key: current.key + 1 }));

        return (
          <div className="flex flex-col gap-5">
            <div className="flex flex-col gap-2">
              <div className="flex items-center gap-3">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
                  <ListTree className="size-5" aria-hidden="true" />
                </span>
                <h1 className="text-xl font-semibold text-foreground" data-testid="modules-heading">
                  Modules of {course.name}
                </h1>
              </div>
              <Breadcrumbs
                items={[
                  { label: "Courses", href: "/tenant-admin/courses" },
                  { label: course.name, href: `/tenant-admin/courses/${courseId}` },
                  { label: "Modules" },
                ]}
              />
            </div>

            <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
              <div className="relative w-full sm:max-w-sm">
                <label htmlFor="modules-search" className="sr-only">
                  Search modules
                </label>
                <Input
                  id="modules-search"
                  type="search"
                  placeholder="Search"
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                  className="pr-8"
                  data-testid="modules-search"
                />
                <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
              </div>
              <div className="flex flex-wrap gap-2">
                <Link
                  href={`/tenant-admin/courses/${courseId}/content`}
                  className={buttonVariants({ variant: "outline" })}
                  data-testid="modules-manage-content"
                >
                  <FolderOpen aria-hidden="true" />
                  Session materials &amp; videos
                </Link>
                <Button type="button" onClick={() => openForm(null)} data-testid="modules-add-module">
                  <Plus aria-hidden="true" />
                  Add module
                </Button>
              </div>
            </div>

            {modulesQuery.status === "pending" ? (
              <TableSkeleton label="Loading modules…" />
            ) : (
              <QueryStateBoundary
                query={modulesQuery}
                loadingLabel="Loading modules…"
                loginPath="/login"
                permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
              >
                {(modules) => {
                  const sorted = optimisticRows ?? [...modules].sort((a, b) => a.sequence - b.sequence);
                  const term = search.trim().toLowerCase();
                  const rows = term ? sorted.filter((module) => module.title.toLowerCase().includes(term)) : sorted;

                  if (modules.length === 0) {
                    return (
                      <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center" data-testid="modules-empty">
                        <p className="text-sm font-medium text-foreground">No modules yet</p>
                        <p className="max-w-md text-sm text-muted-foreground">
                          Modules group a course&apos;s sessions, for example &ldquo;Live Zoom classes&rdquo; or &ldquo;Recordings&rdquo;.
                        </p>
                        <Button type="button" size="sm" onClick={() => openForm(null)}>
                          <Plus aria-hidden="true" />
                          Add module
                        </Button>
                      </div>
                    );
                  }
                  if (rows.length === 0) {
                    return (
                      <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-10 text-center" data-testid="modules-no-match">
                        <p className="text-sm font-medium text-foreground">No modules match &ldquo;{search.trim()}&rdquo;</p>
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
                      next.map((module) => module.id),
                      {
                        onSuccess: () => toast.show({ kind: "success", text: "Module order saved." }),
                        onError: (error) =>
                          toast.show({ kind: "error", text: `Module order was not saved: ${mutationErrorMessage(error)}` }),
                        onSettled: () => setOptimisticRows(null),
                      }
                    );
                  };

                  const columns: ReorderableColumn<KlassModule>[] = [
                    {
                      key: "name",
                      header: "Name",
                      cell: (module) => <span className="font-medium text-foreground">{module.title}</span>,
                      hideOnCard: true,
                    },
                    {
                      key: "price",
                      header: `Price (${course.currency || "LKR"})`,
                      headerClassName: "text-right",
                      className: "text-right tabular-nums",
                      cell: (module) => formatAmount(module.price) ?? "N/A",
                    },
                  ];

                  const refreshing = modulesQuery.isFetching && !reorder.isPending;
                  return (
                    <div
                      className={cn("flex flex-col gap-2 transition-opacity", refreshing && "opacity-60")}
                      aria-busy={refreshing || undefined}
                      data-testid="modules-list"
                    >
                      {term ? <p className="text-xs text-muted-foreground">Clear the search to reorder modules.</p> : null}
                      <ReorderableTable
                        rows={rows}
                        rowKey={(module) => module.id}
                        rowLabel={(module) => module.title}
                        columns={columns}
                        caption={`Modules of ${course.name}`}
                        reorderEnabled={!term}
                        reorderPending={reorder.isPending}
                        onMove={handleMove}
                        rowTestId="module-row"
                        actions={(module) => (
                          <>
                            <Link
                              href={`/tenant-admin/courses/${courseId}/modules/${module.id}/sessions`}
                              className={buttonVariants({ variant: "ghost", size: "icon-sm" })}
                              aria-label={`Sessions of ${module.title}`}
                              title="Sessions"
                              data-testid="module-action-sessions"
                            >
                              <ListTree className="text-green-700 dark:text-green-500" aria-hidden="true" />
                            </Link>
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`Edit ${module.title}`}
                              title="Edit"
                              onClick={() => openForm(module)}
                              data-testid="module-action-edit"
                            >
                              <Pencil className="text-primary" aria-hidden="true" />
                            </Button>
                            <Button
                              type="button"
                              variant="ghost"
                              size="icon-sm"
                              aria-label={`Delete ${module.title}`}
                              title="Delete"
                              onClick={() => {
                                remove.reset();
                                setDeleteTarget(module);
                              }}
                              data-testid="module-action-delete"
                            >
                              <Trash2 className="text-destructive" aria-hidden="true" />
                            </Button>
                          </>
                        )}
                      />
                      <p className="text-right text-sm text-muted-foreground">
                        {rows.length} of {modules.length} modules
                      </p>
                    </div>
                  );
                }}
              </QueryStateBoundary>
            )}

            <ModuleFormDialog
              key={formState.key}
              courseId={courseId}
              open={formState.open}
              module={formState.module}
              nextSequence={(modulesQuery.data ?? []).reduce((max, module) => Math.max(max, module.sequence), 0) + 1}
              onOpenChange={(open) => setFormState((current) => ({ ...current, open }))}
              onSaved={(saved, mode) => {
                setFormState((current) => ({ ...current, open: false }));
                toast.show({
                  kind: "success",
                  text: mode === "create" ? `Module “${saved.title}” created.` : `Module “${saved.title}” saved.`,
                });
              }}
            />
            <ConfirmDeleteDialog
              open={deleteTarget !== null}
              onOpenChange={(open) => {
                if (!open) setDeleteTarget(null);
              }}
              title={`Delete “${deleteTarget?.title ?? ""}”?`}
              description="This deletes the module and its sessions. It cannot be undone."
              confirmLabel="Delete module"
              pending={remove.isPending}
              error={remove.isError ? mutationErrorMessage(remove.error, MODULE_CONFLICT_HINT) : null}
              onConfirm={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                remove.mutate(target.id, {
                  onSuccess: () => {
                    setDeleteTarget(null);
                    toast.show({ kind: "success", text: `Module “${target.title}” deleted.` });
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
