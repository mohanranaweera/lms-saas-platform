"use client";

import Link from "next/link";
import { Send } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { Button } from "@/components/ui/button";
import { BroadcastList } from "@/components/communications/broadcast-list";

/**
 * Teacher → Messages (Wave 11). A teacher sees only the broadcasts they sent
 * and can message only students of their own courses - both enforced by the
 * backend.
 */
export default function TeacherMessagesPage() {
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Messages"
        description="Send an announcement to the students enrolled in your courses."
        actions={
          <Button render={<Link href="/teacher/communications/new" />}>
            <Send className="size-4" aria-hidden="true" />
            New message
          </Button>
        }
      />
      <BroadcastList basePath="/teacher/communications" />
    </div>
  );
}
