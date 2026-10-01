"use client";

import { useRouter } from "next/navigation";
import { PageHeader } from "@/components/ui/page-header";
import { BroadcastComposer } from "@/components/communications/broadcast-composer";

/** Teacher → New message (Wave 11): own courses, in-app or email only (enforced server-side). */
export default function TeacherNewMessagePage() {
  const router = useRouter();
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="New message" description="Message the students currently enrolled in one of your courses." />
      <BroadcastComposer mode="teacher" onSent={(broadcast) => router.push(`/teacher/communications/${broadcast.id}`)} />
    </div>
  );
}
