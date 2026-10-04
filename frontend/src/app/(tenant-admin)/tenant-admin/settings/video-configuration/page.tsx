"use client";

import { useState } from "react";
import Link from "next/link";
import { Controller, useForm, useWatch, type FieldPath } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  useTenantVideoConfiguration,
  useUpdateTenantVideoConfiguration,
  type TenantVideoConfigurationView,
  type WatermarkField,
} from "@/lib/api/video-providers";
import {
  WATERMARK_FIELDS,
  toTenantVideoSettings,
  toVideoConfigurationFormValues,
  videoConfigurationSchema,
  type VideoConfigurationFormValues,
} from "@/lib/validation/video-configuration";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const SELECT_CLASS =
  "h-8 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30";

/**
 * Tenant Admin → Institute configuration → Video settings (VIDEO Phase 3).
 * Tenant-wide rules for lesson videos. They are stored now and enforced by
 * the playback phases; the device limit itself stays under Devices & access.
 * A Read-only Auditor sees the values without the controls; the backend
 * re-checks every save.
 */
export default function VideoConfigurationPage() {
  const { session } = useAuth();
  const canManage = canManageIntegrations(session?.role ?? null);
  const query = useTenantVideoConfiguration();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <Link
          href="/tenant-admin/settings"
          className="text-sm font-medium text-muted-foreground hover:text-foreground hover:underline"
        >
          ← Institute configuration
        </Link>
        <h1 className="mt-2 text-xl font-semibold text-foreground">Video settings</h1>
        <p className="text-sm text-muted-foreground">
          How lesson videos are uploaded and played across your institute. The number of devices a student may use is
          set under{" "}
          <Link href="/tenant-admin/settings/devices-access" className="underline hover:text-foreground">
            Devices &amp; access
          </Link>
          .
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading video settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(view) => <VideoConfigurationForm view={view} canManage={canManage} />}
      </QueryStateBoundary>
    </div>
  );
}

function VideoConfigurationForm({ view, canManage }: { view: TenantVideoConfigurationView; canManage: boolean }) {
  const mutation = useUpdateTenantVideoConfiguration();
  const [notice, setNotice] = useState<string | null>(null);
  const [pageError, setPageError] = useState<string | null>(null);
  const defaultValues = toVideoConfigurationFormValues(view.settings);
  const fieldNames = new Set<string>(Object.keys(defaultValues));
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<VideoConfigurationFormValues>({
    resolver: zodResolver(videoConfigurationSchema),
    defaultValues,
  });
  const watchTimePolicy = useWatch({ control, name: "watchTimePolicy" });
  const watermarkEnabled = useWatch({ control, name: "watermarkEnabled" });
  const captureProtectionEnabled = useWatch({ control, name: "captureProtectionEnabled" });

  const onSubmit = handleSubmit(async (values) => {
    setNotice(null);
    setPageError(null);
    try {
      await mutation.mutateAsync(toTenantVideoSettings(values));
      setNotice("Video settings saved.");
    } catch (error) {
      if (isApiClientError(error)) {
        for (const fieldError of error.fieldErrors) {
          if (fieldNames.has(fieldError.field)) {
            setError(fieldError.field as FieldPath<VideoConfigurationFormValues>, {
              type: "server",
              message: fieldError.message,
            });
          }
        }
      }
      setPageError(isApiClientError(error) ? error.message : "The video settings could not be saved.");
    }
  });

  const fieldError = (name: keyof VideoConfigurationFormValues) => {
    const message = errors[name]?.message;
    return message ? (
      <p id={`video-${name}-error`} role="alert" className="text-xs text-destructive">
        {String(message)}
      </p>
    ) : null;
  };

  const numberField = (
    name: keyof VideoConfigurationFormValues,
    label: string,
    hint: string,
    attrs: { min: number; max: number; step?: string }
  ) => (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={`video-${name}`}>{label}</Label>
      <Input
        id={`video-${name}`}
        type="number"
        inputMode={attrs.step ? "decimal" : "numeric"}
        min={attrs.min}
        max={attrs.max}
        step={attrs.step}
        aria-invalid={errors[name] ? true : undefined}
        aria-describedby={errors[name] ? `video-${name}-error` : `video-${name}-hint`}
        {...register(name)}
      />
      <p id={`video-${name}-hint`} className="text-xs text-muted-foreground">
        {hint}
      </p>
      {fieldError(name)}
    </div>
  );

  return (
    <form onSubmit={onSubmit} noValidate className="flex max-w-3xl flex-col gap-6">
      <p className="text-sm text-muted-foreground">
        {view.customized
          ? `Last changed ${view.updatedAt ? formatDateTime(view.updatedAt) : ""}.`
          : "These are the platform defaults. Save to make them your institute's own."}
      </p>

      <fieldset disabled={!canManage} className="flex flex-col gap-6">
        <Card>
          <CardHeader>
            <CardTitle>Uploads</CardTitle>
            <CardDescription>Where new lesson videos go.</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="video-defaultProvider">Store new videos on</Label>
              <select id="video-defaultProvider" className={SELECT_CLASS} {...register("defaultProvider")}>
                <option value="INTERNAL">This platform&apos;s storage</option>
                <option value="YOUTUBE">Your YouTube channel</option>
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="video-defaultPrivacyStatus">YouTube visibility</Label>
              <select id="video-defaultPrivacyStatus" className={SELECT_CLASS} {...register("defaultPrivacyStatus")}>
                <option value="UNLISTED">Unlisted (playable in lessons)</option>
                <option value="PRIVATE">Private (not playable in lessons)</option>
              </select>
            </div>
            <label className="flex items-start gap-2 text-sm text-foreground sm:col-span-2">
              <input type="checkbox" className="mt-0.5 size-4" {...register("uploadsEnabled")} />
              <span>Allow teachers and staff to upload videos</span>
            </label>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Player &amp; protection</CardTitle>
            <CardDescription>
              Watermarks and screen-capture protection deter copying; they can&apos;t make copying impossible.
            </CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <label className="flex items-start gap-2 text-sm text-foreground sm:col-span-2">
              <input type="checkbox" className="mt-0.5 size-4" {...register("nativePlayerRequired")} />
              <span>
                Require the AOC Secure Player app
                <span className="block text-xs text-muted-foreground">
                  Students must open lessons in the desktop player instead of the browser.
                </span>
              </span>
            </label>
            <label className="flex items-start gap-2 text-sm text-foreground sm:col-span-2">
              <input type="checkbox" className="mt-0.5 size-4" {...register("watermarkEnabled")} />
              <span>Show a moving watermark with student details</span>
            </label>
            {watermarkEnabled ? (
              <>
                {numberField("watermarkOpacity", "Watermark opacity", "From 0.05 (faint) to 0.60.", {
                  min: 0.05,
                  max: 0.6,
                  step: "0.05",
                })}
                {numberField("watermarkMovementIntervalSeconds", "Move every (seconds)", "Between 5 and 120.", {
                  min: 5,
                  max: 120,
                })}
                <fieldset className="flex flex-col gap-2 sm:col-span-2" aria-describedby="video-watermarkFields-error">
                  <legend className="text-sm font-medium text-foreground">Show in the watermark</legend>
                  <Controller
                    control={control}
                    name="watermarkFields"
                    render={({ field }) => (
                      <div className="flex flex-wrap gap-x-6 gap-y-2">
                        {WATERMARK_FIELDS.map((option) => (
                          <label key={option.value} className="flex items-center gap-2 text-sm text-foreground">
                            <input
                              type="checkbox"
                              className="size-4"
                              checked={field.value.includes(option.value)}
                              onChange={(event) => {
                                const next: WatermarkField[] = event.target.checked
                                  ? [...field.value, option.value]
                                  : field.value.filter((value) => value !== option.value);
                                field.onChange(next);
                              }}
                            />
                            {option.label}
                          </label>
                        ))}
                      </div>
                    )}
                  />
                  {fieldError("watermarkFields")}
                </fieldset>
              </>
            ) : null}
            <label className="flex items-start gap-2 text-sm text-foreground sm:col-span-2">
              <input type="checkbox" className="mt-0.5 size-4" {...register("captureProtectionEnabled")} />
              <span>
                Block screen recording where the device supports it
                <span className="block text-xs text-muted-foreground">
                  Works in the AOC Secure Player on Windows. External cameras and capture hardware are not blocked.
                </span>
              </span>
            </label>
            {captureProtectionEnabled ? (
              <div className="flex flex-col gap-1 sm:col-span-2">
                <label className="flex items-start gap-2 text-sm text-foreground">
                  <input
                    type="checkbox"
                    className="mt-0.5 size-4"
                    aria-invalid={errors.captureProtectionRequired ? true : undefined}
                    {...register("captureProtectionRequired")}
                  />
                  <span>Refuse to play on devices where this protection can&apos;t be turned on</span>
                </label>
                {fieldError("captureProtectionRequired")}
              </div>
            ) : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Viewing limits</CardTitle>
            <CardDescription>Seeking forward never counts as watched time.</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {numberField("maxConcurrentSessions", "Videos playing at once per student", "Between 1 and 5.", {
              min: 1,
              max: 5,
            })}
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="video-watchTimePolicy">Watch-time limit per video</Label>
              <select id="video-watchTimePolicy" className={SELECT_CLASS} {...register("watchTimePolicy")}>
                <option value="NONE">No limit</option>
                <option value="VIDEO_DURATION_MULTIPLIER">A multiple of the video&apos;s length</option>
                <option value="FIXED_MINUTES">A fixed number of minutes</option>
              </select>
            </div>
            {watchTimePolicy === "VIDEO_DURATION_MULTIPLIER"
              ? numberField("watchTimeMultiplier", "Multiplier", "For example 1.5 = one and a half times the length.", {
                  min: 1,
                  max: 10,
                  step: "0.1",
                })
              : null}
            {watchTimePolicy === "FIXED_MINUTES"
              ? numberField("watchTimeFixedMinutes", "Minutes per video", "Between 1 and 10000.", { min: 1, max: 10000 })
              : null}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Advanced</CardTitle>
            <CardDescription>Timing used by the secure player. The defaults suit most institutes.</CardDescription>
          </CardHeader>
          <CardContent className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {numberField("launchCodeTtlSeconds", "Player link lifetime (seconds)", "Between 15 and 60.", {
              min: 15,
              max: 60,
            })}
            {numberField("heartbeatIntervalSeconds", "Player check-in interval (seconds)", "Between 10 and 60.", {
              min: 10,
              max: 60,
            })}
          </CardContent>
        </Card>
      </fieldset>

      {pageError ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{pageError}</AlertDescription>
        </Alert>
      ) : null}
      <div role="status" aria-live="polite">
        {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
      </div>
      {canManage ? (
        <Button type="submit" className="w-full sm:w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save video settings"}
        </Button>
      ) : (
        <p className="text-sm text-muted-foreground">Only a Tenant Admin can change these settings.</p>
      )}
    </form>
  );
}
