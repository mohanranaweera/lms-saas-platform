"use client";

import { Badge } from "@/components/ui/badge";
import { useUnreadNotificationCount } from "@/lib/api/notifications";

/**
 * Unread-count badge for the "Notifications" nav item (`student-nav.tsx`,
 * `teacher-nav.tsx`), backed by `GET /api/v1/notifications/unread-count`
 * (self-scoped server-side, no id/tenant param). Renders nothing while the
 * count is loading/unknown or errored, and nothing when the count is `0` —
 * a `0` badge would be visual noise, not information (see plan). This is a
 * decorative nav enhancement, not a page, so it degrades silently on error
 * rather than rendering a dedicated error state — the Notification Center
 * page itself (`notification-list.tsx`) is the source of truth and already
 * has its own loading/empty/error states.
 *
 * `aria-label` carries the full "N unread notifications" context so a
 * screen-reader user doesn't hear a bare, context-free number — the visible
 * text stays the short numeral (`aria-hidden` on that span) per
 * `.claude/rules/ui-ux.md` §4.
 */
export function NotificationsNavBadge() {
  const query = useUnreadNotificationCount();
  const unreadCount = query.data?.unreadCount ?? 0;

  if (query.status !== "success" || unreadCount <= 0) {
    return null;
  }

  const label = `${unreadCount} unread notification${unreadCount === 1 ? "" : "s"}`;

  return (
    <Badge variant="destructive" aria-label={label} className="ml-2">
      <span aria-hidden="true">{unreadCount > 99 ? "99+" : unreadCount}</span>
    </Badge>
  );
}
