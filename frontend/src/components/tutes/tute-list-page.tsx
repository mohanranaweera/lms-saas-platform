"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { AlertTriangle, Eye, FileText, Pencil, Plus, Search, Trash2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { Label } from "@/components/ui/label";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast } from "@/components/courses/admin/admin-toast";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { TableSkeleton } from "@/components/courses/admin/reorderable-table";
import { HubPagination } from "@/components/payments/hub/hub-pagination";
import { TuteFormDialog } from "@/components/tutes/tute-form-dialog";
import type { TuteRoutes } from "@/components/tutes/tute-routes";
import { useCourse } from "@/lib/api/courses";
import { useCourseTutes, useDeleteTute, type TuteView } from "@/lib/api/tutes";
import { TUTE_PAYMENT_TYPE_LABELS, formatTutePrice } from "@/lib/tutes/tute-form";
import { TUTE_CAP, useTuteCapabilities } from "@/lib/tutes/use-tute-capabilities";
import { cn } from "@/lib/utils";

export const TUTE_PAGE_SIZES = [10, 25, 50] as const;

/**
 * Course → Manage Tutes (spec §3): "Tutes of {course}", debounced search,
 * Add Tute, server pagination and View / Edit / Delete per capability.
 * Archived tutes are hidden unless "Show archived" is on. Hidden controls are
 * UX only; every request is re-authorized by the backend.
 */
export function TuteListPage({ courseId, routes }: { courseId: string; routes: TuteRoutes }) {
  const { can } = useTuteCapabilities();
  const courseQuery = useCourse(courseId);
  const courseName = courseQuery.data?.name ?? null;
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [includeArchived, setIncludeArchived] = useState(false);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState<number>(10);
  const [formOpen, setFormOpen] = useState(false);
  const [formKey, setFormKey] = useState(0);
  const [editing, setEditing] = useState<TuteView | null>(null);
  const [deleting, setDeleting] = useState<TuteView | null>(null);
  const toast = useAdminToast();
  const remove = useDeleteTute(courseId);
  const query = useCourseTutes(courseId, { search, includeArchived, page, size });

  useEffect(() => {
    const handle = setTimeout(() => {
      setSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(handle);
  }, [searchInput]);

  const openForm = (tute: TuteView | null) => {
    setEditing(tute);
    setFormKey((key) => key + 1);
    setFormOpen(true);
  };

  const confirmDelete = () => {
    if (!deleting) return;
    const target = deleting;
    remove.mutate(target.id, {
      onSuccess: (result) => {
        setDeleting(null);
        toast.show({
          kind: "success",
          text:
            result.result === "ARCHIVED"
              ? `“${target.name}” has assignment history, so it was archived instead of deleted.`
              : `“${target.name}” deleted.`,
        });
      },
    });
  };

  const canCreate = can(TUTE_CAP.CREATE);
  const canEdit = can(TUTE_CAP.UPDATE);
  const canDelete = can(TUTE_CAP.DELETE);
  const canView = can(TUTE_CAP.VIEW_TUTE);
  const currency = query.data?.content.find((tute) => tute.currency)?.currency ?? null;

  return (
    <div className="flex min-w-0 flex-col gap-5">
      <div className="flex items-start gap-3">
        <span className="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <FileText className="size-5" aria-hidden="true" />
        </span>
        <div className="flex min-w-0 flex-col gap-1">
          <h1 className="text-xl font-semibold break-words text-foreground">
            {courseName ? `Tutes of ${courseName}` : "Tutes"}
          </h1>
          <Breadcrumbs
            items={[
              { label: "Courses", href: routes.courses },
              { label: courseName ?? "Course" },
            ]}
          />
        </div>
      </div>

      <div className="flex min-w-0 flex-col gap-4 rounded-xl border border-border bg-card p-3 shadow-sm sm:p-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <div className="relative w-full sm:w-72">
              <label htmlFor="tutes-search" className="sr-only">
                Search tutes
              </label>
              <Input
                id="tutes-search"
                type="search"
                placeholder="Search"
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                className="pr-8"
                data-testid="tutes-search"
              />
              <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            </div>
            <div className="flex items-center gap-2">
              <Switch
                id="tutes-show-archived"
                checked={includeArchived}
                onCheckedChange={(next) => {
                  setIncludeArchived(next);
                  setPage(0);
                }}
                data-testid="tutes-show-archived"
              />
              <Label htmlFor="tutes-show-archived" className="font-normal">
                Show archived
              </Label>
            </div>
          </div>
          {canCreate ? (
            <Button type="button" onClick={() => openForm(null)} data-testid="tutes-add">
              <Plus aria-hidden="true" />
              Add Tute
            </Button>
          ) : null}
        </div>

        {query.status === "pending" ? (
          <TableSkeleton label="Loading tutes…" />
        ) : (
          <QueryStateBoundary
            query={query}
            loadingLabel="Loading tutes…"
            loginPath="/login"
            permissionDenied={{ dashboardHref: routes.dashboard }}
          >
            {(data) => (
              <div className={cn("flex min-w-0 flex-col gap-3", query.isFetching && "opacity-70")} aria-busy={query.isFetching || undefined}>
                <div className="overflow-x-auto rounded-lg border border-border" data-testid="tutes-table-scroll">
                  <table className="w-full min-w-[720px] text-sm" data-testid="tutes-table">
                    <caption className="sr-only">Tutes of {courseName ?? "this course"}</caption>
                    <thead className="bg-muted/50 text-left">
                      <tr>
                        <th scope="col" className="px-3 py-2 font-medium">Name</th>
                        <th scope="col" className="px-3 py-2 font-medium">Quantity</th>
                        <th scope="col" className="px-3 py-2 font-medium">Payment Type</th>
                        <th scope="col" className="px-3 py-2 font-medium">Sold Through</th>
                        <th scope="col" className="px-3 py-2 text-right font-medium">
                          Price{currency ? ` (${currency})` : ""}
                        </th>
                        <th scope="col" className="px-3 py-2 text-right font-medium">Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {data.content.length === 0 ? (
                        <tr>
                          <td colSpan={6} className="px-3 py-8 text-center text-muted-foreground" data-testid="tutes-empty">
                            {search ? `No tutes match “${search}”.` : "No data available"}
                          </td>
                        </tr>
                      ) : (
                        data.content.map((tute) => (
                          <tr key={tute.id} className="border-t border-border align-top" data-testid="tute-row">
                            <td className="px-3 py-2">
                              <div className="flex flex-wrap items-center gap-2">
                                <span className="font-medium text-foreground">{tute.name}</span>
                                {tute.status === "ARCHIVED" ? <Badge variant="outline">Archived</Badge> : null}
                              </div>
                            </td>
                            <td className="px-3 py-2">
                              <div className="flex flex-col">
                                <span className="tabular-nums">{tute.quantity.toLocaleString("en-US")}</span>
                                <span
                                  className={cn(
                                    "flex items-center gap-1 text-xs",
                                    tute.available <= 0 ? "font-medium text-warning" : "text-muted-foreground"
                                  )}
                                  data-testid="tute-available"
                                >
                                  {tute.available <= 0 ? <AlertTriangle className="size-3.5" aria-hidden="true" /> : null}
                                  {tute.available.toLocaleString("en-US")} available
                                  {tute.available <= 0 ? <span className="sr-only"> (out of stock)</span> : null}
                                </span>
                              </div>
                            </td>
                            <td className="px-3 py-2">{TUTE_PAYMENT_TYPE_LABELS[tute.paymentType] ?? tute.paymentType}</td>
                            <td className="px-3 py-2">{tute.paidLessonTitle ?? "—"}</td>
                            <td className="px-3 py-2 text-right tabular-nums">
                              {tute.paymentType === "PAID" ? formatTutePrice(tute.price, tute.currency) : "—"}
                            </td>
                            <td className="px-3 py-2">
                              <div className="flex justify-end gap-1">
                                {canView ? (
                                  <Link
                                    href={routes.tute(courseId, tute.id)}
                                    className={buttonVariants({ variant: "ghost", size: "icon-sm" })}
                                    aria-label={`View ${tute.name}`}
                                    data-testid="tute-action-view"
                                  >
                                    <Eye aria-hidden="true" />
                                  </Link>
                                ) : null}
                                {canEdit && tute.status === "ACTIVE" ? (
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon-sm"
                                    aria-label={`Edit ${tute.name}`}
                                    onClick={() => openForm(tute)}
                                    data-testid="tute-action-edit"
                                  >
                                    <Pencil aria-hidden="true" />
                                  </Button>
                                ) : null}
                                {canDelete && tute.status === "ACTIVE" ? (
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="icon-sm"
                                    aria-label={`Delete ${tute.name}`}
                                    onClick={() => {
                                      remove.reset();
                                      setDeleting(tute);
                                    }}
                                    data-testid="tute-action-delete"
                                    className="text-destructive"
                                  >
                                    <Trash2 aria-hidden="true" />
                                  </Button>
                                ) : null}
                              </div>
                            </td>
                          </tr>
                        ))
                      )}
                    </tbody>
                  </table>
                </div>
                <HubPagination
                  idPrefix="tutes"
                  page={data.page}
                  size={size}
                  totalElements={data.totalElements}
                  sizes={TUTE_PAGE_SIZES}
                  onPageChange={setPage}
                  onSizeChange={(next) => {
                    setSize(next);
                    setPage(0);
                  }}
                />
              </div>
            )}
          </QueryStateBoundary>
        )}
      </div>

      <TuteFormDialog
        key={formKey}
        courseId={courseId}
        currency={editing?.currency ?? currency}
        open={formOpen}
        onOpenChange={setFormOpen}
        tute={editing}
        onReload={async () => {
          const result = await query.refetch();
          return result.data?.content.find((tute) => tute.id === editing?.id) ?? null;
        }}
        onSaved={(saved, mode) => {
          setFormOpen(false);
          toast.show({ kind: "success", text: mode === "create" ? `“${saved.name}” created.` : `“${saved.name}” saved.` });
        }}
      />
      <ConfirmDeleteDialog
        open={deleting !== null}
        onOpenChange={(next) => {
          if (!next) setDeleting(null);
        }}
        title={deleting ? `Delete “${deleting.name}”?` : "Delete tute?"}
        description="A tute that has never been assigned is deleted permanently. A tute with any assignment or dispatch history is archived instead: it is hidden, can't be assigned again, and its history is kept."
        confirmLabel="Delete tute"
        pending={remove.isPending}
        error={remove.isError ? mutationErrorMessage(remove.error) : null}
        onConfirm={confirmDelete}
      />
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}
