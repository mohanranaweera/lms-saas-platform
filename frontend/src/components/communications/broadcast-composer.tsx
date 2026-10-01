"use client";

import { useMemo, useState } from "react";
import {
  AUDIENCE_LABELS,
  CHANNEL_LABELS,
  useAudiencePreview,
  useCommunicationChannels,
  useCommunicationTemplates,
  useSendBroadcast,
  type AudienceInput,
  type AudienceType,
  type BroadcastView,
  type DeliveryChannel,
  type PaymentStatusFilter,
  type TemplateView,
} from "@/lib/api/communications";
import { useCourses } from "@/lib/api/courses";
import { useStudents } from "@/lib/api/students";
import { isApiClientError } from "@/lib/api/error";
import { randomUuid } from "@/lib/uuid";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";

const STAFF_AUDIENCES: AudienceType[] = ["ALL_STUDENTS", "COURSE", "SELECTED_STUDENTS", "PAYMENT_STATUS", "ALL_TEACHERS"];
const NO_TEMPLATE = "NONE";
const ANY_COURSE = "ANY";
const SMS_LIMIT = 640;

const CHANNEL_REASONS: Record<string, string> = {
  NOT_CONFIGURED: "not set up for your institute",
  PROVIDER_DISABLED_IN_ENVIRONMENT: "provider not available",
};

function errorText(error: unknown): string {
  return isApiClientError(error)
    ? (error.fieldErrors[0]?.message ?? error.message)
    : "Something went wrong. Please try again.";
}

/**
 * Broadcast composer (Wave 11). Staff choose any approved audience; a teacher
 * (`mode="teacher"`) can only message a course they own, in-app or by email -
 * the backend enforces both, this only shapes the form.
 *
 * Sending is two steps: preview the audience (the backend resolves the exact
 * recipients and per-channel reach), then send. The request carries a client
 * request id that stays the same until a send succeeds, so a double click or a
 * network retry can never send twice.
 */
export function BroadcastComposer({
  mode,
  onSent,
}: {
  mode: "staff" | "teacher";
  onSent: (broadcast: BroadcastView) => void;
}) {
  const isTeacher = mode === "teacher";
  const channelsQuery = useCommunicationChannels();
  const templatesQuery = useCommunicationTemplates();
  const coursesQuery = useCourses();
  const preview = useAudiencePreview();
  const send = useSendBroadcast();

  const [audienceType, setAudienceType] = useState<AudienceType>(isTeacher ? "COURSE" : "ALL_STUDENTS");
  const [courseId, setCourseId] = useState("");
  const [paymentStatus, setPaymentStatus] = useState<PaymentStatusFilter>("PENDING");
  const [studentIds, setStudentIds] = useState<Set<string>>(new Set());
  const [templateId, setTemplateId] = useState("");
  const [subject, setSubject] = useState("");
  const [body, setBody] = useState("");
  const [smsBody, setSmsBody] = useState("");
  const [channels, setChannels] = useState<Set<DeliveryChannel>>(new Set(["IN_APP"]));
  const [clientRequestId, setClientRequestId] = useState(() => randomUuid());
  const [previewedFor, setPreviewedFor] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const audience: AudienceInput = useMemo(
    () => ({
      type: audienceType,
      courseId: audienceType === "COURSE" || audienceType === "PAYMENT_STATUS" ? courseId || null : null,
      paymentStatus: audienceType === "PAYMENT_STATUS" ? paymentStatus : null,
      studentIds: audienceType === "SELECTED_STUDENTS" ? Array.from(studentIds) : null,
    }),
    [audienceType, courseId, paymentStatus, studentIds]
  );
  const audienceKey = JSON.stringify(audience);
  const previewCurrent = previewedFor === audienceKey && preview.data !== undefined;

  const templates: TemplateView[] = templatesQuery.data ?? [];
  const selectedTemplate = templates.find((template) => template.id === templateId) ?? null;
  const whatsAppTemplateApproved = selectedTemplate?.whatsAppStatus === "APPROVED";
  const textChannelChosen = channels.has("SMS") || channels.has("WHATSAPP");

  function applyTemplate(id: string) {
    setTemplateId(id === NO_TEMPLATE ? "" : id);
    const template = templates.find((candidate) => candidate.id === id);
    if (template) {
      setSubject(template.subject);
      setBody(template.body);
      setSmsBody(template.smsBody ?? "");
    }
    if (!template || template.whatsAppStatus !== "APPROVED") {
      setChannels((current) => {
        const next = new Set(current);
        next.delete("WHATSAPP");
        return next;
      });
    }
  }

  function toggleChannel(channel: DeliveryChannel, checked: boolean) {
    setChannels((current) => {
      const next = new Set(current);
      if (checked) next.add(channel);
      else next.delete(channel);
      return next;
    });
  }

  function validateAudience(): string | null {
    if (audienceType === "COURSE" && !courseId) return "Choose a course.";
    if (audienceType === "SELECTED_STUDENTS" && studentIds.size === 0) return "Select at least one student.";
    return null;
  }

  function onPreview() {
    const problem = validateAudience();
    setFormError(problem);
    if (problem) return;
    preview.mutate(audience, { onSuccess: () => setPreviewedFor(audienceKey) });
  }

  function onSend() {
    const problem =
      validateAudience() ??
      (!subject.trim() ? "Enter a subject." : null) ??
      (!body.trim() ? "Enter a message." : null) ??
      (channels.size === 0 ? "Choose at least one channel." : null) ??
      (textChannelChosen && !smsBody.trim() && body.trim().length > SMS_LIMIT
        ? `Add a short SMS / WhatsApp version (at most ${SMS_LIMIT} characters).`
        : null);
    setFormError(problem);
    if (problem) return;
    send.mutate(
      {
        clientRequestId,
        subject: subject.trim(),
        body: body.trim(),
        smsBody: smsBody.trim() || null,
        templateId: templateId || null,
        channels: Array.from(channels),
        audience,
      },
      {
        onSuccess: (broadcast) => {
          setClientRequestId(randomUuid());
          onSent(broadcast);
        },
      }
    );
  }

  const courses = coursesQuery.data?.content ?? [];

  return (
    <div className="flex flex-col gap-6">
      <Card>
        <CardHeader>
          <CardTitle>1. Who should receive it?</CardTitle>
          <CardDescription>
            {isTeacher
              ? "You can message the students currently enrolled in your own courses."
              : "Suspended accounts are always left out."}
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {!isTeacher ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="audience-type">Audience</Label>
              <Select value={audienceType} onValueChange={(value) => value && setAudienceType(value as AudienceType)}>
                <SelectTrigger id="audience-type" className="w-full sm:w-80">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {STAFF_AUDIENCES.map((option) => (
                    <SelectItem key={option} value={option}>
                      {AUDIENCE_LABELS[option]}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : null}

          {audienceType === "COURSE" || audienceType === "PAYMENT_STATUS" ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="audience-course">
                {audienceType === "PAYMENT_STATUS" ? "Course (optional)" : "Course"}
              </Label>
              <Select
                value={courseId || ANY_COURSE}
                onValueChange={(value) => setCourseId(!value || value === ANY_COURSE ? "" : value)}
              >
                <SelectTrigger id="audience-course" className="w-full sm:w-80">
                  <SelectValue placeholder={coursesQuery.isPending ? "Loading courses…" : "Select a course"} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={ANY_COURSE}>
                    {audienceType === "PAYMENT_STATUS" ? "Any course" : "Select a course"}
                  </SelectItem>
                  {courses.map((course) => (
                    <SelectItem key={course.id} value={course.id}>
                      {course.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              {isTeacher && !coursesQuery.isPending && courses.length === 0 ? (
                <p className="text-sm text-muted-foreground">You don&apos;t have any courses yet.</p>
              ) : null}
            </div>
          ) : null}

          {audienceType === "PAYMENT_STATUS" ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="audience-payment">Payment status</Label>
              <Select
                value={paymentStatus}
                onValueChange={(value) => value && setPaymentStatus(value as PaymentStatusFilter)}
              >
                <SelectTrigger id="audience-payment" className="w-full sm:w-80">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="PENDING">Payment pending</SelectItem>
                  <SelectItem value="PAID">Paid</SelectItem>
                  <SelectItem value="REJECTED">Payment rejected</SelectItem>
                </SelectContent>
              </Select>
            </div>
          ) : null}

          {audienceType === "SELECTED_STUDENTS" ? (
            <StudentPicker selected={studentIds} onChange={setStudentIds} />
          ) : null}

          <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
            <Button type="button" variant="outline" onClick={onPreview} disabled={preview.isPending}>
              {preview.isPending ? "Checking…" : "Preview recipients"}
            </Button>
            <div role="status" aria-live="polite" className="text-sm">
              {previewCurrent && preview.data ? (
                <span>
                  <strong>{preview.data.recipientCount}</strong>{" "}
                  {preview.data.recipientCount === 1 ? "recipient" : "recipients"} · email{" "}
                  {preview.data.reachByChannel.EMAIL}, phone {preview.data.reachByChannel.SMS}
                  {preview.data.sampleNames.length > 0
                    ? ` · e.g. ${preview.data.sampleNames.slice(0, 3).join(", ")}`
                    : ""}
                </span>
              ) : null}
            </div>
          </div>
          {preview.isError ? (
            <Alert variant="destructive">
              <AlertDescription>{errorText(preview.error)}</AlertDescription>
            </Alert>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>2. Message</CardTitle>
          <CardDescription>
            You can personalise with {"{{name}}"} and {"{{instituteName}}"}. Other placeholders are rejected.
          </CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          {templates.length > 0 ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="message-template">Start from a template</Label>
              <Select value={templateId || NO_TEMPLATE} onValueChange={(value) => value && applyTemplate(value)}>
                <SelectTrigger id="message-template" className="w-full sm:w-80">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NO_TEMPLATE}>No template</SelectItem>
                  {templates.map((template) => (
                    <SelectItem key={template.id} value={template.id}>
                      {template.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : null}
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="message-subject">Subject</Label>
            <Input
              id="message-subject"
              maxLength={200}
              value={subject}
              onChange={(event) => setSubject(event.target.value)}
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="message-body">Message</Label>
            <Textarea
              id="message-body"
              rows={6}
              maxLength={5000}
              value={body}
              onChange={(event) => setBody(event.target.value)}
            />
          </div>
          {textChannelChosen ? (
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="message-sms">SMS / WhatsApp text (optional)</Label>
              <Textarea
                id="message-sms"
                rows={3}
                maxLength={SMS_LIMIT}
                value={smsBody}
                aria-describedby="message-sms-help"
                onChange={(event) => setSmsBody(event.target.value)}
              />
              <p id="message-sms-help" className="text-xs text-muted-foreground">
                {smsBody.length}/{SMS_LIMIT}. Leave empty to send the message above (only if it is short enough).
              </p>
            </div>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>3. Channels</CardTitle>
          <CardDescription>Recipients who turned a channel off, or have no address for it, are skipped.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-2">
          {(channelsQuery.data ?? []).map((option) => {
            const needsTemplate = option.channel === "WHATSAPP" && !whatsAppTemplateApproved;
            const disabled = !option.available || !option.allowedForYou || needsTemplate;
            const note = !option.allowedForYou
              ? "not available to teachers"
              : !option.available
                ? (CHANNEL_REASONS[option.reason ?? ""] ?? "not available")
                : needsTemplate
                  ? "choose a template with approved WhatsApp status"
                  : null;
            return (
              <label key={option.channel} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  disabled={disabled}
                  checked={channels.has(option.channel) && !disabled}
                  onChange={(event) => toggleChannel(option.channel, event.target.checked)}
                />
                <span className={disabled ? "text-muted-foreground" : "text-foreground"}>
                  {CHANNEL_LABELS[option.channel]}
                  {note ? ` — ${note}` : ""}
                </span>
              </label>
            );
          })}
        </CardContent>
      </Card>

      {formError ? (
        <Alert variant="destructive">
          <AlertDescription>{formError}</AlertDescription>
        </Alert>
      ) : null}
      {send.isError ? (
        <Alert variant="destructive">
          <AlertDescription>{errorText(send.error)}</AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <Button type="button" onClick={onSend} disabled={!previewCurrent || send.isPending}>
          {send.isPending
            ? "Sending…"
            : previewCurrent && preview.data
              ? `Send to ${preview.data.recipientCount} ${preview.data.recipientCount === 1 ? "recipient" : "recipients"}`
              : "Send"}
        </Button>
        {!previewCurrent ? (
          <p className="text-sm text-muted-foreground">Preview the recipients before sending.</p>
        ) : null}
      </div>
    </div>
  );
}

/** Staff-only picker - mounted only for the "Selected students" audience. Ids are student profile ids. */
function StudentPicker({ selected, onChange }: { selected: Set<string>; onChange: (next: Set<string>) => void }) {
  const studentsQuery = useStudents();
  const [search, setSearch] = useState("");
  const students = useMemo(() => {
    const term = search.trim().toLowerCase();
    const active = (studentsQuery.data ?? []).filter((student) => student.status.toUpperCase() === "ACTIVE");
    return term
      ? active.filter((s) => s.name.toLowerCase().includes(term) || s.email.toLowerCase().includes(term))
      : active;
  }, [studentsQuery.data, search]);

  function toggle(id: string, checked: boolean) {
    const next = new Set(selected);
    if (checked) next.add(id);
    else next.delete(id);
    onChange(next);
  }

  return (
    <fieldset className="flex flex-col gap-2">
      <legend className="text-sm font-medium text-foreground">Students ({selected.size} selected)</legend>
      <Label htmlFor="student-search" className="sr-only">
        Search students
      </Label>
      <Input
        id="student-search"
        placeholder="Search by name or email"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      <div className="max-h-64 overflow-y-auto rounded-lg border border-border p-2">
        {studentsQuery.isPending ? (
          <p className="text-sm text-muted-foreground">Loading students…</p>
        ) : studentsQuery.isError ? (
          <p className="text-sm text-destructive">Students could not be loaded.</p>
        ) : students.length === 0 ? (
          <p className="text-sm text-muted-foreground">No active students match.</p>
        ) : (
          students.map((student) => (
            <label key={student.id} className="flex items-center gap-2 py-1 text-sm">
              <input
                type="checkbox"
                checked={selected.has(student.id)}
                onChange={(event) => toggle(student.id, event.target.checked)}
              />
              <span>
                {student.name} <span className="text-muted-foreground">({student.email})</span>
              </span>
            </label>
          ))
        )}
      </div>
    </fieldset>
  );
}
