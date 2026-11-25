/**
 * INST-QR-01 sharing helpers. Everything here is user-initiated and local:
 * no recipients are chosen, nothing is sent, and the PNG never leaves the
 * browser except through the user's own share sheet or download.
 */

export const MAX_MESSAGE_LENGTH = 500;

export function defaultShareMessage(instituteName: string, connectionUrl: string): string {
  return `Connect to ${instituteName}: scan this QR or open ${connectionUrl}, then sign in using your student account.`;
}

/** Keeps the connection link in the message even if the user edited it out. */
export function messageWithLink(message: string, connectionUrl: string): string {
  const trimmed = message.trim();
  if (!trimmed) return defaultShareMessage("your institute", connectionUrl);
  return trimmed.includes(connectionUrl) ? trimmed : `${trimmed}\n${connectionUrl}`;
}

/** `https://wa.me/?text=` opens WhatsApp's own composer, where the user picks recipients. Text only. */
export function whatsappComposerUrl(message: string): string {
  return `https://wa.me/?text=${encodeURIComponent(message)}`;
}

/** Opens the user's mail app with subject/body only — no recipient, no attachment. */
export function emailComposerUrl(instituteName: string, message: string): string {
  const subject = `Connect to ${instituteName}`;
  return `mailto:?subject=${encodeURIComponent(subject)}&body=${encodeURIComponent(message)}`;
}

export function qrFileName(instituteName: string): string {
  const slug = instituteName
    .toLowerCase()
    .normalize("NFKD")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return `${slug || "institute"}-institute-qr.png`;
}

export function qrDataUrl(qrPngBase64: string): string {
  return `data:image/png;base64,${qrPngBase64}`;
}

export function qrPngBlob(qrPngBase64: string): Blob {
  const binary = atob(qrPngBase64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new Blob([bytes], { type: "image/png" });
}

export function downloadQrPng(qrPngBase64: string, instituteName: string): void {
  const url = URL.createObjectURL(qrPngBlob(qrPngBase64));
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = qrFileName(instituteName);
    anchor.rel = "noopener";
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    // Give the browser a tick to start the download before revoking.
    setTimeout(() => URL.revokeObjectURL(url), 1_000);
  }
}

/** Resolves true only when the text really reached the clipboard. */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (!navigator.clipboard?.writeText) return false;
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export type NativeShareOutcome = "shared-file" | "shared-link" | "cancelled" | "unsupported" | "failed";

/**
 * Native share: the PNG + message when the browser can share files, otherwise
 * the link + message. A dismissed share sheet is "cancelled", never success.
 */
export async function nativeShare(params: {
  qrPngBase64: string;
  instituteName: string;
  message: string;
}): Promise<NativeShareOutcome> {
  if (typeof navigator === "undefined" || typeof navigator.share !== "function") return "unsupported";
  const title = `Connect to ${params.instituteName}`;
  const file = new File([qrPngBlob(params.qrPngBase64)], qrFileName(params.instituteName), { type: "image/png" });
  const withFile: ShareData = { title, text: params.message, files: [file] };
  const canShareFile = typeof navigator.canShare === "function" && navigator.canShare(withFile);
  try {
    if (canShareFile) {
      await navigator.share(withFile);
      return "shared-file";
    }
    // The message already carries the link (see messageWithLink); a separate
    // `url` would make many targets paste it twice.
    await navigator.share({ title, text: params.message });
    return "shared-link";
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") return "cancelled";
    return "failed";
  }
}

export function canUseNativeShare(): boolean {
  return typeof navigator !== "undefined" && typeof navigator.share === "function";
}
