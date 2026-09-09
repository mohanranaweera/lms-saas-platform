import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `notification-management`'s MVP-018
 * "Email Notifications" (Notification Center) in-app list/mark-read
 * endpoints (`/api/v1/notifications/**` — see `NotificationController`).
 * Follows `lib/api/attendance.ts`'s conventions exactly (`/v1/...` paths,
 * every call through `useAuth().authorizedFetch("tenant", ...)`, a
 * query-keys factory object, `onSuccess` cache invalidation on mutations).
 *
 * Both `GET /api/v1/notifications` and `PATCH
 * /api/v1/notifications/{id}/read` are self-scoped server-side to the
 * caller's own `(tenant_id, recipient_user_id)` — no id/tenant/user param is
 * ever sent, and there is no `DomainArea`/role gate, so the same hooks serve
 * both the Student and Teacher Notification Center screens unchanged.
 */

/** Mirrors `NotificationResponse` (backend `com.lms.notificationmanagement.web.dto`) field-for-field. `readAt` is `null` until `markRead` succeeds. */
export interface NotificationResponse {
  id: string;
  title: string;
  body: string;
  readAt: string | null;
  createdAt: string;
}

/** `GET /api/v1/notifications`'s query params. Backend default (`@PageableDefault`) is `page=0, size=20, sort=createdAt DESC` — no `sort` param needs to be sent. */
export interface NotificationListParams {
  page?: number;
  size?: number;
}

/** Mirrors `UnreadCountResponse` (backend `com.lms.notificationmanagement.web.dto`) field-for-field. */
export interface UnreadCountResponse {
  unreadCount: number;
}

export const notificationKeys = {
  all: ["notifications"] as const,
  listAll: () => [...notificationKeys.all, "list"] as const,
  list: (params?: NotificationListParams) => [...notificationKeys.listAll(), params ?? {}] as const,
  unreadCount: () => [...notificationKeys.all, "unread-count"] as const,
};

function buildNotificationListQuery(params?: NotificationListParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/**
 * `GET /api/v1/notifications` — any authenticated caller, self-scoped,
 * newest-first. `placeholderData: keepPreviousData` keeps the prior page's
 * rows on screen during a page turn (see `useMyAttendance`'s doc comment for
 * the full `isFetching`/`isPlaceholderData` caveat — the same applies here).
 * `refetchInterval` polls every 30s as the "new arrival" delivery mechanism
 * (the plan leaves this as an unspecified technical default — "poll on
 * focus/interval" — and React Query's default `refetchOnWindowFocus`
 * already covers the focus-based half of that).
 */
export function useNotifications(params?: NotificationListParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildNotificationListQuery(params);
  return useQuery({
    queryKey: notificationKeys.list(params),
    queryFn: () =>
      authorizedFetch<PageResponse<NotificationResponse>>("tenant", `/v1/notifications${queryString}`),
    placeholderData: keepPreviousData,
    refetchInterval: 30_000,
  });
}

/**
 * `GET /api/v1/notifications/unread-count` — any authenticated caller,
 * self-scoped server-side (no id/tenant/user param sent), backs the
 * Notification Center nav badge (`nav-links.tsx`'s `NotificationsBadge`).
 * Same 30s `refetchInterval` as `useNotifications` so the badge doesn't
 * require a full page navigation to update, plus React Query's default
 * `refetchOnWindowFocus`.
 */
export function useUnreadNotificationCount() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: () => authorizedFetch<UnreadCountResponse>("tenant", "/v1/notifications/unread-count"),
    refetchInterval: 30_000,
  });
}

/**
 * `PATCH /api/v1/notifications/{id}/read` — marks one of the caller's own
 * notifications read, returning the updated row. No optimistic update: the
 * plan requires reflecting the confirmed server response, so the row only
 * flips to "read" once this mutation actually succeeds and the list
 * refetches. `404` (not `403`) if `id` doesn't resolve to a row owned by the
 * caller in their own tenant — callers should surface this as an ordinary
 * mutation error, never crash (see `notification-list.tsx`).
 */
export function useMarkNotificationRead() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<NotificationResponse>("tenant", `/v1/notifications/${id}/read`, {
        method: "PATCH",
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: notificationKeys.listAll() });
      queryClient.invalidateQueries({ queryKey: notificationKeys.unreadCount() });
    },
  });
}
