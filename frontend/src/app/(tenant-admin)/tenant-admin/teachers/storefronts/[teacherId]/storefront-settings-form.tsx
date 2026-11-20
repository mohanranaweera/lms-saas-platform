"use client";

import { useEffect, useState } from "react";
import { Controller, useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { CheckCircle2 } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { isApiClientError } from "@/lib/api/error";
import { useSaveStorefrontSettings, type StorefrontAdmin } from "@/lib/api/teacher-storefronts";
import { storefrontUrlWithSlug } from "@/lib/storefront/site-context";
import {
  STOREFRONT_SLUG_MAX,
  storefrontSettingsSchema,
  storefrontSlugErrorMessage,
  storefrontSlugHint,
  type StorefrontSettingsFormInput,
  type StorefrontSettingsFormValues,
} from "@/lib/validation/teacher-storefront";

function defaults(storefront: StorefrontAdmin): StorefrontSettingsFormInput {
  return { enabled: storefront.id ? storefront.enabled : false, slug: storefront.slug ?? "" };
}

/** On/off switch + address. `PUT /v1/teacher-storefronts/{teacherId}` (creates on first save). */
export function StorefrontSettingsForm({ teacherId, storefront }: { teacherId: string; storefront: StorefrontAdmin }) {
  const save = useSaveStorefrontSettings(teacherId);
  const [saved, setSaved] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);
  const form = useForm<StorefrontSettingsFormInput, unknown, StorefrontSettingsFormValues>({
    resolver: zodResolver(storefrontSettingsSchema),
    defaultValues: defaults(storefront),
  });
  const { reset, control, setError } = form;

  useEffect(() => {
    reset(defaults(storefront));
  }, [reset, storefront]);

  const slugValue = useWatch({ control, name: "slug" }) ?? "";
  const enabled = useWatch({ control, name: "enabled" });
  const hint = storefrontSlugHint(slugValue);
  const normalized = slugValue.trim().toLowerCase();
  const addressPreview = hint ? null : storefrontUrlWithSlug(storefront.publishedUrl ?? storefront.previewUrl, normalized);
  const slugError = form.formState.errors.slug?.message;

  const onSubmit = form.handleSubmit(async (values) => {
    setSaved(false);
    setServerError(null);
    try {
      await save.mutateAsync({ slug: values.slug, enabled: values.enabled });
      setSaved(true);
    } catch (error) {
      const slugMessage = isApiClientError(error) ? storefrontSlugErrorMessage(error.code) : null;
      if (slugMessage) {
        setError("slug", { type: "server", message: slugMessage });
        return;
      }
      if (isApiClientError(error) && error.status === 404) {
        setServerError("This teacher isn't part of your institute, or the link is out of date.");
        return;
      }
      if (isApiClientError(error) && error.status === 403) {
        setServerError("You don't have permission to manage teacher storefronts.");
        return;
      }
      setServerError(isApiClientError(error) && error.message ? error.message : "We couldn't save this storefront. Try again.");
    }
  });

  return (
    <form
      onSubmit={onSubmit}
      noValidate
      className="flex flex-col gap-5 rounded-xl border border-border bg-card p-5 shadow-sm"
      aria-labelledby="storefront-settings-heading"
      data-testid="storefront-settings-form"
    >
      <div>
        <h2 id="storefront-settings-heading" className="text-base font-semibold text-foreground">
          Personal homepage
        </h2>
        <p className="text-sm text-muted-foreground">
          The storefront sells this institute&apos;s courses through your normal sign-in, checkout and payments.
          Turning it off never removes anything a student already has.
        </p>
      </div>

      <div className="flex items-start justify-between gap-4 rounded-lg border border-border p-4">
        <div className="flex min-w-0 flex-col gap-1">
          <Label htmlFor="storefront-enabled">Enable personal homepage</Label>
          <p id="storefront-enabled-description" className="text-xs text-muted-foreground">
            {enabled ? "Visitors can open this teacher's homepage." : "Off: the address shows a “Storefront not found” page."}
          </p>
        </div>
        <Controller
          control={control}
          name="enabled"
          render={({ field }) => (
            <Switch
              id="storefront-enabled"
              checked={field.value}
              onCheckedChange={field.onChange}
              onBlur={field.onBlur}
              aria-describedby="storefront-enabled-description"
              disabled={save.isPending}
            />
          )}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="storefront-slug">Address</Label>
        <Input
          id="storefront-slug"
          autoComplete="off"
          spellCheck={false}
          maxLength={STOREFRONT_SLUG_MAX + 10}
          placeholder="e.g. alice-perera"
          aria-invalid={slugError ? true : undefined}
          aria-describedby={slugError ? "storefront-slug-error storefront-slug-help" : "storefront-slug-help"}
          disabled={save.isPending}
          {...form.register("slug")}
        />
        {slugError ? (
          <p id="storefront-slug-error" role="alert" className="text-sm text-destructive">
            {slugError}
          </p>
        ) : null}
        <p id="storefront-slug-help" className="text-xs text-muted-foreground">
          3 to 40 lowercase letters, numbers or single hyphens. An address used once stays with this teacher.
          {!slugError && normalized && hint ? <span className="block text-warning">{hint}</span> : null}
        </p>
        {addressPreview ? (
          <p className="text-xs text-muted-foreground" data-testid="storefront-address-preview">
            Address after saving: <span className="break-all font-medium text-foreground">{addressPreview}</span>
          </p>
        ) : null}
      </div>

      {saved ? (
        <Alert role="status" data-testid="storefront-settings-saved">
          <CheckCircle2 aria-hidden="true" />
          <AlertDescription>Storefront saved.</AlertDescription>
        </Alert>
      ) : null}
      {serverError ? (
        <p role="alert" className="text-sm text-destructive" data-testid="storefront-settings-error">
          {serverError}
        </p>
      ) : null}

      <div className="flex justify-end">
        <Button type="submit" disabled={save.isPending} aria-busy={save.isPending}>
          {save.isPending ? "Saving…" : "Save storefront"}
        </Button>
      </div>
    </form>
  );
}
