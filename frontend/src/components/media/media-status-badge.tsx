import {
  CheckCircle2,
  CircleAlert,
  Clock,
  CloudUpload,
  Hourglass,
  PauseCircle,
  PlugZap,
  RefreshCw,
  Upload,
} from "lucide-react";
import type { MediaAsset } from "@/lib/api/media";
import { Badge } from "@/components/ui/badge";

type Variant = "default" | "warning" | "outline" | "secondary" | "destructive";

export interface MediaStatusInfo {
  label: string;
  variant: Variant;
  Icon: typeof CheckCircle2;
}

/**
 * One status for the media item, combining the asset's processing status and
 * its latest upload job (VIDEO Phase 5). Text plus icon - colour is never the
 * only signal.
 */
export function mediaStatus(asset: MediaAsset): MediaStatusInfo {
  const upload = asset.uploadProgress?.state;
  switch (asset.processingStatus) {
    case "READY":
      return { label: "Ready", variant: "default", Icon: CheckCircle2 };
    case "FAILED":
      return { label: "Failed", variant: "destructive", Icon: CircleAlert };
    case "UPLOADED":
    case "PROCESSING":
      return { label: "Processing on YouTube", variant: "secondary", Icon: Hourglass };
    case "CREATED":
      return { label: "Waiting for the file", variant: "outline", Icon: Upload };
    case "UPLOADING":
      if (upload === "QUOTA_PAUSED") return { label: "Paused until tomorrow", variant: "warning", Icon: PauseCircle };
      if (upload === "PARKED_RECONNECT") return { label: "Waiting for YouTube", variant: "warning", Icon: PlugZap };
      if (upload === "RETRY_WAIT") return { label: "Retrying", variant: "warning", Icon: RefreshCw };
      if (upload === "TRANSFERRING") return { label: "Sending to YouTube", variant: "secondary", Icon: CloudUpload };
      return { label: "Queued", variant: "secondary", Icon: Clock };
    default:
      return { label: asset.processingStatus, variant: "outline", Icon: Clock };
  }
}

export function MediaStatusBadge({ asset }: { asset: MediaAsset }) {
  const { label, variant, Icon } = mediaStatus(asset);
  return (
    <Badge variant={variant}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  );
}
