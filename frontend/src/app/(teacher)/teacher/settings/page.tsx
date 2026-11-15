"use client";

import Link from "next/link";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

import { useT } from "@/i18n/i18n-provider";
/**
 * Teacher Settings index (MEDIA-INT-01). A pure navigation index - no data
 * fetch, so no loading/error state of its own; each destination owns its
 * request and its own QueryStateBoundary.
 */
export default function TeacherSettingsPage() {
  const t = useT();
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">{t("teacher.page.settings.settings")}</h1>
        <p className="text-sm text-muted-foreground">{t("teacher.page.settings.yourOwnTeachingAccounts")}</p>
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Link href="/teacher/settings/integrations" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>{t("teacher.page.settings.integrations")}</CardTitle>
              <CardDescription>{t("teacher.page.settings.connectYourOwnZoom")}</CardDescription>
            </CardHeader>
          </Card>
        </Link>
      </div>
    </div>
  );
}
