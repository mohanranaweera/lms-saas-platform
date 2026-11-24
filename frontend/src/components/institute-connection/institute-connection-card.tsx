"use client";

import { useId, useRef, useState } from "react";
import {
  AlertCircle,
  Check,
  Copy,
  Download,
  Mail,
  MessageCircle,
  QrCode,
  Share2,
  ShieldCheck,
  Smartphone,
} from "lucide-react";
import type { InstituteConnection } from "@/lib/api/institute-connection";
import {
  MAX_MESSAGE_LENGTH,
  canUseNativeShare,
  copyText,
  defaultShareMessage,
  downloadQrPng,
  emailComposerUrl,
  messageWithLink,
  nativeShare,
  qrDataUrl,
  whatsappComposerUrl,
} from "@/lib/institute-connection/share";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button, buttonVariants } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Textarea } from "@/components/ui/textarea";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
} from "@/components/ui/sheet";
import { cn } from "@/lib/utils";

/**
 * INST-QR-01 — the one Institute QR card used by both the Tenant Admin
 * dashboard and the Platform Admin institute details page. Everything shown
 * comes from one backend response (`InstituteConnection`), so the QR image,
 * the copyable link and the share message always carry the same canonical URL.
 *
 * The QR is public: it only selects the institute. It is not an operator /
 * device-enrollment code and grants no sign-in or course access — the copy
 * here says so rather than implying otherwise.
 */

type Feedback = { tone: "success" | "info" | "error"; text: string } | null;

export function InstituteConnectionCard({
  connection,
  scopeNote,
}: {
  connection: InstituteConnection;
  /** Platform Admin: names the institute this card belongs to. */
  scopeNote?: string;
}) {
  const headingId = useId();
  const linkId = useId();
  const linkRef = useRef<HTMLInputElement>(null);
  const [feedback, setFeedback] = useState<Feedback>(null);
  const { instituteName, connectionUrl, qrPngBase64 } = connection;

  async function onCopyLink() {
    if (await copyText(connectionUrl)) {
      setFeedback({ tone: "success", text: "Connection link copied." });
    } else {
      linkRef.current?.select();
      setFeedback({ tone: "error", text: "Couldn't copy automatically. The link is selected — copy it manually." });
    }
  }

  function onDownload() {
    try {
      downloadQrPng(qrPngBase64, instituteName);
      setFeedback({ tone: "info", text: "QR download started." });
    } catch {
      setFeedback({ tone: "error", text: "Couldn't download the QR. Please try again." });
    }
  }

  return (
    <section aria-labelledby={headingId} className="rounded-xl border border-border bg-card text-card-foreground">
      <div className="flex flex-col gap-1 border-b border-border px-4 py-4 sm:px-6">
        <h2 id={headingId} className="flex items-center gap-2 text-base font-semibold">
          <QrCode className="size-4 text-muted-foreground" aria-hidden="true" />
          Institute connection
        </h2>
        <p className="text-sm text-muted-foreground">
          {scopeNote ?? "Share this QR so students can find your institute on their phones."}
        </p>
      </div>

      <div className="grid gap-6 px-4 py-5 sm:px-6 md:grid-cols-[auto_minmax(0,1fr)]">
        <figure className="flex flex-col items-center gap-2 justify-self-center">
          {/* White plate keeps full contrast and the quiet zone in dark mode. */}
          <div className="rounded-lg border border-border bg-white p-2 shadow-xs">
            {/* eslint-disable-next-line @next/next/no-img-element -- inline PNG rendered by the backend */}
            <img
              src={qrDataUrl(qrPngBase64)}
              alt={`Institute QR for ${instituteName}. It opens ${connectionUrl}`}
              width={192}
              height={192}
              className="size-44 [image-rendering:pixelated] sm:size-48"
            />
          </div>
          <figcaption className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
            Institute QR
          </figcaption>
        </figure>

        <div className="flex min-w-0 flex-col gap-4">
          <InstituteIdentity name={instituteName} logoUrl={connection.logoUrl} />

          {!connection.connectable ? (
            <Alert role="status">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>
                This institute isn&apos;t active, so the link won&apos;t open for students until it is.
              </AlertDescription>
            </Alert>
          ) : null}

          <ol className="flex list-decimal flex-col gap-1 pl-5 text-sm text-muted-foreground">
            <li>Open the phone camera (or our student app) and scan the QR.</li>
            <li>Confirm the institute name shown is {instituteName}.</li>
            <li>Sign in with your own student account.</li>
          </ol>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={linkId}>Connection link (for anyone who can&apos;t scan)</Label>
            <div className="flex gap-2">
              <input
                id={linkId}
                ref={linkRef}
                readOnly
                value={connectionUrl}
                onFocus={(event) => event.currentTarget.select()}
                className="h-9 min-w-0 flex-1 rounded-lg border border-input bg-muted/40 px-3 font-mono text-xs text-foreground outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 sm:text-sm"
              />
              <Button type="button" variant="outline" size="icon-lg" onClick={onCopyLink} aria-label="Copy connection link">
                <Copy aria-hidden="true" />
              </Button>
            </div>
          </div>

          <div className="flex flex-col gap-2 sm:flex-row sm:flex-wrap">
            <ShareInstituteQrSheet connection={connection} />
            <Button type="button" variant="outline" onClick={onCopyLink}>
              <Copy aria-hidden="true" />
              Copy connection link
            </Button>
            <Button type="button" variant="outline" onClick={onDownload}>
              <Download aria-hidden="true" />
              Download QR (PNG)
            </Button>
          </div>

          <FeedbackLine feedback={feedback} />

          <AppAvailability connection={connection} />

          <p className="flex items-start gap-2 text-xs text-muted-foreground">
            <ShieldCheck className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
            This QR is public and safe to share. It only identifies the institute. It doesn&apos;t sign
            anyone in or give access to courses or payments.
          </p>
        </div>
      </div>
    </section>
  );
}

function InstituteIdentity({ name, logoUrl }: { name: string; logoUrl: string | null }) {
  const [logoFailed, setLogoFailed] = useState(false);
  const initials = name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase())
    .join("");
  return (
    <div className="flex items-center gap-3">
      {logoUrl && !logoFailed ? (
        // eslint-disable-next-line @next/next/no-img-element -- signed, short-lived logo URL
        <img
          src={logoUrl}
          alt=""
          onError={() => setLogoFailed(true)}
          className="size-11 shrink-0 rounded-lg border border-border bg-white object-contain p-1"
        />
      ) : (
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-muted text-sm font-semibold text-muted-foreground"
        >
          {initials || "?"}
        </span>
      )}
      <div className="min-w-0">
        <p className="truncate text-base font-semibold text-foreground">{name}</p>
        <p className="text-xs text-muted-foreground">Students connect to this institute</p>
      </div>
    </div>
  );
}

function AppAvailability({ connection }: { connection: InstituteConnection }) {
  const { androidUrl, iosUrl } = connection.mobileApps;
  if (!androidUrl && !iosUrl) {
    return (
      <p className="flex items-start gap-2 rounded-lg bg-muted/50 px-3 py-2 text-xs text-muted-foreground">
        <Smartphone className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
        The student mobile app isn&apos;t available yet. Until it is, scanning the QR opens the
        institute&apos;s sign-in page in the phone&apos;s browser.
      </p>
    );
  }
  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <span className="text-muted-foreground">Student app:</span>
      {androidUrl ? (
        <a href={androidUrl} target="_blank" rel="noopener noreferrer" className={buttonVariants({ variant: "outline", size: "sm" })}>
          Google Play
        </a>
      ) : null}
      {iosUrl ? (
        <a href={iosUrl} target="_blank" rel="noopener noreferrer" className={buttonVariants({ variant: "outline", size: "sm" })}>
          App Store
        </a>
      ) : null}
    </div>
  );
}

function FeedbackLine({ feedback }: { feedback: Feedback }) {
  if (!feedback) return <LiveRegion message="" />;
  if (feedback.tone === "error") {
    return (
      <p role="alert" className="flex items-center gap-1.5 text-sm text-destructive">
        <AlertCircle className="size-4 shrink-0" aria-hidden="true" />
        {feedback.text}
      </p>
    );
  }
  return (
    <>
      <LiveRegion message={feedback.text} />
      <p aria-hidden="true" className="flex items-center gap-1.5 text-sm text-muted-foreground">
        {feedback.tone === "success" ? <Check className="size-4 shrink-0 text-emerald-600" /> : null}
        {feedback.text}
      </p>
    </>
  );
}

/** "Share Institute QR": preview + editable message + native share and explicit fallbacks. */
function ShareInstituteQrSheet({ connection }: { connection: InstituteConnection }) {
  const { instituteName, connectionUrl, qrPngBase64 } = connection;
  const messageId = useId();
  const messageRef = useRef<HTMLTextAreaElement>(null);
  const [message, setMessage] = useState(() => defaultShareMessage(instituteName, connectionUrl));
  const [feedback, setFeedback] = useState<Feedback>(null);
  const [nativeSupported] = useState(canUseNativeShare);
  const finalMessage = messageWithLink(message, connectionUrl);

  async function onNativeShare() {
    const outcome = await nativeShare({ qrPngBase64, instituteName, message: finalMessage });
    const texts: Record<typeof outcome, Feedback> = {
      "shared-file": { tone: "success", text: "Shared the QR image and message." },
      "shared-link": {
        tone: "success",
        text: "Shared the message and link. This browser can't share images, so download the QR to send it too.",
      },
      cancelled: { tone: "info", text: "Sharing cancelled. Nothing was sent." },
      unsupported: { tone: "info", text: "This browser can't open a share menu. Use the options below." },
      failed: { tone: "error", text: "Couldn't open the share menu. Download the QR or copy the message instead." },
    };
    setFeedback(texts[outcome]);
  }

  async function onCopyMessage() {
    if (await copyText(finalMessage)) {
      setFeedback({ tone: "success", text: "Message copied." });
    } else {
      messageRef.current?.select();
      setFeedback({ tone: "error", text: "Couldn't copy automatically. The message is selected — copy it manually." });
    }
  }

  function onDownload() {
    try {
      downloadQrPng(qrPngBase64, instituteName);
      setFeedback({ tone: "info", text: "QR download started." });
    } catch {
      setFeedback({ tone: "error", text: "Couldn't download the QR. Please try again." });
    }
  }

  return (
    <Sheet onOpenChange={(open) => !open && setFeedback(null)}>
      <SheetTrigger render={<Button type="button" />}>
        <Share2 aria-hidden="true" />
        Share Institute QR
      </SheetTrigger>
      <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-md">
        <SheetHeader>
          <SheetTitle>Share Institute QR</SheetTitle>
          <SheetDescription>
            Send students the QR and link for {instituteName}. You choose who receives it.
          </SheetDescription>
        </SheetHeader>

        <div className="flex flex-col gap-5 px-4 pb-2">
          <div className="flex items-center gap-4 rounded-lg border border-border p-3">
            <div className="shrink-0 rounded-md border border-border bg-white p-1">
              {/* eslint-disable-next-line @next/next/no-img-element -- inline PNG rendered by the backend */}
              <img
                src={qrDataUrl(qrPngBase64)}
                alt={`Institute QR for ${instituteName}`}
                width={112}
                height={112}
                className="size-28 [image-rendering:pixelated]"
              />
            </div>
            <div className="min-w-0">
              <p className="font-semibold text-foreground">{instituteName}</p>
              <p className="break-all font-mono text-xs text-muted-foreground">{connectionUrl}</p>
            </div>
          </div>

          <div className="flex flex-col gap-1.5">
            <Label htmlFor={messageId}>Message</Label>
            <Textarea
              id={messageId}
              ref={messageRef}
              rows={4}
              maxLength={MAX_MESSAGE_LENGTH}
              value={message}
              onChange={(event) => setMessage(event.target.value)}
            />
            <p className="text-xs text-muted-foreground">
              The connection link is always included, even if you remove it from the text.
            </p>
          </div>

          <div className="flex flex-col gap-2">
            {nativeSupported ? (
              <Button type="button" onClick={onNativeShare}>
                <Share2 aria-hidden="true" />
                Share…
              </Button>
            ) : null}
            <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
              <Button type="button" variant="outline" onClick={onDownload}>
                <Download aria-hidden="true" />
                Download QR
              </Button>
              <Button type="button" variant="outline" onClick={onCopyMessage}>
                <Copy aria-hidden="true" />
                Copy message
              </Button>
              <a
                href={whatsappComposerUrl(finalMessage)}
                target="_blank"
                rel="noopener noreferrer"
                className={cn(buttonVariants({ variant: "outline" }))}
              >
                <MessageCircle aria-hidden="true" />
                Open in WhatsApp
              </a>
              <a href={emailComposerUrl(instituteName, finalMessage)} className={cn(buttonVariants({ variant: "outline" }))}>
                <Mail aria-hidden="true" />
                Open in email
              </a>
            </div>
            <p className="text-xs text-muted-foreground">
              WhatsApp and email open with the message and link only. To include the QR image,
              download it and attach it yourself. Nothing is sent until you send it.
            </p>
          </div>

          <FeedbackLine feedback={feedback} />
        </div>

        <SheetFooter />
      </SheetContent>
    </Sheet>
  );
}
