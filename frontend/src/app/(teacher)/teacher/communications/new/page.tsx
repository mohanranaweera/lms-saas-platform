"use client";

import { useRouter } from "next/navigation";
import { PageHeader } from "@/components/ui/page-header";
import { BroadcastComposer } from "@/components/communications/broadcast-composer";

import { useT } from "@/i18n/i18n-provider";
/** Teacher → New message (Wave 11): own courses, in-app or email only (enforced server-side). */
export default function TeacherNewMessagePage() {
  const t = useT();
  const router = useRouter();
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t("teacher.page.communicationsNew.newMessage")} description={t("teacher.page.communicationsNew.messageTheStudentsCurrently")} />
      <BroadcastComposer mode="teacher" onSent={(broadcast) => router.push(`/teacher/communications/${broadcast.id}`)} />
    </div>
  );
}
