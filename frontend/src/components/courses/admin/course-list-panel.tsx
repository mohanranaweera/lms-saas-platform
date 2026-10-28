"use client";

import { useEffect, useState } from "react";
import { BookOpen, HelpCircle, Plus, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { Tabs } from "@/components/ui/tabs";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { AdminToast, useAdminToast, type AdminToastMessage } from "@/components/courses/admin/admin-toast";
import { CourseCreateDialog } from "@/components/courses/admin/course-create-dialog";
import {
  CourseRowActions,
  CourseStatusBadge,
  REGISTRATION_ARCHIVED_HELP,
  REGISTRATION_HELP,
  RegistrationSwitch,
  publishErrorMessage,
} from "@/components/courses/admin/course-row-actions";
import { mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import {
  ReorderableTable,
  TableSkeleton,
  moveItem,
  type ReorderableColumn,
} from "@/components/courses/admin/reorderable-table";
import {
  PRICING_MODEL_LABELS,
  formatAmount,
  useKlassCourses,
  useCourseTags,
  useReorderCourses,
  useSetKlassCoursePublished,
  type KlassCourse,
} from "@/lib/api/klass-admin";
import { shortId } from "@/lib/format";
import { cn } from "@/lib/utils";

const PAGE_SIZE = 100;
type TabValue = "active" | "archived";

function primaryTeacherName(course: KlassCourse): string {
  const teachers = course.teachers ?? [];
  const primary = teachers.find((teacher) => teacher.primary) ?? teachers[0];
  if (primary) return primary.name;
  return shortId(course.teacherId, "Teacher");
}

/**
 * Tenant Admin "Courses" screen (CM-A01, EP-C01–C09): ACTIVE/ARCHIVED tabs,
 * debounced server search, ADD COURSE modal, persisted drag/keyboard
 * ordering, registration switch and row actions. QUICK CREATE, "Today's
 * courses first", people and overflow controls are intentionally not
 * rendered (D20).
 */
export function CourseListPanel() {
  const [tab, setTab] = useState<TabValue>("active");
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [createOpen, setCreateOpen] = useState(false);
  const [createKey, setCreateKey] = useState(0);
  const [tagId, setTagId] = useState<string | null>(null);
  const toast = useAdminToast();
  const publishNew = useSetKlassCoursePublished();

  const publishCreated = (course: KlassCourse) => {
    if (publishNew.isPending) return;
    publishNew.mutate(
      { courseId: course.id, publish: true },
      {
        onSuccess: () =>
          toast.show({
            kind: "success",
            text: `“${course.name}” published. Students can now see it in Available Courses.`,
          }),
        onError: (error) =>
          toast.show({ kind: "error", text: `Could not publish “${course.name}”: ${publishErrorMessage(error)}` }),
      }
    );
  };

  useEffect(() => {
    const handle = setTimeout(() => {
      setSearch(searchInput.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(handle);
  }, [searchInput]);

  const openCreate = () => {
    setCreateKey((current) => current + 1);
    setCreateOpen(true);
  };

  const tabContent = (value: TabValue) => (
    <CourseTable
      archived={value === "archived"}
      search={search}
      page={page}
      tagId={tagId}
      onTagIdChange={(next) => { setTagId(next); setPage(0); }}
      onPageChange={setPage}
      onClearSearch={() => setSearchInput("")}
      onAddCourse={openCreate}
      onToast={toast.show}
    />
  );

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-center gap-3">
        <span className="flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <BookOpen className="size-5" aria-hidden="true" />
        </span>
        <h1 className="text-xl font-semibold text-foreground">Courses</h1>
      </div>

      <div className="flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
        <div className="flex w-full flex-col gap-1.5 sm:max-w-sm">
          <label htmlFor="courses-search" className="sr-only">
            Search courses
          </label>
          <div className="relative">
            <Input
              id="courses-search"
              type="search"
              placeholder="Search"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              className="pr-8"
              data-testid="courses-search"
            />
            <Search aria-hidden="true" className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
          </div>
        </div>
        <Button type="button" onClick={openCreate} data-testid="courses-add-course">
          <Plus aria-hidden="true" />
          Add course
        </Button>
      </div>

      <Tabs
        aria-label="Course status"
        value={tab}
        onValueChange={(value) => {
          setTab(value as TabValue);
          setPage(0);
        }}
        items={[
          { value: "active", label: "Active", content: tabContent("active") },
          { value: "archived", label: "Archived", content: tabContent("archived") },
        ]}
      />

      <CourseCreateDialog
        key={createKey}
        open={createOpen}
        onOpenChange={setCreateOpen}
        onCreated={(course, outcome) => {
          setCreateOpen(false);
          toast.show(
            outcome.coverUploaded === false
              ? { kind: "error", text: `“${course.name}” was created without a cover image. Add one later from the course.` }
              : {
                  kind: "success",
                  text: "Course created as a draft. Publish it to show it to students.",
                  action: {
                    label: "Publish now",
                    onClick: () => publishCreated(course),
                    testId: "course-created-publish-now",
                  },
                }
          );
        }}
      />
      <AdminToast message={toast.message} onDismiss={toast.dismiss} />
    </div>
  );
}

function CourseTable({
  archived,
  search,
  page,
  tagId,
  onTagIdChange,
  onPageChange,
  onClearSearch,
  onAddCourse,
  onToast,
}: {
  archived: boolean;
  search: string;
  page: number;
  tagId: string | null;
  onTagIdChange: (tagId: string | null) => void;
  onPageChange: (page: number) => void;
  onClearSearch: () => void;
  onAddCourse: () => void;
  onToast: (message: AdminToastMessage) => void;
}) {
  const query = useKlassCourses({ archived, search, page, size: PAGE_SIZE, tagId });
  const tagsQuery = useCourseTags();
  const reorder = useReorderCourses();
  const [optimisticRows, setOptimisticRows] = useState<KlassCourse[] | null>(null);
  const [showRegistrationHelp, setShowRegistrationHelp] = useState(false);
  const registrationHelpId = archived ? "registration-help-panel-archived" : "registration-help-panel-active";

  if (query.status === "pending") {
    return <TableSkeleton label="Loading courses…" />;
  }

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading courses…"
      loginPath="/login"
      permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
    >
      {(data) => {
        const rows = optimisticRows ?? data.content;
        if (data.content.length === 0) {
          return (
            <div
              className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border px-6 py-12 text-center"
              data-testid="courses-empty"
            >
              <p className="text-sm font-medium text-foreground">
                {search
                  ? `No courses match “${search}”`
                  : archived
                    ? "No archived courses"
                    : "No active courses yet"}
              </p>
              <p className="max-w-md text-sm text-muted-foreground">
                {search
                  ? "Try another name or teacher, or clear the search."
                  : archived
                    ? "Courses you archive appear here and can be restored at any time."
                    : "Add your first course to start building modules and sessions."}
              </p>
              {search ? (
                <Button type="button" size="sm" variant="outline" onClick={onClearSearch}>
                  Clear search
                </Button>
              ) : !archived ? (
                <Button type="button" size="sm" onClick={onAddCourse}>
                  <Plus aria-hidden="true" />
                  Add course
                </Button>
              ) : null}
            </div>
          );
        }

        const reorderEnabled = !archived && !search && !tagId && data.totalPages <= 1;
        const headerCurrency = rows.find((course) => course.priceDisplay)?.priceDisplay?.currency ?? "LKR";

        const handleMove = (from: number, to: number) => {
          const next = moveItem(rows, from, to);
          if (next === rows) return;
          setOptimisticRows(next);
          reorder.mutate(
            next.map((course) => course.id),
            {
              onSuccess: () => onToast({ kind: "success", text: "Course order saved." }),
              onError: (error) =>
                onToast({ kind: "error", text: `Course order was not saved: ${mutationErrorMessage(error)}` }),
              onSettled: () => setOptimisticRows(null),
            }
          );
        };

        const columns: ReorderableColumn<KlassCourse>[] = [
          {
            key: "name",
            header: "Name",
            cell: (course) => <span className="font-medium text-foreground">{course.name}</span>,
            hideOnCard: true,
          },
          {
            key: "tags",
            header: "Tags",
            cell: (course) => course.tags.length ? <div className="flex flex-wrap gap-1">{course.tags.map((tag) => <Badge key={tag.id} variant="outline">{tag.name}</Badge>)}</div> : <span className="text-muted-foreground">—</span>,
          },
          {
            key: "status",
            header: "Status",
            cell: (course) => <CourseStatusBadge status={course.status} />,
          },
          { key: "teacher", header: "Teacher", cell: (course) => primaryTeacherName(course) },
          {
            key: "paymentType",
            header: "Payment Type",
            cell: (course) => PRICING_MODEL_LABELS[course.pricingModel] ?? course.pricingModel,
          },
          {
            key: "price",
            header: `Price (${headerCurrency})`,
            headerClassName: "text-right",
            className: "text-right tabular-nums",
            cell: (course) => {
              const amount = formatAmount(course.priceDisplay?.amount);
              if (!amount || !course.priceDisplay) return "N/A";
              const suffix =
                course.priceDisplay.currency !== headerCurrency ? ` ${course.priceDisplay.currency}` : "";
              const period = course.priceDisplay.period ? ` / ${course.priceDisplay.period.toLowerCase()}` : "";
              return (
                <span data-testid="course-price">
                  {amount}
                  {suffix}
                  {period ? <span className="sr-only">{period}</span> : null}
                </span>
              );
            },
          },
          {
            key: "registration",
            header: "Enabled for Registration",
            cell: (course, _index, layout) => <RegistrationSwitch course={course} onToast={onToast} layout={layout} />,
          },
        ];

        const refreshing = query.isFetching && !reorder.isPending;

        return (
          <div
            className={cn("flex flex-col gap-3 transition-opacity", refreshing && "opacity-60")}
            aria-busy={refreshing || undefined}
            data-testid="courses-list"
          >
            <div className="flex flex-col gap-3">
              <label htmlFor={`course-tag-filter-${archived ? "archived" : "active"}`} className="text-sm font-medium text-foreground">Filter by tag</label>
              <select
                id={`course-tag-filter-${archived ? "archived" : "active"}`}
                value={tagId ?? ""}
                onChange={(event) => onTagIdChange(event.target.value || null)}
                disabled={tagsQuery.isPending || tagsQuery.isError}
                className="h-9 w-full max-w-xs rounded-lg border border-input bg-background px-2.5 text-sm"
              >
                <option value="">All tags</option>
                {(tagsQuery.data ?? []).map((tag) => <option key={tag.id} value={tag.id}>{tag.name}</option>)}
              </select>
              {tagsQuery.isError ? <p className="text-xs text-destructive" role="alert">Tags could not be loaded. Clear filter or retry page.</p> : null}
              <button
                type="button"
                onClick={() => setShowRegistrationHelp((current) => !current)}
                aria-expanded={showRegistrationHelp}
                aria-controls={registrationHelpId}
                className="inline-flex w-fit items-center gap-1 rounded text-xs text-muted-foreground outline-none hover:text-foreground focus-visible:ring-2 focus-visible:ring-ring/50"
                data-testid="courses-registration-help-toggle"
              >
                <HelpCircle className="size-3.5" aria-hidden="true" />
                What does &ldquo;Enabled for Registration&rdquo; do?
              </button>
              <p
                id={registrationHelpId}
                hidden={!showRegistrationHelp}
                className="rounded-lg border border-border bg-muted/30 p-2 text-xs text-muted-foreground"
              >
                {REGISTRATION_HELP}
              </p>
              {archived ? (
                <p className="text-xs text-muted-foreground" data-testid="courses-registration-archived-note">
                  {REGISTRATION_ARCHIVED_HELP}
                </p>
              ) : null}
            </div>
            {!reorderEnabled && !archived ? (
              <p className="text-xs text-muted-foreground">
                {search ? "Clear the search to reorder courses." : "Reordering is available when all courses fit on one page."}
              </p>
            ) : null}
            <ReorderableTable
              rows={rows}
              rowKey={(course) => course.id}
              rowLabel={(course) => course.name}
              columns={columns}
              caption={archived ? "Archived courses" : "Active courses"}
              reorderEnabled={reorderEnabled}
              reorderPending={reorder.isPending}
              onMove={handleMove}
              indexOffset={data.page * data.size}
              rowTestId="course-row"
              actions={(course) => <CourseRowActions course={course} onToast={onToast} />}
            />
            <div className="flex flex-wrap items-center justify-end gap-3 text-sm text-muted-foreground">
              <span data-testid="courses-range">
                {data.totalElements === 0
                  ? "0 of 0"
                  : `${data.page * data.size + 1}–${data.page * data.size + data.content.length} of ${data.totalElements}`}
              </span>
              {data.totalPages > 1 ? (
                <div className="flex gap-1">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={page === 0}
                    onClick={() => onPageChange(page - 1)}
                    aria-label="Previous page"
                  >
                    Previous
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={page >= data.totalPages - 1}
                    onClick={() => onPageChange(page + 1)}
                    aria-label="Next page"
                  >
                    Next
                  </Button>
                </div>
              ) : null}
            </div>
          </div>
        );
      }}
    </QueryStateBoundary>
  );
}
