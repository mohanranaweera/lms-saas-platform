"use client";

import {
  useNotificationPreferences,
  useUpdateNotificationPreferences,
  type NotificationPreference,
} from "@/lib/api/notifications";
import { isApiClientError } from "@/lib/api/error";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

import { useT } from "@/i18n/i18n-provider";
const LABELS: Record<NotificationPreference["channel"], string> = {
  EMAIL: "Email announcements",
  SMS: "SMS announcements",
  WHATSAPP: "WhatsApp announcements",
};

/**
 * Wave 11 (PAR-22-03) - the signed-in user's own consent for announcements
 * per channel. Self-scoped server-side; changes save as soon as a box is
 * toggled. In-app notifications, and messages about your payments or account
 * access, are always delivered.
 */
export function NotificationPreferences() {
  const t = useT();
  const query = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();
  return (
    <Card>
      <CardHeader>
        <CardTitle>{t("notifications.notificationPreferences.howYouReceiveAnnouncements")}</CardTitle>
        <CardDescription>
          {t("notifications.notificationPreferences.announcementsAlwaysAppearHere")}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        <QueryStateBoundary query={query} loadingLabel={t("notifications.notificationPreferences.loadingPreferences")} loginPath="/login">
          {(preferences) => (
            <fieldset className="flex flex-col gap-2" disabled={update.isPending}>
              <legend className="sr-only">{t("notifications.notificationPreferences.announcementChannels")}</legend>
              {preferences.map((preference) => (
                <label key={preference.channel} className="flex items-center gap-2 text-sm text-foreground">
                  <input
                    type="checkbox"
                    checked={preference.optedIn}
                    onChange={(event) => update.mutate({ [preference.channel]: event.target.checked })}
                  />
                  {preference.channel === "EMAIL" ? t("notifications.channel.email") : preference.channel === "SMS" ? t("notifications.channel.sms") : preference.channel === "WHATSAPP" ? t("notifications.channel.whatsapp") : LABELS[preference.channel]}
                  {preference.explicitOptInRequired && !preference.optedIn ? (
                    <span className="text-muted-foreground">{t("notifications.notificationPreferences.offUntilYouTurn")}</span>
                  ) : null}
                </label>
              ))}
            </fieldset>
          )}
        </QueryStateBoundary>
        <div role="status" aria-live="polite">
          {update.isSuccess ? <p className="text-sm text-muted-foreground">{t("notifications.notificationPreferences.preferencesSaved")}</p> : null}
          {update.isError ? (
            <p className="text-sm text-destructive">
              {isApiClientError(update.error) ? update.error.message : t("notifications.notificationPreferences.couldNotSavePlease")}
            </p>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}
