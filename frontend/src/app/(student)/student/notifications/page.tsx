import { NotificationList } from "@/components/notifications/notification-list";

/**
 * Student Notification Center (MVP-018, plan §9.5/§10 Flow C). Renders `GET
 * /api/v1/notifications`, self-scoped server-side to this student — never
 * another student's notifications (`.claude/rules/ui-ux.md` §1). Today
 * these arrive from `payment-management`'s confirmed/rejected/refunded
 * events (see `NotificationCenterService`); the empty-state copy below
 * explains that origin honestly rather than a generic "no data" message.
 */
export default function StudentNotificationsPage() {
  return (
    <NotificationList
      heading="Notifications"
      description="Updates about your payments and account activity."
      dashboardHref="/student/dashboard"
      emptyState={{
        title: "No notifications yet",
        description:
          "Notifications appear here after a payment is confirmed, rejected, or refunded. Nothing to show yet.",
      }}
    />
  );
}
