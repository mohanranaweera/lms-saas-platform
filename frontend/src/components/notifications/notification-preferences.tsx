"use client";

import {
  useNotificationPreferences,
  useUpdateNotificationPreferences,
  type NotificationPreference,
} from "@/lib/api/notifications";
import { isApiClientError } from "@/lib/api/error";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

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
  const query = useNotificationPreferences();
  const update = useUpdateNotificationPreferences();
  return (
    <Card>
      <CardHeader>
        <CardTitle>How you receive announcements</CardTitle>
        <CardDescription>
          Announcements always appear here. Messages about your payments and account access are always sent by email.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-2">
        <QueryStateBoundary query={query} loadingLabel="Loading preferences…" loginPath="/login">
          {(preferences) => (
            <fieldset className="flex flex-col gap-2" disabled={update.isPending}>
              <legend className="sr-only">Announcement channels</legend>
              {preferences.map((preference) => (
                <label key={preference.channel} className="flex items-center gap-2 text-sm text-foreground">
                  <input
                    type="checkbox"
                    checked={preference.optedIn}
                    onChange={(event) => update.mutate({ [preference.channel]: event.target.checked })}
                  />
                  {LABELS[preference.channel]}
                  {preference.explicitOptInRequired && !preference.optedIn ? (
                    <span className="text-muted-foreground">(off until you turn it on)</span>
                  ) : null}
                </label>
              ))}
            </fieldset>
          )}
        </QueryStateBoundary>
        <div role="status" aria-live="polite">
          {update.isSuccess ? <p className="text-sm text-muted-foreground">Preferences saved.</p> : null}
          {update.isError ? (
            <p className="text-sm text-destructive">
              {isApiClientError(update.error) ? update.error.message : "Could not save. Please try again."}
            </p>
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}
