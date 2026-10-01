"use client";

import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth/auth-context";
import { canSendCommunications } from "@/lib/auth/permissions";
import { PageHeader } from "@/components/ui/page-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { BroadcastComposer } from "@/components/communications/broadcast-composer";

/**
 * Tenant Admin → New broadcast (Wave 11). `COMMUNICATIONS`/`CREATE_EDIT`. A
 * role without it (e.g. Read-only Auditor) gets a plain notice instead of the
 * composer - UX only; the backend 403 on send is the real enforcement.
 */
export default function NewBroadcastPage() {
  const router = useRouter();
  const { session } = useAuth();
  if (!canSendCommunications(session?.role ?? null)) {
    return (
      <Alert>
        <AlertDescription>Your role can view broadcasts but not send them.</AlertDescription>
      </Alert>
    );
  }
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title="New broadcast" description="Choose who receives it, write the message, then pick channels." />
      <BroadcastComposer
        mode="staff"
        onSent={(broadcast) => router.push(`/tenant-admin/communications/${broadcast.id}`)}
      />
    </div>
  );
}
