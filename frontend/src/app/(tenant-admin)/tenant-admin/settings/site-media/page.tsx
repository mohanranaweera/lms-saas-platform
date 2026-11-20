"use client";

import Link from "next/link";
import { createContext, useContext, useState } from "react";
import { ArrowRight, ImageIcon, PlayCircle, Trash2, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ConfirmDeleteDialog, mutationErrorMessage } from "@/components/courses/admin/confirm-delete-dialog";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/states/error-state";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig, canManageTeachers, canViewTeachers } from "@/lib/auth/permissions";
import { useDeleteTenantAsset, useTenantAsset, useTenantAssetList, type TenantAssetType } from "@/lib/api/tenant-assets";
import { useRemoveTeacherPhoto, useTeachers } from "@/lib/api/teachers";
import { useTenantConfigDomain, useUpdateTenantConfigDomain } from "@/lib/api/tenant-config";

/**
 * Tenant Admin → Settings → Site media. Every file the institute uses on its
 * site rather than in courses, in one inventory: logos, favicon, receipt logo,
 * hero banners, teacher profile photos and the support video. Site files are
 * filed by purpose when uploaded, so none of them appear in the Media Library
 * or can be attached to lessons. Each one is changed where it is used, and
 * can be deleted here (after a confirmation); the backend re-checks every delete.
 */

/** One pending, confirmed-before-running delete shared by every section. */
interface PendingDelete {
  title: string;
  description: string;
  confirmLabel: string;
  run: () => Promise<unknown>;
}

const ConfirmContext = createContext<(request: PendingDelete) => void>(() => undefined);

/** Which deletes this viewer may start (UX only; the server decides). */
const EditRights = createContext({ site: false, teachers: false });

function DeleteButton({ label, request }: { label: string; request: PendingDelete }) {
  const confirm = useContext(ConfirmContext);
  return (
    <Button type="button" variant="ghost" size="sm" className="w-fit text-destructive hover:text-destructive" onClick={() => confirm(request)}>
      <Trash2 aria-hidden="true" />
      {label}
    </Button>
  );
}
const SINGLE_ASSETS: { type: TenantAssetType; label: string }[] = [
  { type: "SITE_LOGO", label: "Institute logo" },
  { type: "SITE_FAVICON", label: "Website favicon" },
  { type: "RECEIPT_LOGO", label: "Payment receipt logo" },
];

const APPEARANCE = "/tenant-admin/settings/appearance";

export default function SiteMediaPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const showTeachers = canViewTeachers(role);
  const [pending, setPending] = useState<PendingDelete | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const confirm = (request: PendingDelete) => {
    setError(null);
    setPending(request);
  };
  const onConfirm = async () => {
    if (!pending) return;
    setRunning(true);
    setError(null);
    try {
      await pending.run();
      setPending(null);
    } catch (failure) {
      setError(mutationErrorMessage(failure));
    } finally {
      setRunning(false);
    }
  };
  return (
    <ConfirmContext.Provider value={confirm}>
      <EditRights.Provider value={{ site: canManageInstituteConfig(role), teachers: canManageTeachers(role) }}>
    <div className="flex flex-col gap-6">
      <SettingsHeader
        active="/tenant-admin/settings/site-media"
        description="Images and videos used on your site rather than in courses. They never appear in the Media Library."
      />
      <SectionCard
        title="Branding images"
        description="Shown on your public pages, portals and receipts."
        manageHref={APPEARANCE}
        manageLabel="Manage in Appearance"
      >
        <ul className="grid gap-4 sm:grid-cols-3">
          {SINGLE_ASSETS.map((asset) => (
            <SingleAssetTile key={asset.type} type={asset.type} label={asset.label} />
          ))}
        </ul>
      </SectionCard>
      <SectionCard
        title="Hero banners"
        description="The rotating banners at the top of your public home page."
        manageHref={APPEARANCE}
        manageLabel="Manage in Appearance"
      >
        <div className="flex flex-col gap-5">
          <BannerRow type="DESKTOP_HERO_BANNER" label="Desktop" />
          <BannerRow type="MOBILE_HERO_BANNER" label="Mobile" />
        </div>
      </SectionCard>
      {showTeachers ? <TeacherPhotos /> : null}
      <SupportVideo />
      <ConfirmDeleteDialog
        open={pending !== null}
        onOpenChange={(open) => {
          if (!open && !running) setPending(null);
        }}
        title={pending?.title ?? ""}
        description={pending?.description ?? ""}
        confirmLabel={pending?.confirmLabel ?? "Delete"}
        pending={running}
        error={error}
        onConfirm={() => void onConfirm()}
      />
    </div>
      </EditRights.Provider>
    </ConfirmContext.Provider>
  );
}

function SectionCard({
  title,
  description,
  manageHref,
  manageLabel,
  children,
}: {
  title: string;
  description: string;
  manageHref: string;
  manageLabel: string;
  children: React.ReactNode;
}) {
  return (
    <Card>
      <CardHeader className="flex flex-row flex-wrap items-start justify-between gap-3">
        <div>
          <CardTitle>
            <h2 className="text-base font-semibold">{title}</h2>
          </CardTitle>
          <CardDescription>{description}</CardDescription>
        </div>
        <Link href={manageHref} className="inline-flex items-center gap-1 text-sm font-medium text-primary hover:underline">
          {manageLabel}
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </CardHeader>
      <CardContent>{children}</CardContent>
    </Card>
  );
}

function Thumb({ url, alt, wide = false }: { url: string | null | undefined; alt: string; wide?: boolean }) {
  return (
    <div className={`flex items-center justify-center overflow-hidden rounded-md border border-border bg-muted/40 ${wide ? "aspect-[4/1]" : "h-24"}`}>
      {url ? (
        // eslint-disable-next-line @next/next/no-img-element -- short-lived signed tenant asset URL
        <img src={url} alt={alt} className={wide ? "size-full object-cover" : "max-h-full max-w-full object-contain p-2"} />
      ) : (
        <span className="flex flex-col items-center gap-1 text-xs text-muted-foreground">
          <ImageIcon className="size-5" aria-hidden="true" />
          Not uploaded
        </span>
      )}
    </div>
  );
}

function SingleAssetTile({ type, label }: { type: TenantAssetType; label: string }) {
  const asset = useTenantAsset(type);
  const remove = useDeleteTenantAsset();
  const rights = useContext(EditRights);
  const current = asset.data;
  return (
    <li className="flex flex-col gap-2">
      <span className="text-sm font-medium">{label}</span>
      {asset.isPending ? <Skeleton className="h-24 w-full" /> : <Thumb url={current?.previewUrl} alt={label} />}
      {rights.site && current ? (
        <DeleteButton
          label={`Delete ${label.toLowerCase()}`}
          request={{
            title: `Delete the ${label.toLowerCase()}?`,
            description: "It disappears from your site straight away. You can upload a new one in Appearance.",
            confirmLabel: "Delete",
            run: () => remove.mutateAsync(current),
          }}
        />
      ) : null}
    </li>
  );
}

function BannerRow({ type, label }: { type: TenantAssetType; label: string }) {
  const list = useTenantAssetList(type);
  const remove = useDeleteTenantAsset();
  const rights = useContext(EditRights);
  return (
    <div className="flex flex-col gap-2">
      <span className="text-sm font-medium">
        {label} {list.data ? <span className="text-muted-foreground">({list.data.length})</span> : null}
      </span>
      {list.isPending ? (
        <Skeleton className="h-20 w-full" />
      ) : list.isError ? (
        <ErrorState message={`Couldn't load ${label.toLowerCase()} banners.`} onRetry={() => void list.refetch()} />
      ) : list.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No {label.toLowerCase()} banners uploaded.</p>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {list.data.map((banner, index) => (
            <li key={banner.id} className="flex flex-col gap-1">
              <Thumb url={banner.previewUrl} alt={`${label} banner ${index + 1}`} wide />
              {rights.site ? (
                <DeleteButton
                  label={`Delete ${label.toLowerCase()} banner ${index + 1}`}
                  request={{
                    title: `Delete ${label.toLowerCase()} banner ${index + 1}?`,
                    description: "It is removed from your home page straight away.",
                    confirmLabel: "Delete banner",
                    run: () => remove.mutateAsync(banner),
                  }}
                />
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function TeacherPhotos() {
  const teachers = useTeachers({ size: 100 });
  const removePhoto = useRemoveTeacherPhoto();
  const rights = useContext(EditRights);
  return (
    <SectionCard
      title="Teacher profile photos"
      description="Shown on teacher cards and public profiles. Change a photo from the teacher's profile."
      manageHref="/tenant-admin/teachers"
      manageLabel="Open Teachers"
    >
      {teachers.isPending ? (
        <Skeleton className="h-24 w-full" />
      ) : teachers.isError ? (
        <ErrorState message="Couldn't load teachers." onRetry={() => void teachers.refetch()} />
      ) : teachers.data.length === 0 ? (
        <p className="text-sm text-muted-foreground">No teachers yet. Photos appear here once you add teachers with a profile photo.</p>
      ) : (
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {teachers.data.map((teacher) => (
            <li key={teacher.id} className="flex flex-col gap-1">
              <Link
                href={`/tenant-admin/teachers/${teacher.id}`}
                className="flex items-center gap-3 rounded-lg border border-border p-3 transition-colors hover:bg-muted/40"
              >
                <span className="flex size-12 shrink-0 items-center justify-center overflow-hidden rounded-full bg-muted">
                  {teacher.profileImageUrl ? (
                    // eslint-disable-next-line @next/next/no-img-element -- short-lived signed URL
                    <img src={teacher.profileImageUrl} alt="" className="size-full object-cover" />
                  ) : (
                    <UserRound className="size-6 text-muted-foreground" aria-hidden="true" />
                  )}
                </span>
                <span className="min-w-0">
                  <span className="block truncate text-sm font-medium">{teacher.name}</span>
                  <span className="block text-xs text-muted-foreground">{teacher.profileImageUrl ? "Photo set" : "No photo"}</span>
                </span>
              </Link>
              {rights.teachers && teacher.profileImageUrl ? (
                <DeleteButton
                  label={`Remove ${teacher.name} photo`}
                  request={{
                    title: `Remove ${teacher.name}'s photo?`,
                    description: "Their cards and public profile show initials instead. The photo file is deleted.",
                    confirmLabel: "Remove photo",
                    run: () => removePhoto.mutateAsync(teacher.id),
                  }}
                />
              ) : null}
            </li>
          ))}
        </ul>
      )}
    </SectionCard>
  );
}

function SupportVideo() {
  const branding = useTenantConfigDomain("BRANDING");
  const update = useUpdateTenantConfigDomain("BRANDING");
  const rights = useContext(EditRights);
  const url = branding.data?.find((property) => property.key === "support_video_url")?.value;
  return (
    <SectionCard
      title="Support page video"
      description="The YouTube video on your public Support section. Course videos live in the Media Library."
      manageHref={APPEARANCE}
      manageLabel="Manage in Appearance"
    >
      {branding.isPending ? (
        <Skeleton className="h-6 w-64" />
      ) : typeof url === "string" && url ? (
        <div className="flex flex-wrap items-center gap-3">
          <a href={url} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 break-all text-sm text-primary hover:underline">
            <PlayCircle className="size-4 shrink-0" aria-hidden="true" />
            {url}
          </a>
          {rights.site ? (
            <DeleteButton
              label="Remove support video"
              request={{
                title: "Remove the support video?",
                description: "The Support section of your home page no longer shows a video.",
                confirmLabel: "Remove video",
                run: () => update.mutateAsync({ support_video_url: null }),
              }}
            />
          ) : null}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">No support video set.</p>
      )}
    </SectionCard>
  );
}
