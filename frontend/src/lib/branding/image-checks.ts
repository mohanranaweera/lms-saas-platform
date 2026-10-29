import { TENANT_ASSET_MAX_BYTES, type TenantAssetType } from "./asset-limits";

/**
 * Client-side pre-upload checks for tenant branding images. UX only — the
 * backend sniffs the content type and enforces the size limits regardless.
 * Errors block the upload; warnings are shown but do not block.
 */

export interface ImageCheckResult {
  errors: string[];
  warnings: string[];
}

export interface ContentBounds {
  /** Fraction (0–1) of the image width covered by visible content. */
  widthCoverage: number;
  heightCoverage: number;
}

function formatMb(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(bytes % (1024 * 1024) === 0 ? 0 : 1)} MB`;
}

/** PNG (`89 50 4E 47`) or JPEG (`FF D8 FF`) magic bytes. */
export function sniffImageType(bytes: Uint8Array): "image/png" | "image/jpeg" | null {
  if (bytes.length >= 4 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return "image/png";
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return "image/jpeg";
  return null;
}

/**
 * Bounding box of "content" in RGBA pixel data. If any corner is
 * transparent, content = pixels with alpha > 16; otherwise the top-left
 * pixel is treated as the background and content = pixels noticeably
 * different from it (catches logos exported with a wide white margin).
 */
export function analyzeContentBounds(data: Uint8ClampedArray, width: number, height: number): ContentBounds {
  const at = (x: number, y: number) => (y * width + x) * 4;
  const corners = [at(0, 0), at(width - 1, 0), at(0, height - 1), at(width - 1, height - 1)];
  const transparent = corners.some((index) => data[index + 3] < 250);
  const bg = [data[0], data[1], data[2]];
  let minX = width;
  let minY = height;
  let maxX = -1;
  let maxY = -1;
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const i = at(x, y);
      const isContent = transparent
        ? data[i + 3] > 16
        : data[i + 3] > 16 && Math.abs(data[i] - bg[0]) + Math.abs(data[i + 1] - bg[1]) + Math.abs(data[i + 2] - bg[2]) > 36;
      if (!isContent) continue;
      if (x < minX) minX = x;
      if (x > maxX) maxX = x;
      if (y < minY) minY = y;
      if (y > maxY) maxY = y;
    }
  }
  if (maxX < 0) return { widthCoverage: 0, heightCoverage: 0 };
  return { widthCoverage: (maxX - minX + 1) / width, heightCoverage: (maxY - minY + 1) / height };
}

/** Artwork must span this share of the canvas width; text-only logos keep ascender/descender room vertically, hence the lower height bar. */
export const PADDED_LOGO_THRESHOLD = 0.75;
export const PADDED_LOGO_HEIGHT_THRESHOLD = 0.6;

/** Pure rules given the decoded facts — unit-tested in e2e/unit/appearance-theme.spec.ts. */
export function evaluateImage(
  type: TenantAssetType,
  facts: { sizeBytes: number; sniffedType: string | null; width: number; height: number; bounds: ContentBounds | null }
): ImageCheckResult {
  const errors: string[] = [];
  const warnings: string[] = [];
  const max = TENANT_ASSET_MAX_BYTES[type];
  if (!facts.sniffedType) errors.push("Choose a PNG or JPEG image. Other file types are not accepted.");
  if (facts.sizeBytes > max) errors.push(`This file is ${formatMb(facts.sizeBytes)}; the maximum is ${formatMb(max)}.`);
  if (errors.length || facts.width === 0) return { errors, warnings };

  const ratio = facts.width / facts.height;
  if (type === "SITE_LOGO" || type === "RECEIPT_LOGO") {
    if (facts.width < 300) warnings.push(`Low resolution: ${facts.width} px wide. Use at least 300 px (about 600 px recommended) so it stays sharp.`);
    if (facts.bounds && (facts.bounds.widthCoverage < PADDED_LOGO_THRESHOLD || facts.bounds.heightCoverage < PADDED_LOGO_HEIGHT_THRESHOLD)) {
      warnings.push("The logo has large empty margins, so it will look small in the header. Crop it tightly around the artwork.");
    }
  }
  if (type === "SITE_FAVICON") {
    if (Math.abs(ratio - 1) > 0.05) warnings.push("Favicons should be square; this image will be squeezed in browser tabs.");
    if (Math.min(facts.width, facts.height) < 48) warnings.push(`Too small: ${facts.width}×${facts.height} px. Use at least 48×48 (512×512 recommended).`);
  }
  if (type === "DESKTOP_HERO_BANNER") {
    if (facts.width < 1280 || facts.height < 427) warnings.push(`Small banner (${facts.width}×${facts.height} px). Minimum is 1280×427; 1920×480 is recommended.`);
    if (Math.abs(ratio - 4) > 0.4) warnings.push("Desktop banners are shown at 4:1. Other proportions are cropped at the edges.");
  }
  if (type === "MOBILE_HERO_BANNER") {
    if (facts.width < 600) warnings.push(`Small banner (${facts.width}×${facts.height} px). 800×300 is recommended.`);
    if (Math.abs(ratio - 8 / 3) > 0.3) warnings.push("Mobile banners are shown at 8:3. Other proportions are cropped at the edges.");
  }
  return { errors, warnings };
}

async function decode(file: File): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = url;
    await image.decode();
    return image;
  } finally {
    URL.revokeObjectURL(url);
  }
}

/** Browser-side check of a chosen file (object URL + canvas). */
export async function checkImageFile(type: TenantAssetType, file: File): Promise<ImageCheckResult> {
  const head = new Uint8Array(await file.slice(0, 8).arrayBuffer());
  const sniffedType = sniffImageType(head);
  const base = { sizeBytes: file.size, sniffedType, width: 0, height: 0, bounds: null };
  const early = evaluateImage(type, base);
  if (early.errors.length) return early;

  let image: HTMLImageElement;
  try {
    image = await decode(file);
  } catch {
    return { errors: ["This image could not be read. It may be corrupted."], warnings: [] };
  }
  const width = image.naturalWidth;
  const height = image.naturalHeight;
  let bounds: ContentBounds | null = null;
  if (type === "SITE_LOGO" || type === "RECEIPT_LOGO") {
    const scale = Math.min(1, 256 / Math.max(width, height));
    const w = Math.max(1, Math.round(width * scale));
    const h = Math.max(1, Math.round(height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const context = canvas.getContext("2d", { willReadFrequently: true });
    if (context) {
      context.drawImage(image, 0, 0, w, h);
      bounds = analyzeContentBounds(context.getImageData(0, 0, w, h).data, w, h);
    }
  }
  return evaluateImage(type, { ...base, width, height, bounds });
}
