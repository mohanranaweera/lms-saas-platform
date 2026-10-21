import { NotificationList } from "@/components/notifications/notification-list";
import { NotificationPreferences } from "@/components/notifications/notification-preferences";

/**
 * Student Notification Center (MVP-018, plan §9.5/§10 Flow C). Renders `GET
 * /api/v1/notifications`, self-scoped server-side to this student — never
 * another student's notifications (`.claude/rules/ui-ux.md` §1). Today
 * these arrive from payment outcomes (confirmed/rejected/refunded) and, since
 * Wave 11, from institute announcements (broadcasts). The student's own
 * channel preferences (email/SMS/WhatsApp consent) are managed below.
 */
export default function StudentNotificationsPage() {
  return (
    <div className="flex flex-col gap-6">
      <NotificationList
        heading="Notifications"
        description="Announcements from your institute and updates about your payments."
        dashboardHref="/student/dashboard"
        emptyState={{
          title: "No notifications yet",
          description:
            "Announcements from your institute and payment updates (confirmed, rejected or refunded) appear here.",
        }}
      />
      <NotificationPreferences />
    </div>
  );
}
