import { NotificationList } from "@/components/notifications/notification-list";

/**
 * Teacher Notification Center (MVP-018, plan §9.5/§10 Flow D). Same `GET
 * /api/v1/notifications` read as the Student screen, self-scoped
 * server-side to this teacher. Genuinely empty at launch — no
 * Teacher-triggering event exists yet in this MVP (per
 * `NotificationController`'s own doc comment) — so the empty-state copy
 * below is worded as "nothing has happened yet", not as if something is
 * broken.
 */
export default function TeacherNotificationsPage() {
  return (
    <NotificationList
      heading="Notifications"
      description="Your activity feed. Notifications for teacher-facing events will appear here."
      dashboardHref="/teacher/dashboard"
      emptyState={{
        title: "No notifications yet",
        description:
          "This is your activity feed. There are no notification-triggering events for teachers yet in this release, so it's expected to be empty for now.",
      }}
    />
  );
}
