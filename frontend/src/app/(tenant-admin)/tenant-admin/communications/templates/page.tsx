"use client";

import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageSystemMessaging, canSendCommunications } from "@/lib/auth/permissions";
import {
  useArchiveTemplate,
  useCommunicationTemplates,
  useCreateTemplate,
  useEventTemplates,
  useRecordWhatsAppStatus,
  useUpdateEventTemplate,
  useUpdateTemplate,
  type EventTemplateView,
  type TemplateInput,
  type TemplateView,
  type WhatsAppStatus,
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
import { Textarea } from "@/components/ui/textarea";

const EVENT_LABELS: Record<string, string> = {
  PAYMENT_CONFIRMED: "Payment confirmed",
  PAYMENT_REJECTED: "Payment rejected",
  PAYMENT_REFUNDED: "Refund processed",
  ACCOUNT_SETUP_INVITE: "Account set-up invitation",
  PASSWORD_RESET: "Password reset",
};

const WHATSAPP_LABELS: Record<WhatsAppStatus, string> = {
  NOT_SUBMITTED: "WhatsApp: not submitted",
  SUBMITTED: "WhatsApp: awaiting approval",
  APPROVED: "WhatsApp: approved",
  REJECTED: "WhatsApp: rejected",
};

function errorText(error: unknown): string {
  return isApiClientError(error)
    ? (error.fieldErrors[0]?.message ?? error.message)
    : "Something went wrong. Please try again.";
}

function useNotice() {
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    if (!notice) return;
    const timeout = setTimeout(() => setNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [notice]);
  return [notice, setNotice] as const;
}

/**
 * Tenant Admin → Communication → Message templates (Wave 11, PAR-12-04,
 * PAR-22-02). Broadcast templates: `COMMUNICATIONS` VIEW / CREATE_EDIT.
 * Recording a WhatsApp template's provider approval and editing the system
 * (payment / account) messages: Tenant Admin only. All re-checked server-side.
 */
export default function MessageTemplatesPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Message templates"
        description="Reusable broadcast messages, and the automatic messages the system sends."
      />
      <BroadcastTemplatesSection
        canEdit={canSendCommunications(role)}
        canRecordWhatsApp={canManageSystemMessaging(role)}
      />
      <SystemTemplatesSection canEdit={canManageSystemMessaging(role)} />
    </div>
  );
}

// ---------------------------------------------------------------------------
// Broadcast templates
// ---------------------------------------------------------------------------

function BroadcastTemplatesSection({ canEdit, canRecordWhatsApp }: { canEdit: boolean; canRecordWhatsApp: boolean }) {
  const query = useCommunicationTemplates(false);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useNotice();
  return (
    <Card>
      <CardHeader>
        <CardTitle>Broadcast templates</CardTitle>
        <CardDescription>
          Use {"{{name}}"} and {"{{instituteName}}"} to personalise. A template must be approved by the WhatsApp
          provider before it can be sent on WhatsApp; editing it needs a new approval.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div role="status" aria-live="polite">
          {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
        </div>
        {canEdit && !creating ? (
          <div>
            <Button type="button" onClick={() => setCreating(true)}>
              New template
            </Button>
          </div>
        ) : null}
        {creating ? (
          <TemplateForm
            onCancel={() => setCreating(false)}
            onSaved={() => {
              setCreating(false);
              setNotice("Template created.");
            }}
          />
        ) : null}
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading templates…"
          loginPath="/login"
          isEmpty={(data) => data.length === 0}
          emptyState={{ title: "No templates yet", description: "Create one to reuse a message." }}
        >
          {(templates) => (
            <ul className="flex flex-col gap-3">
              {templates.map((template) => (
                <li key={template.id}>
                  <TemplateItem
                    template={template}
                    canEdit={canEdit}
                    canRecordWhatsApp={canRecordWhatsApp}
                    onNotice={setNotice}
                  />
                </li>
              ))}
            </ul>
          )}
        </QueryStateBoundary>
      </CardContent>
    </Card>
  );
}

function TemplateForm({
  template,
  onCancel,
  onSaved,
}: {
  template?: TemplateView;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const create = useCreateTemplate();
  const update = useUpdateTemplate();
  const mutation = template ? update : create;
  const [values, setValues] = useState<TemplateInput>({
    name: template?.name ?? "",
    subject: template?.subject ?? "",
    body: template?.body ?? "",
    smsBody: template?.smsBody ?? "",
  });
  const idPrefix = template ? `template-${template.id}` : "template-new";

  function submit(event: React.FormEvent) {
    event.preventDefault();
    const input = { ...values, smsBody: values.smsBody?.trim() ? values.smsBody : null };
    if (template) {
      update.mutate({ id: template.id, input }, { onSuccess: onSaved });
    } else {
      create.mutate(input, { onSuccess: onSaved });
    }
  }

  return (
    <form onSubmit={submit} noValidate className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${idPrefix}-name`}>Name</Label>
        <Input
          id={`${idPrefix}-name`}
          maxLength={120}
          value={values.name}
          onChange={(e) => setValues({ ...values, name: e.target.value })}
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${idPrefix}-subject`}>Subject</Label>
        <Input
          id={`${idPrefix}-subject`}
          maxLength={200}
          value={values.subject}
          onChange={(e) => setValues({ ...values, subject: e.target.value })}
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${idPrefix}-body`}>Message</Label>
        <Textarea
          id={`${idPrefix}-body`}
          rows={5}
          maxLength={5000}
          value={values.body}
          onChange={(e) => setValues({ ...values, body: e.target.value })}
        />
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor={`${idPrefix}-sms`}>SMS / WhatsApp text (optional, up to 640 characters)</Label>
        <Textarea
          id={`${idPrefix}-sms`}
          rows={3}
          maxLength={640}
          value={values.smsBody ?? ""}
          onChange={(e) => setValues({ ...values, smsBody: e.target.value })}
        />
      </div>
      {mutation.isError ? (
        <Alert variant="destructive">
          <AlertDescription>{errorText(mutation.error)}</AlertDescription>
        </Alert>
      ) : null}
      <div className="flex gap-2">
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save template"}
        </Button>
        <Button type="button" variant="outline" onClick={onCancel}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function TemplateItem({
  template,
  canEdit,
  canRecordWhatsApp,
  onNotice,
}: {
  template: TemplateView;
  canEdit: boolean;
  canRecordWhatsApp: boolean;
  onNotice: (message: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const archive = useArchiveTemplate();
  if (editing) {
    return (
      <TemplateForm
        template={template}
        onCancel={() => setEditing(false)}
        onSaved={() => {
          setEditing(false);
          onNotice("Template saved.");
        }}
      />
    );
  }
  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <p className="font-medium text-foreground">{template.name}</p>
          <p className="text-sm text-muted-foreground">{template.subject}</p>
        </div>
        <Badge variant={template.whatsAppStatus === "APPROVED" ? "secondary" : "outline"}>
          {WHATSAPP_LABELS[template.whatsAppStatus]}
        </Badge>
      </div>
      <p className="whitespace-pre-wrap text-sm text-foreground">{template.body}</p>
      {template.whatsAppStatusReason ? (
        <p className="text-xs text-muted-foreground">Note: {template.whatsAppStatusReason}</p>
      ) : null}
      {canRecordWhatsApp ? <WhatsAppApproval template={template} onNotice={onNotice} /> : null}
      {canEdit ? (
        <div className="flex gap-2">
          <Button type="button" variant="outline" size="sm" onClick={() => setEditing(true)}>
            Edit
          </Button>
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={archive.isPending}
            onClick={() => archive.mutate(template.id, { onSuccess: () => onNotice("Template archived.") })}
          >
            Archive
          </Button>
        </div>
      ) : null}
      {archive.isError ? <p className="text-sm text-destructive">{errorText(archive.error)}</p> : null}
    </div>
  );
}

/** PAR-22-02 - records the provider's decision; the backend enforces the transition rules. */
function WhatsAppApproval({ template, onNotice }: { template: TemplateView; onNotice: (message: string) => void }) {
  const record = useRecordWhatsAppStatus();
  const [providerName, setProviderName] = useState(template.whatsAppTemplateName ?? "");
  const [reason, setReason] = useState("");
  const idPrefix = `wa-${template.id}`;

  if (template.whatsAppStatus === "NOT_SUBMITTED" || template.whatsAppStatus === "REJECTED") {
    return (
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-name`}>Provider template name</Label>
          <Input
            id={`${idPrefix}-name`}
            placeholder="e.g. term_start"
            value={providerName}
            onChange={(e) => setProviderName(e.target.value)}
          />
        </div>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={record.isPending}
          onClick={() =>
            record.mutate(
              { id: template.id, action: "SUBMIT", providerTemplateName: providerName },
              { onSuccess: () => onNotice("Marked as submitted to WhatsApp.") }
            )
          }
        >
          Mark as submitted
        </Button>
        {record.isError ? <p className="text-sm text-destructive">{errorText(record.error)}</p> : null}
      </div>
    );
  }
  if (template.whatsAppStatus === "SUBMITTED") {
    return (
      <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor={`${idPrefix}-reason`}>Rejection reason (if rejected)</Label>
          <Input id={`${idPrefix}-reason`} value={reason} onChange={(e) => setReason(e.target.value)} />
        </div>
        <Button
          type="button"
          size="sm"
          disabled={record.isPending}
          onClick={() =>
            record.mutate({ id: template.id, action: "APPROVE" }, { onSuccess: () => onNotice("Approval recorded.") })
          }
        >
          Record approved
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={record.isPending}
          onClick={() =>
            record.mutate(
              { id: template.id, action: "REJECT", reason },
              { onSuccess: () => onNotice("Rejection recorded.") }
            )
          }
        >
          Record rejected
        </Button>
        {record.isError ? <p className="text-sm text-destructive">{errorText(record.error)}</p> : null}
      </div>
    );
  }
  return template.whatsAppStatusChangedAt ? (
    <p className="text-xs text-muted-foreground">
      Approved as “{template.whatsAppTemplateName}” on {formatDateTime(template.whatsAppStatusChangedAt)}.
    </p>
  ) : null;
}

// ---------------------------------------------------------------------------
// System (event) templates
// ---------------------------------------------------------------------------

function SystemTemplatesSection({ canEdit }: { canEdit: boolean }) {
  const query = useEventTemplates();
  const [notice, setNotice] = useNotice();
  return (
    <Card>
      <CardHeader>
        <CardTitle>System messages</CardTitle>
        <CardDescription>
          Sent automatically by email (and shown in-app) for payments and account access. Only the listed
          placeholders can be used.
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div role="status" aria-live="polite">
          {notice ? <p className="text-sm text-muted-foreground">{notice}</p> : null}
        </div>
        <QueryStateBoundary query={query} loadingLabel="Loading system messages…" loginPath="/login">
          {(templates) => (
            <ul className="flex flex-col gap-3">
              {templates.map((template) => (
                <li key={template.key}>
                  <SystemTemplateItem
                    template={template}
                    canEdit={canEdit}
                    onSaved={() => setNotice("Message saved.")}
                  />
                </li>
              ))}
            </ul>
          )}
        </QueryStateBoundary>
      </CardContent>
    </Card>
  );
}

function SystemTemplateItem({
  template,
  canEdit,
  onSaved,
}: {
  template: EventTemplateView;
  canEdit: boolean;
  onSaved: () => void;
}) {
  const update = useUpdateEventTemplate();
  const [editing, setEditing] = useState(false);
  const [subject, setSubject] = useState(template.subject ?? "");
  const [body, setBody] = useState(template.body ?? "");
  const idPrefix = `event-${template.key}`;
  const placeholders = template.variables.map((v) => `{{${v}}}`).join(", ");

  return (
    <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
      <div>
        <p className="font-medium text-foreground">{EVENT_LABELS[template.key] ?? template.key}</p>
        <p className="text-xs text-muted-foreground">
          Placeholders: {placeholders}
          {template.requiredVariables.length > 0
            ? ` (required: ${template.requiredVariables.map((v) => `{{${v}}}`).join(", ")})`
            : ""}
        </p>
      </div>
      {editing ? (
        <form
          noValidate
          className="flex flex-col gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            update.mutate(
              { key: template.key, subject, body },
              {
                onSuccess: () => {
                  setEditing(false);
                  onSaved();
                },
              }
            );
          }}
        >
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-subject`}>Subject</Label>
            <Input
              id={`${idPrefix}-subject`}
              maxLength={200}
              value={subject}
              onChange={(e) => setSubject(e.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor={`${idPrefix}-body`}>Message</Label>
            <Textarea
              id={`${idPrefix}-body`}
              rows={6}
              maxLength={5000}
              value={body}
              onChange={(e) => setBody(e.target.value)}
            />
          </div>
          {update.isError ? (
            <Alert variant="destructive">
              <AlertDescription>{errorText(update.error)}</AlertDescription>
            </Alert>
          ) : null}
          <div className="flex gap-2">
            <Button type="submit" disabled={update.isPending}>
              {update.isPending ? "Saving…" : "Save"}
            </Button>
            <Button type="button" variant="outline" onClick={() => setEditing(false)}>
              Cancel
            </Button>
          </div>
        </form>
      ) : template.configured ? (
        <>
          <p className="text-sm text-muted-foreground">{template.subject}</p>
          <p className="whitespace-pre-wrap text-sm text-foreground">{template.body}</p>
        </>
      ) : (
        <p className="text-sm text-muted-foreground">Not set up yet. Save a message to start sending it.</p>
      )}
      {canEdit && !editing ? (
        <div>
          <Button type="button" variant="outline" size="sm" onClick={() => setEditing(true)}>
            Edit
          </Button>
        </div>
      ) : null}
    </div>
  );
}
