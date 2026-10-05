"use client";

import { useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  useRemoveTenantGoogleApp,
  useSaveTenantGoogleApp,
  useVerifyTenantGoogleApp,
  type TenantGoogleAppView,
} from "@/lib/api/video-providers";
import { googleOAuthAppSchema, type GoogleOAuthAppFormValues } from "@/lib/validation/google-oauth-app";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  AlertDialog,
  AlertDialogClose,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "@/components/ui/alert-dialog";

const SOURCE_LABEL: Record<TenantGoogleAppView["source"], string> = {
  TENANT: "Your institute's Google app",
  PLATFORM: "Platform Google app",
  NOT_CONFIGURED: "No Google app",
};

function errorText(error: unknown, fallback: string): string {
  return isApiClientError(error) ? error.message : fallback;
}

/**
 * VIDEO Phase 5c (ADR-018 Amendment 5 as revised 2026-10-05): the Google
 * OAuth app this institute's YouTube channel connects through - its own, or
 * the platform's when it has none. The secret is write-only (never sent
 * back), so it is re-entered on every save. Changing the app means the
 * channel must be connected again; the backend marks it for reconnect.
 */
export function TenantGoogleAppSection({ view, canManage }: { view: TenantGoogleAppView; canManage: boolean }) {
  const ids = { clientId: useId(), secret: useId(), ack: useId() };
  const save = useSaveTenantGoogleApp();
  const verify = useVerifyTenantGoogleApp();
  const [saved, setSaved] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const {
    register,
    handleSubmit,
    setError,
    resetField,
    control,
    formState: { errors },
  } = useForm<GoogleOAuthAppFormValues>({
    resolver: zodResolver(googleOAuthAppSchema),
    defaultValues: { clientId: view.clientId ?? "", clientSecret: "" },
  });
  const typedClientId = useWatch({ control, name: "clientId" }).trim();
  // Any app other than the one the channel was connected with needs a reconnect.
  const needsReconnectAck = view.channelConnected && typedClientId !== "" && typedClientId !== (view.clientId ?? "");
  const serverFieldError = isApiClientError(save.error) && save.error.fieldErrors.length > 0;

  const onSubmit = handleSubmit((values) => {
    setSaved(false);
    verify.reset();
    if (needsReconnectAck && !acknowledged) return;
    save.mutate(values, {
      onSuccess: () => {
        setSaved(true);
        setAcknowledged(false);
        resetField("clientSecret", { defaultValue: "" });
      },
      onError: (error) => {
        if (!isApiClientError(error)) return;
        for (const fieldError of error.fieldErrors) {
          if (fieldError.field === "clientId" || fieldError.field === "clientSecret") {
            setError(fieldError.field, { message: fieldError.message });
          }
        }
      },
    });
  });

  return (
    <section aria-labelledby="tenant-google-app-heading" className="flex flex-col gap-4 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="tenant-google-app-heading" className="text-base font-medium text-foreground">
          Google app
        </h2>
        <Badge variant={view.source === "TENANT" ? "default" : view.source === "PLATFORM" ? "secondary" : "outline"}>
          {SOURCE_LABEL[view.source]}
        </Badge>
      </div>
      <p className="text-sm text-muted-foreground">
        {view.source === "TENANT"
          ? "Your YouTube channel connects through your institute's own Google Cloud app."
          : view.source === "PLATFORM"
            ? "Your YouTube channel connects through the platform's Google app. You can use your institute's own Google Cloud app instead."
            : "No Google app is set up yet. Add your institute's own Google Cloud app to connect YouTube."}
      </p>

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        {view.source === "TENANT" ? (
          <>
            <dt className="text-muted-foreground">Client ID</dt>
            <dd className="break-all text-foreground">{view.clientId}</dd>
            <dt className="text-muted-foreground">Client secret</dt>
            <dd className="text-foreground">Saved (hidden)</dd>
            {view.updatedAt ? (
              <>
                <dt className="text-muted-foreground">Last saved</dt>
                <dd className="text-foreground">{formatDateTime(view.updatedAt)}</dd>
              </>
            ) : null}
          </>
        ) : null}
        <dt className="text-muted-foreground">Redirect URI</dt>
        <dd className="break-all text-foreground">
          {view.redirectUri ? (
            <code className="rounded bg-muted px-1.5 py-0.5 text-xs">{view.redirectUri}</code>
          ) : (
            <span className="text-muted-foreground">Not available on this platform yet.</span>
          )}
        </dd>
      </dl>

      {canManage ? (
        <form aria-label="Your Google app" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          {!view.encryptionAvailable ? (
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>Google credentials can&apos;t be saved on this platform right now.</AlertDescription>
            </Alert>
          ) : null}
          <p className="text-sm text-muted-foreground">
            In Google Cloud, create an OAuth client of type &quot;Web application&quot;, add the redirect URI above and
            enable the YouTube Data API v3.
          </p>
          <fieldset disabled={!view.encryptionAvailable || save.isPending} className="flex flex-col gap-4">
            <legend className="sr-only">Your Google app credentials</legend>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={ids.clientId}>Client ID</Label>
              <Input
                id={ids.clientId}
                autoComplete="off"
                spellCheck={false}
                placeholder="1234567890-abc.apps.googleusercontent.com"
                aria-invalid={Boolean(errors.clientId)}
                aria-describedby={errors.clientId ? `${ids.clientId}-error` : undefined}
                {...register("clientId")}
              />
              {errors.clientId ? (
                <p id={`${ids.clientId}-error`} className="text-sm text-destructive">
                  {errors.clientId.message}
                </p>
              ) : null}
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor={ids.secret}>Client secret</Label>
              <Input
                id={ids.secret}
                type="password"
                autoComplete="new-password"
                spellCheck={false}
                aria-invalid={Boolean(errors.clientSecret)}
                aria-describedby={`${ids.secret}-hint${errors.clientSecret ? ` ${ids.secret}-error` : ""}`}
                {...register("clientSecret")}
              />
              <p id={`${ids.secret}-hint`} className="text-xs text-muted-foreground">
                Never shown again after saving. Enter it each time you save.
              </p>
              {errors.clientSecret ? (
                <p id={`${ids.secret}-error`} className="text-sm text-destructive">
                  {errors.clientSecret.message}
                </p>
              ) : null}
            </div>
            {needsReconnectAck ? (
              <Alert variant="destructive">
                <AlertCircle aria-hidden="true" />
                <AlertDescription className="flex flex-col gap-2">
                  <span>
                    Your YouTube channel was connected through a different Google app. After saving, connect the
                    channel again before uploading.
                  </span>
                  <label htmlFor={ids.ack} className="flex items-center gap-2 font-medium">
                    <input
                      id={ids.ack}
                      type="checkbox"
                      checked={acknowledged}
                      onChange={(event) => setAcknowledged(event.target.checked)}
                    />
                    I understand the channel must be connected again
                  </label>
                </AlertDescription>
              </Alert>
            ) : null}
          </fieldset>

          {save.isError && !serverFieldError ? (
            <Alert variant="destructive" role="alert">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>{errorText(save.error, "The Google app could not be saved.")}</AlertDescription>
            </Alert>
          ) : null}
          {saved && !save.isPending ? (
            <p role="status" className="text-sm text-muted-foreground">
              Google app saved. Use Verify to check it with Google.
            </p>
          ) : null}

          <div className="flex flex-wrap gap-2">
            <Button
              type="submit"
              disabled={!view.encryptionAvailable || save.isPending || (needsReconnectAck && !acknowledged)}
              aria-busy={save.isPending}
            >
              {save.isPending ? "Saving…" : view.source === "TENANT" ? "Save Google app" : "Use my Google app"}
            </Button>
            {view.source !== "NOT_CONFIGURED" ? (
              <Button
                type="button"
                variant="outline"
                onClick={() => verify.mutate()}
                disabled={verify.isPending}
                aria-busy={verify.isPending}
              >
                {verify.isPending ? "Verifying…" : "Verify with Google"}
              </Button>
            ) : null}
            {view.source === "TENANT" ? (
              <RemoveDialog platformAppAvailable={view.platformAppAvailable} channelConnected={view.channelConnected} />
            ) : null}
          </div>
          {verify.isSuccess ? (
            <p
              role={verify.data.result === "VALID" ? "status" : "alert"}
              className={verify.data.result === "VALID" ? "text-sm text-foreground" : "text-sm text-destructive"}
            >
              {verify.data.message}
            </p>
          ) : null}
          {verify.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {errorText(verify.error, "Google could not be reached. Try again later.")}
            </p>
          ) : null}
        </form>
      ) : (
        <p className="text-sm text-muted-foreground">Only a Tenant Admin can change the Google app.</p>
      )}
    </section>
  );
}

function RemoveDialog({
  platformAppAvailable,
  channelConnected,
}: {
  platformAppAvailable: boolean;
  channelConnected: boolean;
}) {
  const remove = useRemoveTenantGoogleApp();
  const [open, setOpen] = useState(false);

  async function handleConfirm() {
    try {
      await remove.mutateAsync();
      setOpen(false);
    } catch {
      // Shown below.
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" variant="outline" />}>Remove my Google app</AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remove your Google app?</AlertDialogTitle>
          <AlertDialogDescription>
            {platformAppAvailable
              ? "Your institute will use the platform's Google app again."
              : "No Google app will be available, so YouTube can't be connected until you add one again."}
            {channelConnected ? " Your YouTube channel will need to be connected again." : ""}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {remove.isError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorText(remove.error, "The Google app could not be removed.")}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button
            type="button"
            variant="destructive"
            onClick={handleConfirm}
            disabled={remove.isPending}
            aria-busy={remove.isPending}
          >
            {remove.isPending ? "Removing…" : "Remove"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
