"use client";

import Link from "next/link";
import { Bell, Check, Clock3 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLinkItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import {
  useMarkNotificationRead,
  useNotifications,
  useUnreadNotificationCount,
  type NotificationResponse,
} from "@/lib/api/notifications";
import { formatDateTime } from "@/lib/format";
import { isApiClientError } from "@/lib/api/error";

const QUICK_VIEW_SIZE = 6;

function NotificationRow({ notification }: { notification: NotificationResponse }) {
  const markRead = useMarkNotificationRead();
  const unread = notification.readAt === null;

  return (
    <li className={`flex gap-3 rounded-lg p-3 ${unread ? "bg-primary/10" : ""}`}>
      <span className={`mt-0.5 flex size-9 shrink-0 items-center justify-center rounded-lg ${unread ? "bg-primary text-primary-foreground" : "bg-muted text-muted-foreground"}`}>
        <Bell className="size-4" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <p className={`line-clamp-2 text-sm ${unread ? "font-semibold text-foreground" : "font-medium text-muted-foreground"}`}>
          {notification.title}
        </p>
        <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">{notification.body}</p>
        <p className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
          <Clock3 className="size-3" aria-hidden="true" />
          {formatDateTime(notification.createdAt)}
        </p>
      </div>
      {unread ? (
        <Button
          type="button"
          size="icon-xs"
          variant="ghost"
          aria-label={`Mark as read: ${notification.title}`}
          disabled={markRead.isPending}
          onClick={() => markRead.mutate(notification.id)}
        >
          <Check aria-hidden="true" />
        </Button>
      ) : null}
    </li>
  );
}

export function StudentNotificationQuickView() {
  const notifications = useNotifications({ page: 0, size: QUICK_VIEW_SIZE });
  const unreadCount = useUnreadNotificationCount();
  const count = unreadCount.data?.unreadCount ?? 0;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button type="button" variant="ghost" size="icon-sm" aria-label={count ? `${count} unread notifications` : "Notifications"} />}
      >
        <span className="relative">
          <Bell aria-hidden="true" />
          {count > 0 ? (
            <Badge variant="destructive" className="absolute -top-2 -right-3 min-w-4 px-1 text-[10px] leading-4">
              <span aria-hidden="true">{count > 99 ? "99+" : count}</span>
            </Badge>
          ) : null}
        </span>
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-[min(24rem,calc(100vw-2rem))] p-0" align="end">
        <div className="border-b border-border px-4 py-3">
          <div className="flex items-center gap-2">
            <h2 className="text-base font-semibold text-foreground">Notifications</h2>
            {count > 0 ? <Badge>{count}</Badge> : null}
          </div>
          <p className="mt-1 text-xs text-muted-foreground">{count} unread</p>
        </div>
        <div className="max-h-[min(28rem,60vh)] overflow-y-auto p-2">
          {notifications.isPending ? (
            <ul className="flex flex-col gap-2" aria-label="Loading notifications">
              {Array.from({ length: 4 }, (_, index) => <li key={index} className="rounded-lg border border-border p-3"><Skeleton className="h-4 w-2/3" /><Skeleton className="mt-2 h-3 w-full" /><Skeleton className="mt-2 h-3 w-1/3" /></li>)}
            </ul>
          ) : notifications.isError ? (
            <div role="alert" className="p-4 text-center text-sm text-destructive">
              {isApiClientError(notifications.error) ? notifications.error.message : "Could not load notifications."}
              <Button type="button" variant="outline" size="sm" className="mt-3" onClick={() => notifications.refetch()}>Try again</Button>
            </div>
          ) : notifications.data?.content.length ? (
            <ul className="flex flex-col gap-1" aria-label="Recent notifications">
              {notifications.data.content.map((notification) => <NotificationRow key={notification.id} notification={notification} />)}
            </ul>
          ) : (
            <p className="p-6 text-center text-sm text-muted-foreground">No notifications yet.</p>
          )}
        </div>
        <div className="border-t border-border p-2">
          <DropdownMenuLinkItem render={<Link href="/student/notifications" />} className="justify-center font-semibold text-primary">
            See all notifications →
          </DropdownMenuLinkItem>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
