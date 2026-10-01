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
  type MessagingSettingsView,
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

function errorText(error: unknown): string {
  return isApiClientError(error)
    ? (error.fieldErrors[0]?.message ?? error.message)
    : "Something went wrong. Please try again.";
}

/**
 * Tenant Admin → Institute Configuration → Messaging (Wave 11). The
 * institute's own SMS / WhatsApp provider account, `INTEGRATIONS` VIEW /
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
  settings: MessagingSettingsView;
  canManage: boolean;
}) {
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);
  const providers = settings.availableProviders.filter((p) => p.channels.includes(account.channel));
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
            ? `Provider ${account.provider} · sender ${account.senderId} · key ${account.credentialHint}${
                account.updatedAt ? ` · updated ${formatDateTime(account.updatedAt)}` : ""
              }`
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
  providers: MessagingSettingsView["availableProviders"];
  onSaved: () => void;
}) {
  const configure = useConfigureMessaging();
  const [provider, setProvider] = useState(providers[0]?.provider ?? "");
  const [senderId, setSenderId] = useState(account.senderId ?? "");
  const [credentials, setCredentials] = useState<Record<string, string>>({});
  const keys = providers.find((p) => p.provider === provider)?.credentialKeys ?? [];
  const idPrefix = `messaging-${account.channel}`;

  return (
    <form
      noValidate
      autoComplete="off"
      className="flex flex-col gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        configure.mutate(
          { channel: account.channel, provider, senderId, credentials },
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
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {providers.map((p) => (
                <SelectItem key={p.provider} value={p.provider}>
                  {p.provider}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-sender`}>
            {account.channel === "SMS" ? "Sender name" : "WhatsApp number"}
          </Label>
          <Input
            id={`${idPrefix}-sender`}
            maxLength={40}
            value={senderId}
            onChange={(event) => setSenderId(event.target.value)}
          />
        </div>
        {keys.map((key) => (
          <div key={key} className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-${key}`}>{key === "apiKey" ? "API key" : key}</Label>
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
      <p className="text-xs text-muted-foreground">
        Keys are stored encrypted and are never shown again. Saving replaces the current account.
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
