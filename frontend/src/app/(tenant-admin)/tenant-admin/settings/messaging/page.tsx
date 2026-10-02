"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations } from "@/lib/auth/permissions";
import {
  useConfigureMessaging,
  useDisableMessaging,
  useMessagingSettings,
  useTestMessaging,
  type MessagingAccountView,
  type MessagingProviderOption,
} from "@/lib/api/communications";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { PageHeader } from "@/components/ui/page-header";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";

const CHANNEL_TITLES = { SMS: "SMS", WHATSAPP: "WhatsApp" } as const;

interface ProviderCopy {
  label: string;
  senderLabel: string;
  senderHint: string;
  fieldLabels: Record<string, string>;
  help?: string;
}

/**
 * Display copy per provider (MSG-PROV-01 / ADR-024). The backend decides which
 * providers exist and which fields each needs; this only names them.
 */
const PROVIDER_COPY: Record<string, ProviderCopy> = {
  TEXTLK: {
    label: "Text.lk",
    senderLabel: "Sender ID",
    senderHint: "Your approved Text.lk sender ID (up to 11 letters or digits), or a phone number.",
    fieldLabels: { apiToken: "API token" },
    help: "Create an API token in your Text.lk dashboard (Developers → API Tokens). Text.lk does not send delivery reports, so sent SMS show as “Accepted by provider”.",
  },
  META_WHATSAPP: {
    label: "Meta WhatsApp Cloud API",
    senderLabel: "Phone number ID",
    senderHint: "The phone number ID from WhatsApp Manager → API Setup (digits only), not the phone number itself.",
    fieldLabels: {
      accessToken: "Permanent access token",
      appSecret: "App secret",
      templateLanguage: "Template language",
    },
    help: "Use a system-user access token with the whatsapp_business_messaging permission. Broadcasts are sent as approved WhatsApp templates in the language below.",
  },
  SANDBOX: {
    label: "Sandbox (local testing only)",
    senderLabel: "Sender name",
    senderHint: "Any name; sandbox messages are only logged and never leave this server.",
    fieldLabels: { apiKey: "API key" },
  },
};

function providerCopy(provider: string | null | undefined): ProviderCopy {
  return (
    (provider ? PROVIDER_COPY[provider] : undefined) ?? {
      label: provider ?? "",
      senderLabel: "Sender",
      senderHint: "",
      fieldLabels: {},
    }
  );
}

function fieldLabel(provider: string, key: string): string {
  return providerCopy(provider).fieldLabels[key] ?? key;
}

function errorText(error: unknown): string {
  return isApiClientError(error)
    ? (error.fieldErrors[0]?.message ?? error.message)
    : "Something went wrong. Please try again.";
}

/**
 * Tenant Admin → Institute Configuration → Messaging (Wave 11, MSG-PROV-01).
 * The institute's own SMS (Text.lk) / WhatsApp (Meta Cloud API) account, `INTEGRATIONS` VIEW /
 * CREATE_EDIT (Tenant Admin edits; Read-only Auditor views).
 *
 * Credentials are write-only: they are sent once, encrypted by the backend and
 * never shown again - this page only ever displays the masked hint. Only the
 * providers the backend reports as available can be chosen; with none, both
 * channels simply stay off.
 */
export default function MessagingSettingsPage() {
  const { session } = useAuth();
  const canManage = canManageIntegrations(session?.role ?? null);
  const query = useMessagingSettings();
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Messaging (SMS & WhatsApp)"
        description="Connect your institute's own SMS and WhatsApp accounts to send broadcasts on those channels."
      />
      <QueryStateBoundary query={query} loadingLabel="Loading messaging settings…" loginPath="/login">
        {(settings) => (
          <>
            {settings.availableProviders.length === 0 ? (
              <Alert>
                <AlertDescription>
                  No SMS or WhatsApp provider is available on this platform yet, so these channels are off. In-app
                  and email messages still work.
                </AlertDescription>
              </Alert>
            ) : null}
            {settings.accounts.map((account) => (
              <ChannelCard key={account.channel} account={account} settings={settings} canManage={canManage} />
            ))}
          </>
        )}
      </QueryStateBoundary>
    </div>
  );
}

function ChannelCard({
  account,
  settings,
  canManage,
}: {
  account: MessagingAccountView;
  settings: { availableProviders: MessagingProviderOption[] };
  canManage: boolean;
}) {
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);
  // Real providers first; the sandbox (local/test only) last.
  const providers = settings.availableProviders
    .filter((p) => p.channels.includes(account.channel))
    .sort((a, b) => Number(a.provider === "SANDBOX") - Number(b.provider === "SANDBOX"));
  const title = CHANNEL_TITLES[account.channel];

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          {title}
          <Badge variant={account.operational ? "secondary" : "outline"}>
            {account.operational ? "On" : account.status === "NOT_CONFIGURED" ? "Not set up" : "Off"}
          </Badge>
        </CardTitle>
        <CardDescription>
          {account.configured
            ? [
                `Provider ${providerCopy(account.provider).label}`,
                `${providerCopy(account.provider).senderLabel.toLowerCase()} ${account.senderId}`,
                `key ${account.credentialHint}`,
                account.templateLanguage ? `language ${account.templateLanguage}` : null,
                account.updatedAt ? `updated ${formatDateTime(account.updatedAt)}` : null,
              ]
                .filter(Boolean)
                .join(" · ")
            : `No ${title} account connected.`}
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div role="status" aria-live="polite">
          {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
        </div>
        {canManage && providers.length > 0 ? (
          <ConfigureForm
            account={account}
            providers={providers}
            onSaved={() => setNotice(`${title} account saved.`)}
          />
        ) : null}
        {canManage && account.status === "ACTIVE" ? (
          <ActiveAccountActions account={account} onNotice={setNotice} />
        ) : null}
        {account.webhook ? <WebhookSetupPanel webhook={account.webhook} channel={account.channel} /> : null}
      </CardContent>
    </Card>
  );
}

function ConfigureForm({
  account,
  providers,
  onSaved,
}: {
  account: MessagingAccountView;
  providers: MessagingProviderOption[];
  onSaved: () => void;
}) {
  const configure = useConfigureMessaging();
  const [provider, setProvider] = useState(
    providers.find((p) => p.provider === account.provider)?.provider ?? providers[0]?.provider ?? ""
  );
  const [senderId, setSenderId] = useState(account.senderId ?? "");
  const [templateLanguage, setTemplateLanguage] = useState(account.templateLanguage ?? "en");
  const [credentials, setCredentials] = useState<Record<string, string>>({});
  const option = providers.find((p) => p.provider === provider);
  const keys = option?.credentialKeys ?? [];
  const needsLanguage = option?.settingKeys.includes("templateLanguage") ?? false;
  const copy = providerCopy(provider);
  const idPrefix = `messaging-${account.channel}`;

  return (
    <form
      noValidate
      autoComplete="off"
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        configure.mutate(
          {
            channel: account.channel,
            provider,
            senderId,
            // Only the chosen provider's secrets are sent.
            credentials: Object.fromEntries(keys.map((key) => [key, credentials[key] ?? ""])),
            templateLanguage: needsLanguage ? templateLanguage : null,
          },
          {
            onSuccess: () => {
              setCredentials({});
              onSaved();
            },
          }
        );
      }}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-provider`}>Provider</Label>
          <Select value={provider} onValueChange={(value) => value && setProvider(value)}>
            <SelectTrigger id={`${idPrefix}-provider`} className="w-full">
              <SelectValue>{copy.label}</SelectValue>
            </SelectTrigger>
            <SelectContent>
              {providers.map((p) => (
                <SelectItem key={p.provider} value={p.provider}>
                  {providerCopy(p.provider).label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-sender`}>{copy.senderLabel}</Label>
          <Input
            id={`${idPrefix}-sender`}
            maxLength={40}
            value={senderId}
            aria-describedby={`${idPrefix}-sender-hint`}
            onChange={(event) => setSenderId(event.target.value)}
          />
          <p id={`${idPrefix}-sender-hint`} className="text-xs text-muted-foreground">
            {copy.senderHint}
          </p>
        </div>
        {needsLanguage ? (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-templateLanguage`}>{fieldLabel(provider, "templateLanguage")}</Label>
            <Input
              id={`${idPrefix}-templateLanguage`}
              maxLength={15}
              placeholder="en"
              value={templateLanguage}
              aria-describedby={`${idPrefix}-templateLanguage-hint`}
              onChange={(event) => setTemplateLanguage(event.target.value)}
            />
            <p id={`${idPrefix}-templateLanguage-hint`} className="text-xs text-muted-foreground">
              The language your templates were approved in, e.g. en, en_US, si or ta.
            </p>
          </div>
        ) : null}
        {keys.map((key) => (
          <div key={key} className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-${key}`}>{fieldLabel(provider, key)}</Label>
            <Input
              id={`${idPrefix}-${key}`}
              type="password"
              autoComplete="new-password"
              value={credentials[key] ?? ""}
              onChange={(event) => setCredentials({ ...credentials, [key]: event.target.value })}
            />
          </div>
        ))}
      </div>
      {copy.help ? <p className="text-sm text-muted-foreground">{copy.help}</p> : null}
      <p className="text-xs text-muted-foreground">
        Keys are stored encrypted and are never shown again. Saving replaces the current account, so enter every
        key again when you change anything.
      </p>
      {configure.isError ? (
        <Alert variant="destructive">
          <AlertDescription>{errorText(configure.error)}</AlertDescription>
        </Alert>
      ) : null}
      <div>
        <Button type="submit" disabled={configure.isPending}>
          {configure.isPending ? "Saving…" : account.configured ? "Replace account" : "Connect account"}
        </Button>
      </div>
    </form>
  );
}

function ActiveAccountActions({
  account,
  onNotice,
}: {
  account: MessagingAccountView;
  onNotice: (message: string) => void;
}) {
  const disable = useDisableMessaging();
  const test = useTestMessaging();
  const [destination, setDestination] = useState("");
  const idPrefix = `messaging-${account.channel}-test`;
  return (
    <div className="flex flex-col gap-3 border-t border-border pt-4">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={idPrefix}>Send a test message to</Label>
          <Input
            id={idPrefix}
            inputMode="tel"
            placeholder="+94771234567"
            value={destination}
            onChange={(event) => setDestination(event.target.value)}
          />
        </div>
        <Button
          type="button"
          variant="outline"
          disabled={test.isPending}
          onClick={() =>
            test.mutate(
              { channel: account.channel, destination },
              { onSuccess: (result) => onNotice(`Test message sent to ${result.destination}.`) }
            )
          }
        >
          {test.isPending ? "Sending…" : "Send test"}
        </Button>
        <Button
          type="button"
          variant="outline"
          disabled={disable.isPending}
          onClick={() =>
            disable.mutate(account.channel, {
              onSuccess: () => onNotice(`${CHANNEL_TITLES[account.channel]} switched off.`),
            })
          }
        >
          Switch off
        </Button>
      </div>
      {test.isError ? <p className="text-sm text-destructive">{errorText(test.error)}</p> : null}
      {disable.isError ? <p className="text-sm text-destructive">{errorText(disable.error)}</p> : null}
    </div>
  );
}

/**
 * Meta WhatsApp delivery receipts (ADR-024 V8): the tenant pastes these into
 * its Meta app's webhook settings and subscribes to the `messages` field.
 * The backend returns this to managers only.
 */
function WebhookSetupPanel({
  webhook,
  channel,
}: {
  webhook: NonNullable<MessagingAccountView["webhook"]>;
  channel: MessagingAccountView["channel"];
}) {
  const idPrefix = `messaging-${channel}-webhook`;
  return (
    <div className="flex flex-col gap-3 border-t border-border pt-4">
      <div>
        <p className="text-sm font-medium text-foreground">Delivery receipts (webhook)</p>
        <p className="text-sm text-muted-foreground">
          In your Meta app, open WhatsApp → Configuration → Webhook, paste these values, then subscribe to
          the “messages” field. Without this, WhatsApp messages show only as sent.
        </p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-url`}>Callback URL</Label>
          <Input id={`${idPrefix}-url`} readOnly value={webhook.callbackUrl} onFocus={(e) => e.target.select()} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-token`}>Verify token</Label>
          <Input id={`${idPrefix}-token`} readOnly value={webhook.verifyToken} onFocus={(e) => e.target.select()} />
        </div>
      </div>
    </div>
  );
}
