"use client";

import { useId, useRef, useState } from "react";
import { ImageIcon, ImagePlus, Loader2, RefreshCw, Trash2, UploadCloud } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { LiveRegion } from "@/components/ui/live-region";
import { cn } from "@/lib/utils";
import { isApiClientError } from "@/lib/api/error";
import {
  MAX_BANNERS_PER_TYPE,
  useDeleteTenantAsset,
  useTenantAsset,
  useTenantAssetList,
  useUploadTenantAsset,
  type TenantAsset,
  type TenantAssetType,
} from "@/lib/api/tenant-assets";
import { checkImageFile, type ImageCheckResult } from "@/lib/branding/image-checks";

function errorMessage(error: unknown, fallback: string): string {
  if (isApiClientError(error)) {
    if (error.status === 403) return "You don't have permission to change institute branding.";
    return error.message || fallback;
  }
  return fallback;
}

/** Runs the client-side checks, then uploads when there are no blocking errors. */
function useCheckedUpload(type: TenantAssetType) {
  const upload = useUploadTenantAsset(type);
  const [check, setCheck] = useState<ImageCheckResult | null>(null);
  const [checking, setChecking] = useState(false);
  const [uploadedName, setUploadedName] = useState<string | null>(null);

  const choose = async (file: File | undefined) => {
    if (!file) return;
    setUploadedName(null);
    upload.reset();
    setChecking(true);
    let result: ImageCheckResult;
    try {
      result = await checkImageFile(type, file);
    } catch {
      result = { errors: ["This file could not be read. Choose a PNG or JPEG image."], warnings: [] };
    } finally {
      setChecking(false);
    }
    setCheck(result);
    if (result.errors.length) return;
    try {
      await upload.mutateAsync(file);
      setUploadedName(file.name);
    } catch {
      // Rendered from `upload.error` below.
    }
  };

  return { upload, check, checking, uploadedName, choose, busy: checking || upload.isPending };
}

function CheckMessages({ check, uploadError }: { check: ImageCheckResult | null; uploadError: unknown }) {
  return (
    <>
      {check?.errors.map((message) => (
        <p key={message} role="alert" className="text-xs text-destructive">
          {message}
        </p>
      ))}
      {check?.warnings.length ? (
        <ul role="status" className="flex flex-col gap-1 rounded-md bg-warning/10 px-2.5 py-2 text-xs text-warning">
          {check.warnings.map((message) => (
            <li key={message}>{message}</li>
          ))}
        </ul>
      ) : null}
      {uploadError ? (
        <p role="alert" className="text-xs text-destructive">
          {errorMessage(uploadError, "Upload failed. Choose a PNG or JPEG image within the allowed size.")}
        </p>
      ) : null}
    </>
  );
}

/**
 * Singleton branding asset (institute logo, favicon, receipt logo):
 * upload / change / remove with an `object-contain` preview. Uploads save
 * immediately (independent of the page's Save button).
 */
export function AssetUploadField({
  label,
  type,
  guidance,
  canManage,
  previewShape = "wide",
}: {
  label: string;
  type: TenantAssetType;
  guidance: string;
  canManage: boolean;
  previewShape?: "wide" | "square";
}) {
  const inputId = useId();
  const guidanceId = useId();
  const input = useRef<HTMLInputElement>(null);
  const current = useTenantAsset(type);
  const remove = useDeleteTenantAsset();
  const { upload, check, checking, uploadedName, choose, busy } = useCheckedUpload(type);
  const asset = current.data ?? null;

  return (
    <div className="flex flex-col gap-2" data-testid={`asset-field-${type}`}>
      <LiveRegion message={busy ? `Uploading ${label}…` : uploadedName ? `${label} uploaded.` : remove.isSuccess ? `${label} removed.` : ""} />
      <label htmlFor={inputId} className="text-sm font-medium text-foreground">
        {label}
      </label>
      <div
        className="relative flex h-28 items-center justify-center overflow-hidden rounded-lg border border-dashed border-border bg-[repeating-conic-gradient(#f1f5f9_0%_25%,#ffffff_0%_50%)] bg-[length:16px_16px] p-3"
      >
        {current.isPending ? (
          <Skeleton className="h-full w-full" />
        ) : current.isError ? (
          <div className="flex flex-col items-center gap-2 text-center text-xs text-destructive" role="alert">
            {errorMessage(current.error, `Couldn't load the current ${label.toLowerCase()}.`)}
            <Button type="button" variant="outline" size="xs" onClick={() => void current.refetch()}>
              <RefreshCw aria-hidden="true" /> Retry
            </Button>
          </div>
        ) : asset ? (
          // eslint-disable-next-line @next/next/no-img-element -- signed, short-lived preview URL
          <img
            src={asset.previewUrl}
            alt={`Current ${label.toLowerCase()}`}
            className={cn("max-h-full object-contain", previewShape === "square" ? "size-16" : "max-w-full")}
          />
        ) : (
          <span className="flex flex-col items-center gap-1 text-xs text-muted-foreground">
            <ImageIcon className="size-6" aria-hidden="true" />
            Not uploaded
          </span>
        )}
        {busy ? (
          <span className="absolute inset-0 flex items-center justify-center bg-background/70">
            <Loader2 className="size-5 animate-spin text-primary" aria-hidden="true" />
          </span>
        ) : null}
      </div>
      <p id={guidanceId} className="text-xs text-muted-foreground">
        {guidance}
      </p>
      <input
        ref={input}
        id={inputId}
        type="file"
        accept="image/png,image/jpeg"
        className="sr-only"
        disabled={!canManage || busy}
        aria-describedby={guidanceId}
        onChange={(event) => {
          void choose(event.target.files?.[0]);
          event.target.value = "";
        }}
      />
      {canManage ? (
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="outline" size="sm" disabled={busy || current.isPending} onClick={() => input.current?.click()}>
            {asset ? <RefreshCw aria-hidden="true" /> : <UploadCloud aria-hidden="true" />}
            {checking ? "Checking…" : upload.isPending ? "Uploading…" : asset ? "Change" : "Upload"}
          </Button>
          {asset ? (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              disabled={busy || remove.isPending}
              aria-label={`Remove ${label.toLowerCase()}`}
              onClick={() => void remove.mutateAsync(asset).catch(() => undefined)}
            >
              <Trash2 aria-hidden="true" /> {remove.isPending ? "Removing…" : "Remove"}
            </Button>
          ) : null}
        </div>
      ) : null}
      {uploadedName && !upload.isPending ? <p className="text-xs text-foreground">Uploaded and saved.</p> : null}
      <CheckMessages check={check} uploadError={upload.error} />
      {remove.error ? (
        <p role="alert" className="text-xs text-destructive">
          {errorMessage(remove.error, `Couldn't remove the ${label.toLowerCase()}.`)}
        </p>
      ) : null}
    </div>
  );
}

/**
 * Hero banner gallery: thumbnails with Delete, plus an "Upload banner" tile
 * (hidden once the 6-banner cap is reached). Uses `GET /tenant-assets?type=`.
 */
export function BannerGallery({
  label,
  type,
  guidance,
  canManage,
}: {
  label: string;
  type: "DESKTOP_HERO_BANNER" | "MOBILE_HERO_BANNER";
  guidance: string;
  canManage: boolean;
}) {
  const inputId = useId();
  const guidanceId = useId();
  const headingId = useId();
  const list = useTenantAssetList(type);
  const remove = useDeleteTenantAsset();
  const [deleting, setDeleting] = useState<string | null>(null);
  const { upload, check, checking, uploadedName, choose, busy } = useCheckedUpload(type);
  const banners: TenantAsset[] = list.data ?? [];
  const aspect = type === "DESKTOP_HERO_BANNER" ? "aspect-[4/1]" : "aspect-[8/3]";
  const kind = type === "DESKTOP_HERO_BANNER" ? "desktop" : "mobile";

  const onDelete = async (asset: TenantAsset) => {
    setDeleting(asset.id);
    try {
      await remove.mutateAsync(asset);
    } catch {
      // Rendered from `remove.error`.
    } finally {
      setDeleting(null);
    }
  };

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3" data-testid={`banner-gallery-${kind}`}>
      <LiveRegion message={busy ? "Uploading banner…" : uploadedName ? "Banner uploaded." : ""} />
      <h3 id={headingId} className="text-sm font-medium text-foreground">
        {label}
      </h3>
      {list.isPending ? (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          <Skeleton className={cn("w-full rounded-lg", aspect)} />
          <Skeleton className={cn("w-full rounded-lg", aspect)} />
        </div>
      ) : list.isError ? (
        <div role="alert" className="flex flex-wrap items-center gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">
          {errorMessage(list.error, "Couldn't load banners.")}
          <Button type="button" variant="outline" size="sm" onClick={() => void list.refetch()}>
            Retry
          </Button>
        </div>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {banners.map((banner, index) => (
            <li key={banner.id} className={cn("group relative overflow-hidden rounded-lg border border-border bg-muted", aspect)}>
              {/* eslint-disable-next-line @next/next/no-img-element -- signed, short-lived preview URL */}
              <img src={banner.previewUrl} alt={`${label} ${index + 1}`} className="absolute inset-0 size-full object-cover" />
              {canManage ? (
                <Button
                  type="button"
                  size="sm"
                  variant="destructive"
                  className="absolute right-2 top-2 bg-background/95 shadow-sm hover:bg-background"
                  disabled={deleting === banner.id}
                  aria-label={`Delete ${kind} banner ${index + 1}`}
                  onClick={() => void onDelete(banner)}
                >
                  <Trash2 aria-hidden="true" /> {deleting === banner.id ? "Deleting…" : "Delete"}
                </Button>
              ) : null}
            </li>
          ))}
          {canManage && banners.length < MAX_BANNERS_PER_TYPE ? (
            <li className={cn("relative", aspect, "min-h-24")}>
              <label
                htmlFor={inputId}
                className={cn(
                  "flex size-full cursor-pointer flex-col items-center justify-center gap-1.5 rounded-lg border-2 border-dashed border-primary/40 bg-primary/5 text-sm font-medium text-primary transition-colors hover:bg-primary/10 has-focus-visible:ring-3 has-focus-visible:ring-ring/50",
                  busy && "cursor-progress opacity-70"
                )}
              >
                {busy ? <Loader2 className="size-5 animate-spin" aria-hidden="true" /> : <ImagePlus className="size-5" aria-hidden="true" />}
                {checking ? "Checking…" : upload.isPending ? "Uploading…" : "Upload banner"}
                <input
                  id={inputId}
                  type="file"
                  accept="image/png,image/jpeg"
                  className="sr-only"
                  disabled={busy}
                  aria-describedby={guidanceId}
                  onChange={(event) => {
                    void choose(event.target.files?.[0]);
                    event.target.value = "";
                  }}
                />
              </label>
            </li>
          ) : null}
        </ul>
      )}
      {!list.isPending && !list.isError && banners.length === 0 && !canManage ? (
        <p className="text-sm text-muted-foreground">No {kind} banners uploaded. The public page shows a branded hero instead.</p>
      ) : null}
      {canManage && banners.length >= MAX_BANNERS_PER_TYPE ? (
        <p className="text-xs text-muted-foreground">Maximum of {MAX_BANNERS_PER_TYPE} banners reached. Delete one to upload another.</p>
      ) : null}
      <p id={guidanceId} className="text-xs text-muted-foreground">
        {guidance}
      </p>
      <CheckMessages check={check} uploadError={upload.error} />
      {remove.error ? (
        <p role="alert" className="text-xs text-destructive">
          {errorMessage(remove.error, "Couldn't delete the banner.")}
        </p>
      ) : null}
    </section>
  );
}
