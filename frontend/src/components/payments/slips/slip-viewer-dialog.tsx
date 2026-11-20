"use client";

import { useEffect, useState } from "react";
import { AlertCircle, Download, FileText } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LoadingState } from "@/components/states/loading-state";
import { StaffDialog } from "@/components/payments/staff/staff-dialog";
import { SlipFlagBadge, SlipStatusBadge } from "@/components/payments/status-badges";
import { isApiClientError } from "@/lib/api/error";
import {
  useSlipDownloadUrl,
  type PaymentSlipResponse,
  type SlipDownloadUrlResponse,
} from "@/lib/api/payment-slips";
import { ApproveSlipDialog } from "./approve-slip-dialog";
import { RejectSlipDialog } from "./reject-slip-dialog";

/**
 * PAY-HUB-01 Klass-style "Payment Slip" viewer. The slip is shown inline
 * (image, or embedded PDF) from a fresh short-lived signed URL fetched each
 * time the dialog opens - never cached or stored. The reference is the one
 * the student submitted and is read-only. Approve / Reject reuse the slip
 * dialogs (same backend gates: duplicate flags need an override reason, a
 * rejection needs a reason) and render only when `canReview` and the slip is
 * `UNDER_REVIEW` - UX only; the endpoints re-check everything.
 */
export function SlipViewerDialog({
  slip,
  open,
  onOpenChange,
  canReview,
}: {
  slip: PaymentSlipResponse;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  canReview: boolean;
}) {
  return (
    <StaffDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Payment Slip"
      description={[slip.studentName, slip.displayOrderNumber].filter(Boolean).join(" · ") || undefined}
      size="lg"
      testId="slip-viewer"
    >
      <SlipViewerBody slip={slip} canReview={canReview} onClose={() => onOpenChange(false)} />
    </StaffDialog>
  );
}

function SlipViewerBody({
  slip,
  canReview,
  onClose,
}: {
  slip: PaymentSlipResponse;
  canReview: boolean;
  onClose: () => void;
}) {
  const download = useSlipDownloadUrl();
  const [link, setLink] = useState<SlipDownloadUrlResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const { mutateAsync } = download;

  // The body only mounts while the dialog is open, so this runs once per open.
  useEffect(() => {
    let cancelled = false;
    mutateAsync(slip.id)
      .then((result) => {
        if (!cancelled) setLink(result);
      })
      .catch((fetchError: unknown) => {
        if (!cancelled) {
          setError(isApiClientError(fetchError) ? fetchError.message : "Couldn't load the slip file.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [mutateAsync, slip.id]);

  async function handleDownload() {
    setError(null);
    try {
      // Always a fresh signed URL - the one shown may have expired.
      const fresh = await mutateAsync(slip.id);
      window.open(fresh.url, "_blank", "noopener,noreferrer");
    } catch (fetchError) {
      setError(isApiClientError(fetchError) ? fetchError.message : "Couldn't open the slip file.");
    }
  }

  const reviewable = canReview && slip.status === "UNDER_REVIEW";

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex min-h-[16rem] flex-1 items-center justify-center overflow-auto bg-muted/40 p-3 sm:max-h-[60vh]">
        {error ? (
          <Alert variant="destructive" className="max-w-md">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        ) : !link ? (
          <LoadingState label="Loading slip…" />
        ) : link.contentType?.startsWith("image/") ? (
          // eslint-disable-next-line @next/next/no-img-element -- short-lived signed storage URL, not an optimisable asset
          <img
            src={link.url}
            alt={`Payment slip ${slip.referenceNumber}`}
            className="max-h-[55vh] w-auto max-w-full object-contain"
            data-testid="slip-viewer-image"
          />
        ) : link.contentType === "application/pdf" ? (
          <iframe
            src={link.url}
            title={`Payment slip ${slip.referenceNumber}`}
            className="h-[55vh] w-full rounded border border-border bg-background"
            data-testid="slip-viewer-pdf"
          />
        ) : (
          <div className="flex flex-col items-center gap-2 text-sm text-muted-foreground">
            <FileText className="size-8" aria-hidden="true" />
            This file can&apos;t be previewed here. Use Download to open it.
          </div>
        )}
      </div>

      <div className="flex flex-col gap-3 border-t border-border p-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`slip-viewer-method-${slip.id}`}>Payment method</Label>
            <Input id={`slip-viewer-method-${slip.id}`} value="Bank transfer" readOnly className="w-40" />
          </div>
          <div className="flex min-w-48 flex-1 flex-col gap-1.5">
            <Label htmlFor={`slip-viewer-reference-${slip.id}`}>Reference number</Label>
            <Input
              id={`slip-viewer-reference-${slip.id}`}
              value={slip.referenceNumber}
              readOnly
              data-testid="slip-viewer-reference"
            />
          </div>
          <SlipStatusBadge status={slip.status} />
        </div>
        {slip.flags.length > 0 ? (
          <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
            Flags:
            {slip.flags.map((flag) => (
              <SlipFlagBadge key={flag.id} flagType={flag.flagType} />
            ))}
          </div>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2">
          {reviewable ? (
            <>
              <ApproveSlipDialog slip={slip} onDone={onClose} />
              <RejectSlipDialog slip={slip} onDone={onClose} />
            </>
          ) : null}
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            Close
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={handleDownload}
            disabled={download.isPending}
            data-testid="slip-viewer-download"
          >
            <Download aria-hidden="true" />
            Download
          </Button>
        </div>
      </div>
    </div>
  );
}
