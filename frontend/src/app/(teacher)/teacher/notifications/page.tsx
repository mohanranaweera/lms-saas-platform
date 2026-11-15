"use client";

import { NotificationList } from "@/components/notifications/notification-list";
import { NotificationPreferences } from "@/components/notifications/notification-preferences";

import { useT } from "@/i18n/i18n-provider";
/**
 * Teacher Notification Center (MVP-018, plan §9.5/§10 Flow D). Same `GET
 * /api/v1/notifications` read as the Student screen, self-scoped
 * server-side to this teacher. Since Wave 11 teachers receive institute
 * announcements (the "All teachers" broadcast audience); the empty state says
 * so. Channel preferences (email/SMS/WhatsApp consent) are managed below.
 */
export default function TeacherNotificationsPage() {
  const t = useT();
  return (
    <div className="flex flex-col gap-6">
      <NotificationList
        heading={t("teacher.page.notifications.notifications")}
        description={t("teacher.page.notifications.announcementsFromYourInstitute")}
        dashboardHref="/teacher/dashboard"
        emptyState={{
          title: t("teacher.page.notifications.noNotificationsYet"),
          description: t("teacher.page.notifications.announcementsYourInstituteSends"),
        }}
      />
      <NotificationPreferences />
    </div>
  );
}
