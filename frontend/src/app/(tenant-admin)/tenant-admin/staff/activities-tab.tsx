"use client";

import { useEffect, useMemo, useState } from "react";
import { ChevronDown, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { DateInput } from "@/components/ui/date-input";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useTenantTimeZone } from "@/lib/api/klass-admin";
import { useStaffActivities, type StaffActivity } from "@/lib/api/staff";
import { zonedDateKey } from "@/lib/datetime";
import { cn } from "@/lib/utils";

const DEBOUNCE_MS = 300;

function useDebounced<T>(value: T, delay: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timeout = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(timeout);
  }, [value, delay]);
  return debounced;
}

/** `YYYY-MM-DD` (local) → ISO instant at the start / end of that local day; empty stays empty. */
function dayBoundary(value: string, end: boolean): string {
  if (!value) return "";
  const date = new Date(`${value}T${end ? "23:59:59.999" : "00:00:00"}`);
  return Number.isNaN(date.getTime()) ? "" : date.toISOString();
}

function formatTime(iso: string, timeZone: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "";
  try {
    return date.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", timeZone });
  } catch {
    return date.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
  }
}

function formatDateHeading(key: string): string {
  const date = new Date(`${key}T00:00:00`);
  if (Number.isNaN(date.getTime())) return key;
  return date.toLocaleDateString("en-GB", { weekday: "long", day: "numeric", month: "long", year: "numeric" });
}

/** "STUDENTS_EXPORT_CSV" -> "Students Export Csv"; other strings unchanged. */
function humanizeCode(text: string): string {
  if (!/^[A-Z][A-Z0-9_]+$/.test(text)) return text;
  return text
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

/** "fromVersion" -> "From version" for the detail labels. */
function humanizeKey(key: string): string {
  const spaced = key.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/_/g, " ").toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

/**
 * Readable text for an audit detail value: scalars as-is (codes humanized), objects as
 * "a: b" pairs joined, lists one item per entry - never raw JSON.
 */
function formatDetailValue(value: unknown): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string") return humanizeCode(value);
  if (typeof value === "number" || typeof value === "boolean") return String(value);
  if (Array.isArray(value)) {
    return value.length === 0 ? "None" : value.map((item) => formatDetailValue(item)).join("; ");
  }
  if (typeof value === "object") {
    return Object.values(value as Record<string, unknown>)
      .map((item) => formatDetailValue(item))
      .join(" — ");
  }
  return String(value);
}

/**
 * Staff → Activities: the tenant audit feed (`GET /v1/staff/activities`,
 * STAFF_ACTIVITY_VIEW), already tenant-scoped and redacted server-side.
 * Server search (`q`) and optional date range; grouped by tenant-zone date;
 * bounded "Load more" pagination.
 */
export function ActivitiesTab() {
  const { timeZone } = useTenantTimeZone();
  const [searchInput, setSearchInput] = useState("");
  const q = useDebounced(searchInput.trim(), DEBOUNCE_MS);
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");
  const rangeInvalid = Boolean(fromDate && toDate && fromDate > toDate);

  const params = useMemo(
    () => ({
      q,
      from: rangeInvalid ? "" : dayBoundary(fromDate, false),
      to: rangeInvalid ? "" : dayBoundary(toDate, true),
    }),
    [q, fromDate, toDate, rangeInvalid]
  );
  const query = useStaffActivities(params);

  const boundaryQuery = {
    status: query.status,
    data: query.data,
    error: query.error,
    refetch: () => query.refetch(),
  };
  const filtered = Boolean(q || fromDate || toDate);

  return (
    <section aria-labelledby="staff-activities-heading" className="flex flex-col gap-4 rounded-xl border border-border bg-card p-4">
      <h2 id="staff-activities-heading" className="sr-only">
        Activities
      </h2>
      <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
        <div className="flex w-full flex-col gap-1.5 lg:max-w-sm">
          <Label htmlFor="staff-activity-search">Search activities</Label>
          <div className="relative">
            <Input
              id="staff-activity-search"
              type="search"
              placeholder="Action, person or reference"
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              className="pr-8"
            />
            <Search
              aria-hidden="true"
              className="pointer-events-none absolute top-1/2 right-2.5 size-4 -translate-y-1/2 text-muted-foreground"
            />
          </div>
        </div>
        <div className="flex flex-col gap-1">
          <DateInput
            idPrefix="staff-activity-range"
            fromValue={fromDate}
            toValue={toDate}
            onFromChange={setFromDate}
            onToChange={setToDate}
            invalid={rangeInvalid}
            errorId="staff-activity-range-error"
          />
          {rangeInvalid ? (
            <p id="staff-activity-range-error" role="alert" className="text-xs text-destructive">
              The start date must be on or before the end date.
            </p>
          ) : null}
        </div>
      </div>

      {query.status === "pending" ? (
        <div role="status" aria-label="Loading activities…" className="flex flex-col gap-3" data-testid="activities-skeleton">
          <span className="sr-only">Loading activities…</span>
          <Skeleton className="h-5 w-48" />
          {Array.from({ length: 4 }, (_, index) => (
            <Skeleton key={index} className="h-14 w-full" />
          ))}
        </div>
      ) : (
        <QueryStateBoundary
          query={boundaryQuery}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.pages.every((page) => page.content.length === 0)}
          emptyState={
            filtered
              ? {
                  title: "No activities match these filters",
                  description: "Try a different search or date range.",
                  action: {
                    label: "Clear filters",
                    onClick: () => {
                      setSearchInput("");
                      setFromDate("");
                      setToDate("");
                    },
                  },
                }
              : { title: "No activity yet", description: "Actions taken in this institute will appear here." }
          }
        >
          {(data) => {
            const entries = data.pages.flatMap((page) => page.content);
            const total = data.pages[0]?.totalElements ?? entries.length;
            const groups: Array<{ key: string; items: StaffActivity[] }> = [];
            for (const entry of entries) {
              const key = zonedDateKey(entry.occurredAt, timeZone) ?? "Unknown date";
              const last = groups[groups.length - 1];
              if (last && last.key === key) last.items.push(entry);
              else groups.push({ key, items: [entry] });
            }
            return (
              <div className="flex flex-col gap-5" aria-busy={query.isFetching}>
                {groups.map((group) => (
                  <section key={group.key} aria-labelledby={`activity-day-${group.key}`} className="flex flex-col gap-2">
                    <h3 id={`activity-day-${group.key}`} className="text-sm font-semibold text-foreground">
                      {formatDateHeading(group.key)}
                    </h3>
                    <ul className="flex flex-col divide-y divide-border rounded-lg border border-border">
                      {group.items.map((entry) => (
                        <ActivityItem key={entry.id} entry={entry} timeZone={timeZone} />
                      ))}
                    </ul>
                  </section>
                ))}
                <div className="flex flex-col items-center gap-2">
                  <p className="text-xs text-muted-foreground" aria-live="polite">
                    Showing {entries.length} of {total}
                  </p>
                  {query.hasNextPage ? (
                    <Button
                      type="button"
                      variant="outline"
                      disabled={query.isFetchingNextPage}
                      onClick={() => void query.fetchNextPage()}
                    >
                      {query.isFetchingNextPage ? "Loading…" : "Load more"}
                    </Button>
                  ) : null}
                  {query.isFetchNextPageError ? (
                    <p role="alert" className="text-sm text-destructive">
                      More activities couldn&apos;t be loaded. Please try again.
                    </p>
                  ) : null}
                </div>
              </div>
            );
          }}
        </QueryStateBoundary>
      )}
    </section>
  );
}

function ActivityItem({ entry, timeZone }: { entry: StaffActivity; timeZone: string }) {
  const [open, setOpen] = useState(false);
  const details = entry.details ? Object.entries(entry.details) : [];
  const detailsId = `activity-details-${entry.id}`;
  return (
    <li className="flex flex-col gap-1 px-3 py-2.5" data-testid="activity-item">
      <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between sm:gap-4">
        <div className="min-w-0">
          <p className="font-medium text-foreground">{entry.actionLabel || entry.action}</p>
          <p className="text-sm text-muted-foreground">
            {entry.actorName}
            {entry.actorRole ? ` · ${entry.actorRole}` : ""}
            {entry.targetEntity ? (
              <>
                {" "}
                → {entry.targetEntity}
                {entry.targetReference ? <span className="break-all"> {entry.targetReference}</span> : null}
              </>
            ) : null}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <time dateTime={entry.occurredAt} className="text-xs tabular-nums text-muted-foreground">
            {formatTime(entry.occurredAt, timeZone)}
          </time>
          {details.length > 0 ? (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-expanded={open}
              aria-controls={detailsId}
              onClick={() => setOpen((current) => !current)}
            >
              Details
              <ChevronDown aria-hidden="true" className={cn("transition-transform", open && "rotate-180")} />
            </Button>
          ) : null}
        </div>
      </div>
      {details.length > 0 ? (
        <dl id={detailsId} hidden={!open} className="grid grid-cols-1 gap-x-4 gap-y-1 rounded-md bg-muted/40 p-2 text-xs sm:grid-cols-[max-content_1fr]">
          {details.map(([key, value]) => (
            <div key={key} className="contents">
              <dt className="font-medium text-muted-foreground">{humanizeKey(key)}</dt>
              <dd className="break-all text-foreground">{formatDetailValue(value)}</dd>
            </div>
          ))}
        </dl>
      ) : null}
    </li>
  );
}
