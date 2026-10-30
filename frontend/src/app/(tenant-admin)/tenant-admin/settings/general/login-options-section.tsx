"use client";

import { useState } from "react";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { SignInMobileCard } from "@/components/auth/sign-in-mobile-card";
import { isApiClientError } from "@/lib/api/error";
import { useUpdateTenantConfigDomain, type TenantConfigProperty } from "@/lib/api/tenant-config";
import { configValue } from "@/lib/validation/tenant-config";

/** Regions offered for numbers written without a country code (backend accepts any libphonenumber region). */
const PHONE_REGIONS = [
  { code: "LK", label: "Sri Lanka (+94)" },
  { code: "IN", label: "India (+91)" },
  { code: "MV", label: "Maldives (+960)" },
  { code: "AE", label: "United Arab Emirates (+971)" },
  { code: "GB", label: "United Kingdom (+44)" },
  { code: "AU", label: "Australia (+61)" },
  { code: "SG", label: "Singapore (+65)" },
  { code: "CA", label: "Canada (+1)" },
  { code: "US", label: "United States (+1)" },
] as const;

const SELECT_CLASS =
  "h-9 w-full rounded-lg border border-input bg-transparent px-2.5 py-1 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30 sm:max-w-xs";

/**
 * Settings → General → Login options (ADR-031, Klass "Login Options"). The
 * institute's mobile sign-in switch (`GENERAL.mobile_login_enabled`, default
 * on — email sign-in can never be switched off) and the country used for
 * numbers typed without a country code (`GENERAL.phone_region`). Both save on
 * change; `BRANDING_SETTINGS/CREATE_EDIT` server-side, audit-logged. Also the
 * signed-in admin's own sign-in mobile.
 */
export function LoginOptionsSection({ properties, canManage }: { properties: TenantConfigProperty[]; canManage: boolean }) {
  const mutation = useUpdateTenantConfigDomain("GENERAL");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);
  const mobileEnabled = configValue(properties, "mobile_login_enabled") !== false;
  const regionValue = configValue(properties, "phone_region");
  const region = typeof regionValue === "string" ? regionValue : "LK";
  const regions = PHONE_REGIONS.some((option) => option.code === region)
    ? PHONE_REGIONS
    : [...PHONE_REGIONS, { code: region, label: region }];

  async function save(changes: Record<string, unknown>, message: string) {
    setErrorMessage(null);
    setSaved(null);
    try {
      await mutation.mutateAsync(changes);
      setSaved(message);
    } catch (error) {
      setErrorMessage(
        isApiClientError(error)
          ? error.status === 403
            ? "You don't have permission to change login options."
            : error.message
          : "Something went wrong. Please try again."
      );
    }
  }

  return (
    <section
      aria-labelledby="login-options-heading"
      className="flex max-w-4xl flex-col gap-4 rounded-lg border border-border p-4"
      data-testid="login-options"
    >
      <div>
        <h2 id="login-options-heading" className="text-base font-medium text-foreground">
          Login options
        </h2>
        <p className="text-sm text-muted-foreground">How students, teachers and staff sign in. Email sign-in is always available.</p>
      </div>

      <div className="flex items-start gap-3">
        <Switch
          id="mobile-login-enabled"
          checked={mobileEnabled}
          disabled={!canManage || mutation.isPending}
          aria-describedby="mobile-login-enabled-help"
          onCheckedChange={(next) =>
            void save({ mobile_login_enabled: next }, next ? "Mobile sign-in turned on." : "Mobile sign-in turned off.")
          }
          className="mt-0.5"
        />
        <div className="flex flex-col gap-0.5">
          <Label htmlFor="mobile-login-enabled">Allow sign-in with mobile number</Label>
          <p id="mobile-login-enabled-help" className="text-sm text-muted-foreground">
            Shows a Mobile tab on the sign-in page. Students sign in with the mobile number they registered with.
          </p>
        </div>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="phone-region">Phone number country</Label>
        <select
          id="phone-region"
          className={SELECT_CLASS}
          value={region}
          disabled={!canManage || mutation.isPending}
          aria-describedby="phone-region-help"
          onChange={(event) => void save({ phone_region: event.target.value }, "Phone number country saved.")}
        >
          {regions.map((option) => (
            <option key={option.code} value={option.code}>
              {option.label}
            </option>
          ))}
        </select>
        <p id="phone-region-help" className="text-xs text-muted-foreground">
          Used for numbers typed without a country code, e.g. 077 123 4567.
        </p>
      </div>

      {errorMessage ? (
        <Alert variant="destructive">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{errorMessage}</AlertDescription>
        </Alert>
      ) : null}
      {saved ? (
        <p role="status" className="text-sm text-muted-foreground">
          {saved}
        </p>
      ) : null}

      <SignInMobileCard
        path="/v1/auth/me/login-mobile"
        title="Your sign-in mobile"
        description="Sign in to your own account with a mobile number instead of your email."
        canEdit
        idPrefix="my-login-mobile"
      />
    </section>
  );
}
