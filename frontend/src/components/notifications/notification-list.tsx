"use client";

import { useEffect, useRef, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import {
  useMarkNotificationRead,
  useNotifications,
  type NotificationResponse,
} from "@/lib/api/notifications";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";

const PAGE_SIZE = 20;

/**
 * Skeleton rows shown while `useNotifications` is `pending` (mirrors
 * `AttendanceReportsTableSkeleton`'s established precedent — plan calls for
 * "skeleton rows, not a full-page spinner"). This is a consumer-style
 * surface (Student/Teacher), so this is a single card-list skeleton, not a
 * desktop-table/mobile-card split like the admin variant.
 */
function NotificationListSkeleton() {
  return (
    <ul aria-hidden="true" className="flex flex-col gap-2">
      {Array.from({ length: 5 }).map((_, index) => (
        <li key={index} className="flex flex-col gap-2 rounded-lg border border-border p-4">
          <Skeleton className="h-4 w-1/3" />
          <Skeleton className="h-3 w-full" />
          <Skeleton className="h-3 w-2/3" />
        </li>
      ))}
    </ul>
  );
}

interface NotificationListProps {
  /** Page heading, rendered as this component's own `<h1>` — kept here (not the page) so header copy and list body stay visually/structurally together. */
  heading: string;
  description: string;
  /** Contextual empty-state copy (per `.claude/rules/ui-ux.md` §3) — Student and Teacher need different wording since the underlying "why is this empty" reason differs. */
  emptyState: { title: string; description: string };
  /** Where `PermissionDeniedState`'s "back to dashboard" link points — differs per role (`/student/dashboard` vs `/teacher/dashboard`). */
  dashboardHref: string;
}

/**
 * Shared Notification Center list for both the Student
 * (`app/(student)/student/notifications/page.tsx`) and Teacher
 * (`app/(teacher)/teacher/notifications/page.tsx`) routes — `GET
 * /api/v1/notifications` is already self-scoped server-side to the caller's
 * own `(tenant_id, recipient_user_id)`, so one component/query serves both
 * roles unchanged (no role param, per `.claude/rules/ui-ux.md` §1: never
 * fetch a broader dataset and filter client-side).
 */
export function NotificationList({ heading, description, emptyState, dashboardHref }: NotificationListProps) {
  const [page, setPage] = useState(0);
  const query = useNotifications({ page, size: PAGE_SIZE });
  const markReadMutation = useMarkNotificationRead();
  const [pendingId, setPendingId] = useState<string | null>(null);
  const [mutationError, setMutationError] = useState("");

  // Background refetch (poll / page-turn) case only — `query.data` is
  // already populated (from `keepPreviousData`) so `QueryStateBoundary`'s
  // own loading branch is never reached here; mirrors
  // `app/(student)/student/attendance/page.tsx`'s `isRefetching` derivation.
  const isRefetching = query.isFetching && query.data !== undefined;

  // Row `<li>` elements, keyed by notification id, so a successful mark-read
  // can move focus onto the row itself once its "Mark as read" button
  // unmounts (the row's `isUnread` flips false) — otherwise focus silently
  // falls back to `<body>` and the user loses their place in the list.
  const rowRefs = useRef<Map<string, HTMLLIElement>>(new Map());

  function registerRowRef(id: string, element: HTMLLIElement | null) {
    if (element) {
      rowRefs.current.set(id, element);
    } else {
      rowRefs.current.delete(id);
    }
  }

  // "New notification arrived" announcement for the 30s background poll
  // (and any other background refetch, e.g. after a mark-read
  // invalidation): only announce when this page's unread count actually
  // *increased* since the last fetch — a routine poll where nothing changed
  // (or a mark-read, which only ever decreases this page's unread count)
  // must stay silent. Reset the baseline on every page change so turning
  // pages never itself triggers an announcement.
  const [pollAnnouncement, setPollAnnouncement] = useState("");
  const previousUnreadCountRef = useRef<number | null>(null);
  const previousPageRef = useRef(page);

  useEffect(() => {
    if (!query.data) {
      return;
    }
    const pageChanged = previousPageRef.current !== page;
    previousPageRef.current = page;

    const unreadCount = query.data.content.filter((notification) => notification.readAt === null).length;
    const previousUnreadCount = previousUnreadCountRef.current;
    previousUnreadCountRef.current = unreadCount;

    if (!pageChanged && previousUnreadCount !== null && unreadCount > previousUnreadCount) {
      setPollAnnouncement("New notification arrived.");
    } else {
      setPollAnnouncement("");
    }
  }, [query.data, page]);

  function handleMarkRead(id: string) {
    setPendingId(id);
    setMutationError("");
    markReadMutation.mutate(id, {
      onSuccess: () => {
        setPendingId(null);
        // The row's "Mark as read" button is about to unmount now that
        // `isUnread` flips false — move focus to the row itself so
        // keyboard/screen-reader users don't lose their place.
        rowRefs.current.get(id)?.focus();
      },
      onError: (error) => {
        setPendingId(null);
        setMutationError(
          isApiClientError(error)
            ? error.message
            : "Could not mark this notification as read. Please try again."
        );
      },
    });
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">{heading}</h1>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>

      {query.status === "pending" ? (
        <>
          <p role="status" aria-live="polite" aria-busy="true" className="sr-only">
            Loading notifications…
          </p>
          <NotificationListSkeleton />
        </>
      ) : (
        <QueryStateBoundary
          query={query}
          loginPath="/login"
          permissionDenied={{ dashboardHref }}
          isEmpty={(data) => data.content.length === 0 && page === 0}
          emptyState={emptyState}
        >
          {(data) => (
            <div className="flex flex-col gap-3" aria-busy={isRefetching}>
              <LiveRegion message={pollAnnouncement} />

              {mutationError ? (
                <Alert variant="destructive">
                  <AlertDescription>{mutationError}</AlertDescription>
                </Alert>
              ) : null}

              {data.content.length === 0 ? (
                <EmptyState
                  title="No more results"
                  description="There are no notifications on this page. Go back to an earlier page."
                />
              ) : (
                <ul className={`flex flex-col gap-2 ${isRefetching ? "opacity-60" : ""}`}>
                  {data.content.map((notification) => (
                    <NotificationRow
                      key={notification.id}
                      notification={notification}
                      isMarkingRead={pendingId === notification.id}
                      onMarkRead={() => handleMarkRead(notification.id)}
                      registerRowRef={registerRowRef}
                    />
                  ))}
                </ul>
              )}

              <div className="flex items-center justify-between">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                  disabled={page === 0 || query.isFetching}
                >
                  Previous
                </Button>
                <span className="text-xs text-muted-foreground">
                  Page {data.page + 1} of {Math.max(data.totalPages, 1)}
                </span>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setPage((current) => current + 1)}
                  disabled={data.page + 1 >= data.totalPages || query.isFetching}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </QueryStateBoundary>
      )}
    </div>
  );
}

function NotificationRow({
  notification,
  isMarkingRead,
  onMarkRead,
  registerRowRef,
}: {
  notification: NotificationResponse;
  isMarkingRead: boolean;
  onMarkRead: () => void;
  /** Registers/unregisters this row's `<li>` so the parent can move focus onto it after a successful mark-read (see `NotificationList`'s `rowRefs`). */
  registerRowRef: (id: string, element: HTMLLIElement | null) => void;
}) {
  const isUnread = notification.readAt === null;

  return (
    <li
      ref={(element) => registerRowRef(notification.id, element)}
      // Focusable (but not tab-stoppable) so `rowRefs.current.get(id)?.focus()`
      // has somewhere to land once this row's "Mark as read" button unmounts.
      // Plain `focus:` (not `focus-visible:`) so the ring reliably shows for
      // this specific programmatic-focus case too, not just keyboard nav.
      tabIndex={-1}
      className={`flex flex-col gap-2 rounded-lg border border-border p-4 focus:outline-none focus:ring-2 focus:ring-ring/50 focus:ring-offset-2 sm:flex-row sm:items-start sm:justify-between ${
        isUnread ? "bg-muted/40" : ""
      }`}
    >
      <div className="flex flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <span className={`text-sm text-foreground ${isUnread ? "font-semibold" : "font-medium"}`}>
            {notification.title}
          </span>
          {isUnread ? <Badge variant="secondary">Unread</Badge> : null}
        </div>
        <p className="text-sm text-muted-foreground">{notification.body}</p>
        <span className="text-xs text-muted-foreground">{formatDateTime(notification.createdAt)}</span>
      </div>
      {isUnread ? (
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onMarkRead}
          disabled={isMarkingRead}
          aria-busy={isMarkingRead}
          aria-label={`Mark as read: "${notification.title}"`}
          className="shrink-0 self-start"
        >
          {isMarkingRead ? "Marking as read…" : "Mark as read"}
        </Button>
      ) : null}
    </li>
  );
}
