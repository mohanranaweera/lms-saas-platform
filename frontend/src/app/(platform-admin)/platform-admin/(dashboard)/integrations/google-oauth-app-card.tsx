"use client";

import { useId, useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, CheckCircle2, CircleSlash, Server } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  useRemoveGoogleOAuthApp,
  useSaveGoogleOAuthApp,
  useVerifyGoogleOAuthApp,
  type GoogleOAuthAppSource,
  type GoogleOAuthAppView,
} from "@/lib/api/platform-oauth-apps";
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

const SOURCE: Record<
  GoogleOAuthAppSource,
  { label: string; variant: "default" | "secondary" | "outline"; Icon: typeof CheckCircle2 }
> = {
  SAVED: { label: "Saved on this screen", variant: "default", Icon: CheckCircle2 },
  ENVIRONMENT: { label: "From environment variables", variant: "secondary", Icon: Server },
  NOT_CONFIGURED: { label: "Not configured", variant: "outline", Icon: CircleSlash },
};

function errorText(error: unknown, fallback: string): string {
  return isApiClientError(error) ? error.message : fallback;
}

/**
 * VIDEO Phase 5c (ADR-018 Amendment 5): the one Google OAuth app the whole
 * platform uses for institutes' YouTube channels. Institutes never enter
 * Google credentials; they only click Connect. The secret is write-only -
 * the server never sends it back, so it is re-entered on every save.
 */
export function GoogleOAuthAppCard({ view }: { view: GoogleOAuthAppView }) {
  const ids = { clientId: useId(), secret: useId(), ack: useId() };
  const save = useSaveGoogleOAuthApp();
  const verify = useVerifyGoogleOAuthApp();
  const [saved, setSaved] = useState(false);
  const [acknowledged, setAcknowledged] = useState(false);
  const [copied, setCopied] = useState(false);
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
  const typedClientId = useWatch({ control, name: "clientId" });
  // Google ties every connected channel to the client that connected it.
  const replacesClient = view.clientId !== null && typedClientId.trim() !== "" && typedClientId.trim() !== view.clientId;
  const serverFieldError = isApiClientError(save.error) && save.error.fieldErrors.length > 0;
  const { label, variant, Icon } = SOURCE[view.source];

  const onSubmit = handleSubmit((values) => {
    setSaved(false);
    verify.reset();
    if (replacesClient && !acknowledged) return;
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

  async function copyRedirectUri() {
    if (!view.redirectUri) return;
    try {
      await navigator.clipboard.writeText(view.redirectUri);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  }

  return (
    <section aria-labelledby="google-oauth-app-heading" className="flex flex-col gap-4 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 id="google-oauth-app-heading" className="text-base font-medium text-foreground">
          Google (YouTube) app
        </h2>
        <Badge variant={variant}>
          <Icon aria-hidden="true" />
          {label}
        </Badge>
      </div>
      <p className="text-sm text-muted-foreground">
        One Google OAuth app is used by every institute to connect its YouTube channel. Institutes never see or enter
        these credentials - they only click Connect. Create the app (a &quot;Web application&quot; OAuth client) in
        Google Cloud and register the redirect URI below.
      </p>

      <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
        <dt className="text-muted-foreground">Client ID</dt>
        <dd className="break-all text-foreground">{view.clientId ?? "Not set"}</dd>
        <dt className="text-muted-foreground">Client secret</dt>
        <dd className="text-foreground">
          {view.secretSaved
            ? "Saved (hidden)"
            : view.source === "ENVIRONMENT"
              ? "From environment variables (hidden)"
              : "Not set"}
        </dd>
        {view.updatedAt ? (
          <>
            <dt className="text-muted-foreground">Last saved</dt>
            <dd className="text-foreground">{formatDateTime(view.updatedAt)}</dd>
          </>
        ) : null}
        <dt className="text-muted-foreground">Redirect URI</dt>
        <dd className="flex flex-wrap items-center gap-2">
          {view.redirectUri ? (
            <>
              <code className="break-all rounded bg-muted px-1.5 py-0.5 text-xs text-foreground">{view.redirectUri}</code>
              <Button type="button" size="sm" variant="outline" onClick={copyRedirectUri}>
                {copied ? "Copied" : "Copy"}
              </Button>
            </>
          ) : (
            <span className="text-muted-foreground">
              Not available - the platform callback URL (MEDIA_OAUTH_CALLBACK_BASE_URL) isn&apos;t set.
            </span>
          )}
        </dd>
      </dl>
      {copied ? (
        <p role="status" className="sr-only">
          Redirect URI copied.
        </p>
      ) : null}

      {!view.encryptionAvailable ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Credentials can&apos;t be saved here because the platform&apos;s credential encryption key
            (MEDIA_CREDENTIALS_ENCRYPTION_KEY) isn&apos;t set.
          </AlertDescription>
        </Alert>
      ) : null}

      <form aria-label="Google app credentials" noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
        <fieldset disabled={!view.encryptionAvailable || save.isPending} className="flex flex-col gap-4">
          <legend className="sr-only">Google app credentials</legend>
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

          {replacesClient ? (
            <Alert variant="destructive">
              <AlertCircle aria-hidden="true" />
              <AlertDescription className="flex flex-col gap-2">
                <span>
                  This replaces the current Google app. Every institute&apos;s connected YouTube channel stops working
                  until its admin connects it again.
                </span>
                <label htmlFor={ids.ack} className="flex items-center gap-2 font-medium">
                  <input
                    id={ids.ack}
                    type="checkbox"
                    checked={acknowledged}
                    onChange={(event) => setAcknowledged(event.target.checked)}
                  />
                  I understand every institute must reconnect
                </label>
              </AlertDescription>
            </Alert>
          ) : null}
        </fieldset>

        {save.isError && !serverFieldError ? (
          <Alert variant="destructive" role="alert">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorText(save.error, "The credentials could not be saved.")}</AlertDescription>
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
            disabled={!view.encryptionAvailable || save.isPending || (replacesClient && !acknowledged)}
            aria-busy={save.isPending}
          >
            {save.isPending ? "Saving…" : "Save"}
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
          {view.source === "SAVED" ? <RemoveDialog environmentConfigured={view.environmentConfigured} /> : null}
        </div>
      </form>

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

      {view.history.length > 0 ? (
        <div className="flex flex-col gap-2">
          <h3 className="text-sm font-medium text-foreground">Recent changes</h3>
          <ul className="flex flex-col gap-1 text-sm text-muted-foreground">
            {view.history.map((change, index) => (
              <li key={`${change.occurredAt}-${index}`}>
                {formatDateTime(change.occurredAt)} -{" "}
                {change.action === "REMOVED"
                  ? `Removed ${change.clientIdBefore ?? "the saved app"}`
                  : change.clientIdBefore && change.clientIdBefore !== change.clientIdAfter
                    ? `Replaced ${change.clientIdBefore} with ${change.clientIdAfter}`
                    : `Saved ${change.clientIdAfter}${change.secretChanged ? " (new secret)" : ""}`}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

function RemoveDialog({ environmentConfigured }: { environmentConfigured: boolean }) {
  const remove = useRemoveGoogleOAuthApp();
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
      <AlertDialogTrigger render={<Button type="button" variant="outline" />}>Remove saved app</AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Remove the saved Google app?</AlertDialogTitle>
          <AlertDialogDescription>
            {environmentConfigured
              ? "The Google app from the environment variables will be used again. Channels connected with the saved app must be connected again."
              : "No Google app will be configured, so institutes can't connect or use YouTube until one is saved again."}
          </AlertDialogDescription>
        </AlertDialogHeader>
        {remove.isError ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorText(remove.error, "The app could not be removed.")}</AlertDescription>
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
