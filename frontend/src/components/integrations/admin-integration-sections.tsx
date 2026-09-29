"use client";

import { useMemo, useState } from "react";
import { useForm, type UseFormRegisterReturn } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import {
  PROVIDER_LABELS,
  useAdminDisconnect,
  useAdminTestConnection,
  useSaveBunny,
  useSaveDynTube,
  useSetVideoHost,
  useUpdateTeacherProviders,
  type ConnectionStatus,
  type ConnectionView,
  type TeacherConnectionRow,
  type TeacherMediaProvider,
  type TeacherProvidersView,
  type VideoHost,
} from "@/lib/api/integrations";
import { bunnySchema, dynTubeSchema, type BunnyFormValues, type DynTubeFormValues } from "@/lib/validation/integrations";
import { ConnectionStatusBadge, connectionDisplayState } from "@/components/integrations/connection-status-badge";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
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

function errorText(error: unknown, fallback: string): string {
  return isApiClientError(error) ? error.message : fallback;
}

// ---------------------------------------------------------------------------
// Teacher providers (which of Zoom / YouTube / Vimeo teachers may connect)
// ---------------------------------------------------------------------------

export function TeacherProvidersSection({
  providers,
  canManage,
}: {
  providers: TeacherProvidersView;
  canManage: boolean;
}) {
  const mutation = useUpdateTeacherProviders();
  const [values, setValues] = useState({
    zoomEnabled: providers.zoom.enabled,
    youtubeEnabled: providers.youtube.enabled,
    vimeoEnabled: providers.vimeo.enabled,
  });
  const [status, setStatus] = useState<{ text: string; error: boolean } | null>(null);
  const rows: { key: keyof typeof values; provider: TeacherMediaProvider; toggle: typeof providers.zoom }[] = [
    { key: "zoomEnabled", provider: "ZOOM", toggle: providers.zoom },
    { key: "youtubeEnabled", provider: "YOUTUBE", toggle: providers.youtube },
    { key: "vimeoEnabled", provider: "VIMEO", toggle: providers.vimeo },
  ];

  async function handleSave(event: React.FormEvent) {
    event.preventDefault();
    setStatus(null);
    try {
      await mutation.mutateAsync(values);
      setStatus({ text: "Teacher integrations saved.", error: false });
    } catch (error) {
      setStatus({ text: errorText(error, "Could not save. Please try again."), error: true });
    }
  }

  return (
    <section aria-labelledby="teacher-providers-heading" className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <h2 id="teacher-providers-heading" className="text-base font-medium text-foreground">
          Teacher accounts
        </h2>
        <p className="text-sm text-muted-foreground">
          Choose which services teachers may connect with their own accounts. Switching one off keeps existing
          connections but stops them being used.
        </p>
      </div>
      <form onSubmit={handleSave} className="flex flex-col gap-3">
        <fieldset className="flex flex-col gap-2" disabled={!canManage}>
          <legend className="sr-only">Services teachers may connect</legend>
          {rows.map((row) => (
            <label key={row.key} className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={values[row.key]}
                onChange={(event) => setValues((current) => ({ ...current, [row.key]: event.target.checked }))}
              />
              {PROVIDER_LABELS[row.provider]}
              {!row.toggle.available ? (
                <span className="text-xs text-muted-foreground">(not available on this platform yet)</span>
              ) : null}
            </label>
          ))}
        </fieldset>
        {canManage ? (
          <Button type="submit" className="w-fit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
            {mutation.isPending ? "Saving…" : "Save"}
          </Button>
        ) : null}
        {status ? (
          <p role={status.error ? "alert" : "status"} className={status.error ? "text-sm text-destructive" : "text-sm text-muted-foreground"}>
            {status.text}
          </p>
        ) : null}
      </form>
    </section>
  );
}

// ---------------------------------------------------------------------------
// Tenant video hosts (DynTube / Bunny.net)
// ---------------------------------------------------------------------------

function AccountSummary({ connection }: { connection: ConnectionView | null }) {
  if (!connection) {
    return <p className="text-sm text-muted-foreground">Not set up yet.</p>;
  }
  return (
    <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
      <dt className="text-muted-foreground">Status</dt>
      <dd>
        <ConnectionStatusBadge state={connectionDisplayState(connection)} />
      </dd>
      <dt className="text-muted-foreground">API key</dt>
      <dd className="text-foreground">{connection.secretConfigured ? "Saved (hidden)" : "Not set"}</dd>
      {connection.settings?.libraryId ? (
        <>
          <dt className="text-muted-foreground">Library ID</dt>
          <dd className="text-foreground">{connection.settings.libraryId}</dd>
          <dt className="text-muted-foreground">CDN hostname</dt>
          <dd className="break-all text-foreground">{connection.settings.cdnHostname}</dd>
        </>
      ) : null}
      <dt className="text-muted-foreground">Last checked</dt>
      <dd className="text-foreground">{connection.lastVerifiedAt ? formatDateTime(connection.lastVerifiedAt) : "—"}</dd>
      {connection.status === "DISCONNECTED" ? (
        <>
          <dt className="text-muted-foreground">Disconnected</dt>
          <dd className="text-foreground">{connection.disconnectedAt ? formatDateTime(connection.disconnectedAt) : "—"}</dd>
        </>
      ) : null}
    </dl>
  );
}

function ConnectionActions({ connection, canManage }: { connection: ConnectionView | null; canManage: boolean }) {
  const testMutation = useAdminTestConnection();
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  if (!canManage || !connection || connection.status === "DISCONNECTED") return null;

  async function handleTest() {
    if (!connection) return;
    setMessage(null);
    try {
      const result = await testMutation.mutateAsync(connection.id);
      setMessage({ text: result.message, error: !result.ok });
    } catch (error) {
      setMessage({ text: errorText(error, "The test could not be run."), error: true });
    }
  }

  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="outline" onClick={handleTest} disabled={testMutation.isPending} aria-busy={testMutation.isPending}>
          {testMutation.isPending ? "Testing…" : "Test connection"}
        </Button>
        <DisconnectDialog connection={connection} subject={PROVIDER_LABELS[connection.provider]} reasonRequired={false} />
      </div>
      {message ? (
        <p role={message.error ? "alert" : "status"} className={message.error ? "text-sm text-destructive" : "text-sm text-muted-foreground"}>
          {message.text}
        </p>
      ) : null}
    </div>
  );
}

function SecretInput({
  id,
  label,
  stored,
  error,
  registration,
}: {
  id: string;
  label: string;
  stored: boolean;
  error?: string;
  registration: UseFormRegisterReturn;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <Input
        id={id}
        type="password"
        autoComplete="new-password"
        placeholder={stored ? "Leave blank to keep the saved key" : undefined}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        {...registration}
      />
      {error ? (
        <p id={`${id}-error`} role="alert" className="text-sm text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function DynTubeSection({ connection, canManage }: { connection: ConnectionView | null; canManage: boolean }) {
  const mutation = useSaveDynTube();
  const stored = connection?.status !== "DISCONNECTED" && Boolean(connection?.secretConfigured);
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const { register, handleSubmit, setError, reset, formState: { errors } } = useForm<DynTubeFormValues>({
    resolver: zodResolver(dynTubeSchema),
    defaultValues: { apiKey: "" },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    if (!stored && !values.apiKey.trim()) {
      setError("apiKey", { type: "manual", message: "API key is required." });
      return;
    }
    try {
      await mutation.mutateAsync(values);
      setSaved(true);
      reset({ apiKey: "" });
    } catch (error) {
      if (isApiClientError(error)) {
        const field = error.fieldErrors.find((fieldError) => fieldError.field === "apiKey");
        if (field) {
          setError("apiKey", { type: "server", message: field.message });
        } else {
          setPageError({ message: error.message, code: error.code });
        }
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <section aria-labelledby="dyntube-heading" className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <h3 id="dyntube-heading" className="text-sm font-medium text-foreground">
        DynTube
      </h3>
      <AccountSummary connection={connection} />
      {canManage ? (
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3">
          {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}
          <SecretInput id="dyntube-api-key" label="DynTube API key" stored={stored} error={errors.apiKey?.message} registration={register("apiKey")} />
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
              {mutation.isPending ? "Checking with DynTube…" : stored ? "Save DynTube" : "Connect DynTube"}
            </Button>
            {saved ? (
              <p role="status" className="text-sm text-muted-foreground">
                DynTube account saved.
              </p>
            ) : null}
          </div>
        </form>
      ) : null}
      <ConnectionActions connection={connection} canManage={canManage} />
    </section>
  );
}

export function BunnySection({ connection, canManage }: { connection: ConnectionView | null; canManage: boolean }) {
  const mutation = useSaveBunny();
  const live = connection?.status !== "DISCONNECTED" ? connection : null;
  const stored = Boolean(live?.secretConfigured);
  const [pageError, setPageError] = useState<{ message: string; code?: string } | null>(null);
  const [saved, setSaved] = useState(false);
  const { register, handleSubmit, setError, reset, formState: { errors } } = useForm<BunnyFormValues>({
    resolver: zodResolver(bunnySchema),
    defaultValues: {
      apiKey: "",
      libraryId: live?.settings?.libraryId ?? "",
      cdnHostname: live?.settings?.cdnHostname ?? "",
      tokenAuthKey: "",
    },
  });

  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    setSaved(false);
    if (!stored && !values.apiKey.trim()) {
      setError("apiKey", { type: "manual", message: "API key is required." });
      return;
    }
    try {
      await mutation.mutateAsync(values);
      setSaved(true);
      reset({ ...values, apiKey: "", tokenAuthKey: "" });
    } catch (error) {
      if (isApiClientError(error)) {
        let mapped = false;
        for (const fieldError of error.fieldErrors) {
          if (["apiKey", "libraryId", "cdnHostname", "tokenAuthKey"].includes(fieldError.field)) {
            setError(fieldError.field as keyof BunnyFormValues, { type: "server", message: fieldError.message });
            mapped = true;
          }
        }
        if (!mapped) setPageError({ message: error.message, code: error.code });
        return;
      }
      setPageError({ message: "An unexpected error occurred. Please try again." });
    }
  });

  return (
    <section aria-labelledby="bunny-heading" className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <h3 id="bunny-heading" className="text-sm font-medium text-foreground">
        Bunny.net Stream
      </h3>
      <AccountSummary connection={connection} />
      {canManage ? (
        <form onSubmit={onSubmit} noValidate className="flex flex-col gap-3">
          {pageError ? <ErrorState message={pageError.message} code={pageError.code} /> : null}
          <SecretInput id="bunny-api-key" label="Stream API key" stored={stored} error={errors.apiKey?.message} registration={register("apiKey")} />
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="bunny-library-id">Video library ID</Label>
            <Input
              id="bunny-library-id"
              inputMode="numeric"
              aria-invalid={errors.libraryId ? true : undefined}
              aria-describedby={errors.libraryId ? "bunny-library-id-error" : undefined}
              {...register("libraryId")}
            />
            {errors.libraryId ? (
              <p id="bunny-library-id-error" role="alert" className="text-sm text-destructive">
                {errors.libraryId.message}
              </p>
            ) : null}
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="bunny-cdn-hostname">CDN hostname</Label>
            <Input
              id="bunny-cdn-hostname"
              placeholder="vz-abc123.b-cdn.net"
              aria-invalid={errors.cdnHostname ? true : undefined}
              aria-describedby={errors.cdnHostname ? "bunny-cdn-hostname-error" : undefined}
              {...register("cdnHostname")}
            />
            {errors.cdnHostname ? (
              <p id="bunny-cdn-hostname-error" role="alert" className="text-sm text-destructive">
                {errors.cdnHostname.message}
              </p>
            ) : null}
          </div>
          <SecretInput
            id="bunny-token-auth-key"
            label="Token authentication key (optional)"
            stored={Boolean(live?.settings?.tokenAuthConfigured)}
            error={errors.tokenAuthKey?.message}
            registration={register("tokenAuthKey")}
          />
          <div className="flex items-center gap-3">
            <Button type="submit" disabled={mutation.isPending} aria-busy={mutation.isPending}>
              {mutation.isPending ? "Checking with Bunny.net…" : stored ? "Save Bunny.net" : "Connect Bunny.net"}
            </Button>
            {saved ? (
              <p role="status" className="text-sm text-muted-foreground">
                Bunny.net account saved.
              </p>
            ) : null}
          </div>
        </form>
      ) : null}
      <ConnectionActions connection={connection} canManage={canManage} />
    </section>
  );
}

export function VideoHostSection({
  videoHost,
  dyntube,
  bunny,
  canManage,
}: {
  videoHost: VideoHost;
  dyntube: ConnectionView | null;
  bunny: ConnectionView | null;
  canManage: boolean;
}) {
  const mutation = useSetVideoHost();
  const [status, setStatus] = useState<{ text: string; error: boolean } | null>(null);
  const options: { value: VideoHost; label: string; ready: boolean }[] = [
    { value: "NONE", label: "None", ready: true },
    { value: "DYNTUBE", label: "DynTube", ready: dyntube?.status === "ACTIVE" },
    { value: "BUNNY", label: "Bunny.net", ready: bunny?.status === "ACTIVE" },
  ];

  async function choose(value: VideoHost) {
    setStatus(null);
    try {
      await mutation.mutateAsync(value);
      setStatus({ text: "Video host updated.", error: false });
    } catch (error) {
      setStatus({ text: errorText(error, "Could not change the video host."), error: true });
    }
  }

  return (
    <fieldset className="flex flex-col gap-2 rounded-lg border border-border p-4" disabled={!canManage || mutation.isPending}>
      <legend className="px-1 text-sm font-medium text-foreground">Active video host</legend>
      <p className="text-sm text-muted-foreground">
        Used for uploaded course videos (coming soon). Only a connected account can be chosen.
      </p>
      {options.map((option) => (
        <label key={option.value} className="flex items-center gap-2 text-sm">
          <input
            type="radio"
            name="video-host"
            value={option.value}
            checked={videoHost === option.value}
            disabled={!option.ready}
            onChange={() => choose(option.value)}
          />
          {option.label}
          {!option.ready ? <span className="text-xs text-muted-foreground">(connect it first)</span> : null}
        </label>
      ))}
      {status ? (
        <p role={status.error ? "alert" : "status"} className={status.error ? "text-sm text-destructive" : "text-sm text-muted-foreground"}>
          {status.text}
        </p>
      ) : null}
    </fieldset>
  );
}

// ---------------------------------------------------------------------------
// Teachers' connections
// ---------------------------------------------------------------------------

function DisconnectDialog({
  connection,
  subject,
  reasonRequired,
}: {
  connection: ConnectionView;
  subject: string;
  reasonRequired: boolean;
}) {
  const mutation = useAdminDisconnect();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const reasonId = `disconnect-reason-${connection.id}`;

  async function handleConfirm() {
    setErrorMessage(null);
    if (reasonRequired && !reason.trim()) {
      setErrorMessage("A reason is required.");
      return;
    }
    try {
      await mutation.mutateAsync({ connectionId: connection.id, reason });
      setOpen(false);
      setReason("");
    } catch (error) {
      setErrorMessage(errorText(error, "Could not disconnect. Please try again."));
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger render={<Button type="button" variant="outline" size="sm" aria-label={`Disconnect ${subject}`} />}>
        Disconnect
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Disconnect {subject}?</AlertDialogTitle>
          <AlertDialogDescription>
            The platform will stop using this account. It can be connected again later.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={reasonId}>Reason{reasonRequired ? "" : " (optional)"}</Label>
          <Textarea id={reasonId} value={reason} maxLength={500} onChange={(event) => setReason(event.target.value)} />
        </div>
        {errorMessage ? (
          <Alert variant="destructive">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>{errorMessage}</AlertDescription>
          </Alert>
        ) : null}
        <AlertDialogFooter>
          <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
          <Button type="button" variant="destructive" onClick={handleConfirm} disabled={mutation.isPending} aria-busy={mutation.isPending}>
            {mutation.isPending ? "Disconnecting…" : "Disconnect"}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

const STATUS_FILTERS: { value: "ALL" | ConnectionStatus; label: string }[] = [
  { value: "ALL", label: "All statuses" },
  { value: "ACTIVE", label: "Connected" },
  { value: "NEEDS_RECONNECT", label: "Needs reconnect" },
];

export function TeacherConnectionsSection({
  rows,
  canManage,
}: {
  rows: TeacherConnectionRow[];
  canManage: boolean;
}) {
  const [provider, setProvider] = useState<"ALL" | TeacherMediaProvider>("ALL");
  const [status, setStatus] = useState<"ALL" | ConnectionStatus>("ALL");
  const filtered = useMemo(
    () =>
      rows.filter(
        (row) =>
          (provider === "ALL" || row.connection.provider === provider) &&
          (status === "ALL" || row.connection.status === status)
      ),
    [rows, provider, status]
  );
  const filtersActive = provider !== "ALL" || status !== "ALL";

  const columns: DataTableColumn<TeacherConnectionRow>[] = [
    { key: "teacher", header: "Teacher", cell: (row) => row.teacherName || "Unknown teacher", hideOnCard: true },
    { key: "service", header: "Service", cell: (row) => PROVIDER_LABELS[row.connection.provider] },
    { key: "account", header: "Account", cell: (row) => <span className="break-all">{row.connection.accountLabel ?? "—"}</span> },
    {
      key: "status",
      header: "Status",
      cell: (row) => <ConnectionStatusBadge state={connectionDisplayState(row.connection)} />,
      hideOnCard: true,
    },
    { key: "connected", header: "Connected", cell: (row) => formatDateTime(row.connection.connectedAt) },
    {
      key: "actions",
      header: "Actions",
      hideOnCard: true,
      cell: (row) =>
        canManage ? (
          <DisconnectDialog
            connection={row.connection}
            subject={`${row.teacherName || "this teacher"}'s ${PROVIDER_LABELS[row.connection.provider]}`}
            reasonRequired
          />
        ) : null,
    },
  ];

  return (
    <section aria-labelledby="teacher-connections-heading" className="flex flex-col gap-3">
      <div>
        <h2 id="teacher-connections-heading" className="text-base font-medium text-foreground">
          Teachers&apos; connected accounts
        </h2>
        <p className="text-sm text-muted-foreground">
          Teachers connect their own accounts. You can disconnect one (for example when a teacher leaves), but you
          can&apos;t connect an account for them or see their credentials.
        </p>
      </div>
      {rows.length > 0 ? (
        <div className="flex flex-wrap gap-3">
          <div className="flex flex-col gap-1">
            <Label htmlFor="teacher-connections-provider">Service</Label>
            <select
              id="teacher-connections-provider"
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={provider}
              onChange={(event) => setProvider(event.target.value as typeof provider)}
            >
              <option value="ALL">All services</option>
              <option value="ZOOM">Zoom</option>
              <option value="YOUTUBE">YouTube</option>
              <option value="VIMEO">Vimeo</option>
            </select>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor="teacher-connections-status">Status</Label>
            <select
              id="teacher-connections-status"
              className="h-9 rounded-md border border-input bg-background px-2 text-sm"
              value={status}
              onChange={(event) => setStatus(event.target.value as typeof status)}
            >
              {STATUS_FILTERS.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          </div>
        </div>
      ) : null}
      {rows.length === 0 ? (
        <EmptyState
          title="No teacher has connected an account yet"
          description="Teachers connect Zoom, YouTube or Vimeo from their own Settings → Integrations page. Connected accounts will appear here."
        />
      ) : filtered.length === 0 ? (
        <EmptyState
          title="No connections match your filters"
          description="Try a different service or status."
          action={{
            label: "Clear filters",
            onClick: () => {
              setProvider("ALL");
              setStatus("ALL");
            },
          }}
        />
      ) : (
        <DataTable
          columns={columns}
          rows={filtered}
          rowKey={(row) => row.connection.id}
          caption={filtersActive ? "Teachers' connected accounts (filtered)" : "Teachers' connected accounts"}
          cardHeading={(row) => row.teacherName || "Unknown teacher"}
          cardHeadingAdornment={(row) => <ConnectionStatusBadge state={connectionDisplayState(row.connection)} />}
          cardFooter={(row) =>
            canManage ? (
              <DisconnectDialog
                connection={row.connection}
                subject={`${row.teacherName || "this teacher"}'s ${PROVIDER_LABELS[row.connection.provider]}`}
                reasonRequired
              />
            ) : null
          }
        />
      )}
    </section>
  );
}
