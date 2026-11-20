"use client";

import Link from "next/link";
import { Send } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { Button } from "@/components/ui/button";
import { BroadcastList } from "@/components/communications/broadcast-list";

import { useT } from "@/i18n/i18n-provider";
/**
 * Teacher → Messages (Wave 11). A teacher sees only the broadcasts they sent
 * and can message only students of their own courses - both enforced by the
 * backend.
 */
export default function TeacherMessagesPage() {
  const t = useT();
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title={t("teacher.page.communications.messages")}
        description={t("teacher.page.communications.sendAnAnnouncementTo")}
        actions={
          <Button render={<Link href="/teacher/communications/new" />}>
            <Send className="size-4" aria-hidden="true" />
            {t("teacher.page.communications.newMessage")}
          </Button>
        }
      />
      <BroadcastList basePath="/teacher/communications" />
    </div>
  );
}
