"use client";

import Link from "next/link";
import { Send } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canSendCommunications } from "@/lib/auth/permissions";
import { PageHeader } from "@/components/ui/page-header";
import { Button } from "@/components/ui/button";
import { BroadcastList } from "@/components/communications/broadcast-list";

/**
 * Tenant Admin → Communication → Broadcasts (Wave 11, PAR-12-04). Readable by
 * `COMMUNICATIONS`/`VIEW`; "New broadcast" only for roles that can send. The
 * backend enforces both on every request.
 */
export default function BroadcastsPage() {
  const { session } = useAuth();
  const canSend = canSendCommunications(session?.role ?? null);
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Broadcasts"
        description="Messages sent to students and teachers by in-app notification, email, SMS or WhatsApp."
        actions={
          canSend ? (
            <Button render={<Link href="/tenant-admin/communications/new" />}>
              <Send className="size-4" aria-hidden="true" />
              New broadcast
            </Button>
          ) : null
        }
      />
      <BroadcastList basePath="/tenant-admin/communications" />
    </div>
  );
}
