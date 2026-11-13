"use client";

import { useT } from "@/i18n/i18n-provider";
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
  const t = useT();
  return (
    <div className="flex flex-col gap-6">
      <NotificationList
        heading={t("student.notifications.title")}
        description={t("student.notifications.subtitle")}
        dashboardHref="/student/dashboard"
        emptyState={{
          title: t("student.notifications.empty.title"),
          description: t("student.notifications.empty.description"),
        }}
      />
      <NotificationPreferences />
    </div>
  );
}
