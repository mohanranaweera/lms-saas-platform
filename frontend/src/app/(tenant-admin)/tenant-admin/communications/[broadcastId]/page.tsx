"use client";

import { useParams } from "next/navigation";
import { BroadcastDetail } from "@/components/communications/broadcast-detail";

/** Tenant Admin → Broadcast detail + delivery log (Wave 11). 404 for another tenant's id (backend). */
export default function BroadcastDetailPage() {
  const params = useParams<{ broadcastId: string }>();
  return <BroadcastDetail broadcastId={params.broadcastId ?? ""} backHref="/tenant-admin/communications" />;
}
