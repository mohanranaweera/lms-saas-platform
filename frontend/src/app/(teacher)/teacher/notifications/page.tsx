import { NotificationList } from "@/components/notifications/notification-list";
import { NotificationPreferences } from "@/components/notifications/notification-preferences";

/**
 * Teacher Notification Center (MVP-018, plan §9.5/§10 Flow D). Same `GET
 * /api/v1/notifications` read as the Student screen, self-scoped
 * server-side to this teacher. Since Wave 11 teachers receive institute
 * announcements (the "All teachers" broadcast audience); the empty state says
 * so. Channel preferences (email/SMS/WhatsApp consent) are managed below.
 */
export default function TeacherNotificationsPage() {
  return (
    <div className="flex flex-col gap-6">
      <NotificationList
        heading="Notifications"
        description="Announcements from your institute."
        dashboardHref="/teacher/dashboard"
        emptyState={{
          title: "No notifications yet",
          description: "Announcements your institute sends to teachers will appear here.",
        }}
      />
      <NotificationPreferences />
    </div>
  );
}
