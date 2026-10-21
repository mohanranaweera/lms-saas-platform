"use client";

import { useState } from "react";
import { CheckCircle2, CircleSlash, Clock, UserX } from "lucide-react";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime } from "@/lib/format";
import { useInviteTenantAdmin, type FirstAdmin, type FirstAdminStatus } from "@/lib/api/platform-admin-tenants";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

const STATUS: Record<FirstAdminStatus, { label: string; variant: "default" | "warning" | "outline" | "secondary"; Icon: typeof CheckCircle2 }> = {
  NONE: { label: "No admin yet", variant: "outline", Icon: UserX },
  INVITED: { label: "Invitation pending", variant: "warning", Icon: Clock },
  ACTIVE: { label: "Active", variant: "default", Icon: CheckCircle2 },
  SUSPENDED: { label: "Suspended", variant: "secondary", Icon: CircleSlash },
};

/**
 * AUTH-PWD-01 — the tenant's first Tenant Admin, on the Platform Admin tenant
 * page. Approving a tenant invites its admin automatically (registration
 * contact email); here a Platform Admin can see whether the invitation was
 * used, resend it, correct the email while it's pending, or invite an admin
 * for a tenant that has none. The backend enforces every rule (409/429).
 */
export function TenantAdminPanel({
  tenantId,
  tenantName,
  firstAdmin,
  canInvite,
}: {
  tenantId: string;
  tenantName: string;
  firstAdmin: FirstAdmin;
  /** The tenant is trial/active (approved). */
  canInvite: boolean;
}) {
  const mutation = useInviteTenantAdmin(tenantId);
  const [email, setEmail] = useState("");
  const [editing, setEditing] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const { label, variant, Icon } = STATUS[firstAdmin.status];
  const expired =
    firstAdmin.status === "INVITED" && (!firstAdmin.inviteExpiresAt || new Date(firstAdmin.inviteExpiresAt) < new Date());

  async function send(withEmail?: string) {
    setMessage(null);
    try {
      const result = await mutation.mutateAsync(withEmail);
      setEditing(false);
      setEmail("");
      setMessage({ text: `Invitation sent to ${result.emailMasked ?? "the admin"}.`, error: false });
    } catch (error) {
      setMessage({
        text: isApiClientError(error) ? error.message : "The invitation could not be sent. Please try again.",
        error: true,
      });
    }
  }

  const emailForm = (submitLabel: string) => (
    <form
      className="flex flex-col gap-2 sm:flex-row sm:items-end"
      onSubmit={(event) => {
        event.preventDefault();
        void send(email.trim());
      }}
    >
      <div className="flex flex-1 flex-col gap-1.5">
        <Label htmlFor="tenant-admin-email">Admin email</Label>
        <Input
          id="tenant-admin-email"
          type="email"
          required
          autoComplete="off"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
        />
      </div>
      <Button type="submit" disabled={mutation.isPending || !email.trim()} aria-busy={mutation.isPending}>
        {mutation.isPending ? "Sending…" : submitLabel}
      </Button>
    </form>
  );

  return (
    <section aria-labelledby="tenant-admin-heading" className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="tenant-admin-heading" className="text-base font-medium text-foreground">
          Tenant Admin
        </h2>
        <Badge variant={variant}>
          <Icon aria-hidden="true" />
          {label}
        </Badge>
      </div>

      {firstAdmin.status === "NONE" ? (
        <p className="text-sm text-muted-foreground">
          {canInvite
            ? `${tenantName} has no Tenant Admin yet. Invite one to let the institute sign in.`
            : "A Tenant Admin is invited automatically when the tenant is approved."}
        </p>
      ) : (
        <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Email</dt>
          <dd className="break-all text-foreground">{firstAdmin.emailMasked}</dd>
          {firstAdmin.status === "INVITED" ? (
            <>
              <dt className="text-muted-foreground">Invited</dt>
              <dd className="text-foreground">{firstAdmin.invitedAt ? formatDateTime(firstAdmin.invitedAt) : "—"}</dd>
              <dt className="text-muted-foreground">Link</dt>
              <dd className="text-foreground">
                {expired ? "Expired or not sent yet - resend it" : `Valid until ${formatDateTime(firstAdmin.inviteExpiresAt!)}`}
              </dd>
            </>
          ) : null}
        </dl>
      )}

      {canInvite && firstAdmin.status === "NONE" ? (
        <div className="flex flex-col gap-2">
          <Button type="button" className="w-fit" onClick={() => send()} disabled={mutation.isPending} aria-busy={mutation.isPending}>
            {mutation.isPending ? "Sending…" : "Invite with the registration contact email"}
          </Button>
          {emailForm("Invite this email")}
        </div>
      ) : null}

      {canInvite && firstAdmin.status === "INVITED" ? (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" onClick={() => send()} disabled={mutation.isPending} aria-busy={mutation.isPending}>
              {mutation.isPending ? "Sending…" : "Resend invitation"}
            </Button>
            <Button type="button" variant="ghost" onClick={() => setEditing((value) => !value)} aria-expanded={editing}>
              Change email
            </Button>
          </div>
          {editing ? emailForm("Save and resend") : null}
        </div>
      ) : null}

      <p role="status" aria-live="polite" className="text-sm text-muted-foreground empty:hidden">
        {message && !message.error ? message.text : ""}
      </p>
      <p role="alert" className="text-sm text-destructive empty:hidden">
        {message?.error ? message.text : ""}
      </p>
    </section>
  );
}
