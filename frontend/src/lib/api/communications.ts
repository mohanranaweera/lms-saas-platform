import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Wave 11 - communications (broadcasts, templates, delivery logs) and the
 * tenant's SMS / WhatsApp provider settings. Contract:
 * `docs/api/notification-management.md` (Communications) and
 * `docs/api/integrations.md` (Messaging providers).
 *
 * The backend decides everything: who may send to whom (COMMUNICATIONS grants,
 * teacher course ownership), which channels are available, who is in an
 * audience, and what each delivery's outcome was. Credentials are write-only:
 * no response ever contains one, only a masked hint.
 */

export type DeliveryChannel = "IN_APP" | "EMAIL" | "SMS" | "WHATSAPP";
export type AudienceType = "ALL_STUDENTS" | "COURSE" | "SELECTED_STUDENTS" | "ALL_TEACHERS" | "PAYMENT_STATUS";
export type PaymentStatusFilter = "PAID" | "PENDING" | "REJECTED";
export type DeliveryStatus = "PENDING" | "SENDING" | "SENT" | "FAILED" | "SKIPPED";
export type WhatsAppStatus = "NOT_SUBMITTED" | "SUBMITTED" | "APPROVED" | "REJECTED";

export const CHANNEL_LABELS: Record<DeliveryChannel, string> = {
  IN_APP: "In-app",
  EMAIL: "Email",
  SMS: "SMS",
  WHATSAPP: "WhatsApp",
};

export const AUDIENCE_LABELS: Record<AudienceType, string> = {
  ALL_STUDENTS: "All students",
  COURSE: "Students in a course",
  SELECTED_STUDENTS: "Selected students",
  ALL_TEACHERS: "All teachers",
  PAYMENT_STATUS: "Students by payment status",
};

/** Mirrors `AudienceRequest`. `studentIds` are student profile ids. */
export interface AudienceInput {
  type: AudienceType;
  courseId?: string | null;
  paymentStatus?: PaymentStatusFilter | null;
  studentIds?: string[] | null;
}

/** Mirrors `ChannelOption`. */
export interface ChannelOption {
  channel: DeliveryChannel;
  available: boolean;
  allowedForYou: boolean;
  reason: string | null;
}

/** Mirrors `AudiencePreview`. */
export interface AudiencePreview {
  recipientCount: number;
  reachByChannel: Record<DeliveryChannel, number>;
  sampleNames: string[];
}

export interface ChannelStats {
  channel: DeliveryChannel;
  pending: number;
  sent: number;
  failed: number;
  skipped: number;
}

/** Mirrors `BroadcastView`. */
export interface BroadcastView {
  id: string;
  subject: string;
  body: string;
  smsBody: string | null;
  channels: DeliveryChannel[];
  audienceType: AudienceType;
  audienceCourseId: string | null;
  audiencePaymentStatus: PaymentStatusFilter | null;
  recipientCount: number;
  senderUserId: string;
  senderRole: string;
  templateId: string | null;
  createdAt: string;
  stats: ChannelStats[];
  replayed: boolean;
}

/** Mirrors `DeliveryView`. `destination` is always masked. */
export interface DeliveryView {
  id: string;
  recipientUserId: string;
  recipientName: string | null;
  channel: DeliveryChannel;
  status: DeliveryStatus;
  attemptCount: number;
  destination: string | null;
  errorCode: string | null;
  nextAttemptAt: string | null;
  completedAt: string | null;
}

/** Mirrors `RecipientDeliveryView`. */
export interface StudentDeliveryView {
  id: string;
  broadcastId: string;
  subject: string | null;
  channel: DeliveryChannel;
  status: DeliveryStatus;
  errorCode: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface SendBroadcastInput {
  clientRequestId: string;
  subject: string;
  body: string;
  smsBody?: string | null;
  templateId?: string | null;
  channels: DeliveryChannel[];
  audience: AudienceInput;
}

/** Mirrors `TemplateView`. */
export interface TemplateView {
  id: string;
  name: string;
  subject: string;
  body: string;
  smsBody: string | null;
  status: "ACTIVE" | "ARCHIVED";
  whatsAppStatus: WhatsAppStatus;
  whatsAppTemplateName: string | null;
  whatsAppStatusReason: string | null;
  whatsAppStatusChangedAt: string | null;
  updatedAt: string;
}

export interface TemplateInput {
  name: string;
  subject: string;
  body: string;
  smsBody?: string | null;
}

/** Mirrors `EventTemplateView`. */
export interface EventTemplateView {
  key: string;
  configured: boolean;
  subject: string | null;
  body: string | null;
  variables: string[];
  requiredVariables: string[];
  updatedAt: string | null;
}

/** Mirrors `MessagingAccountView` - never a credential, only `credentialHint`. */
export interface MessagingAccountView {
  channel: "SMS" | "WHATSAPP";
  configured: boolean;
  id: string | null;
  provider: string | null;
  senderId: string | null;
  credentialHint: string | null;
  status: "ACTIVE" | "DISABLED" | "NOT_CONFIGURED";
  disabledAt: string | null;
  updatedAt: string | null;
  operational: boolean;
}

export interface MessagingSettingsView {
  accounts: MessagingAccountView[];
  availableProviders: { provider: string; channels: string[]; credentialKeys: string[] }[];
}

export interface DeliveryFilters {
  status?: DeliveryStatus | "";
  channel?: DeliveryChannel | "";
  page?: number;
}

export const communicationKeys = {
  all: ["communications"] as const,
  channels: () => [...communicationKeys.all, "channels"] as const,
  broadcasts: () => [...communicationKeys.all, "broadcasts"] as const,
  broadcastList: (page: number) => [...communicationKeys.broadcasts(), "list", page] as const,
  broadcast: (id: string) => [...communicationKeys.broadcasts(), "detail", id] as const,
  deliveries: (id: string, filters: DeliveryFilters) =>
    [...communicationKeys.broadcasts(), "deliveries", id, filters] as const,
  templates: (includeArchived: boolean) => [...communicationKeys.all, "templates", includeArchived] as const,
  eventTemplates: () => [...communicationKeys.all, "event-templates"] as const,
  studentHistory: (studentId: string, page: number) =>
    [...communicationKeys.all, "student-history", studentId, page] as const,
  messaging: () => ["integrations", "messaging"] as const,
};

// ---------------------------------------------------------------------------
// Broadcasts
// ---------------------------------------------------------------------------

export function useCommunicationChannels() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: communicationKeys.channels(),
    queryFn: () => authorizedFetch<ChannelOption[]>("tenant", "/v1/communications/channels"),
  });
}

export function useBroadcasts(page: number) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: communicationKeys.broadcastList(page),
    queryFn: () =>
      authorizedFetch<PageResponse<BroadcastView>>("tenant", `/v1/communications/broadcasts?page=${page}&size=20`),
    placeholderData: keepPreviousData,
  });
}

/** Polls while anything is still queued so the delivery counts move on their own. */
export function useBroadcast(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: communicationKeys.broadcast(id),
    queryFn: () => authorizedFetch<BroadcastView>("tenant", `/v1/communications/broadcasts/${id}`),
    enabled: id.length > 0,
    refetchInterval: (query) => (query.state.data?.stats.some((stats) => stats.pending > 0) ? 5_000 : false),
  });
}

export function useBroadcastDeliveries(id: string, filters: DeliveryFilters) {
  const { authorizedFetch } = useAuth();
  const search = new URLSearchParams({ page: String(filters.page ?? 0), size: "50" });
  if (filters.status) search.set("status", filters.status);
  if (filters.channel) search.set("channel", filters.channel);
  return useQuery({
    queryKey: communicationKeys.deliveries(id, filters),
    queryFn: () =>
      authorizedFetch<PageResponse<DeliveryView>>(
        "tenant",
        `/v1/communications/broadcasts/${id}/deliveries?${search.toString()}`
      ),
    enabled: id.length > 0,
    placeholderData: keepPreviousData,
  });
}

export function useAudiencePreview() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (audience: AudienceInput) =>
      authorizedFetch<AudiencePreview>("tenant", "/v1/communications/audience-preview", {
        method: "POST",
        body: JSON.stringify(audience),
      }),
  });
}

export function useSendBroadcast() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: SendBroadcastInput) =>
      authorizedFetch<BroadcastView>("tenant", "/v1/communications/broadcasts", {
        method: "POST",
        body: JSON.stringify(input),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: communicationKeys.broadcasts() });
    },
  });
}

/** Student detail Notifications tab (`studentId` = student profile id). */
export function useStudentDeliveryHistory(studentId: string, page: number) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: communicationKeys.studentHistory(studentId, page),
    queryFn: () =>
      authorizedFetch<PageResponse<StudentDeliveryView>>(
        "tenant",
        `/v1/communications/students/${studentId}/deliveries?page=${page}&size=20`
      ),
    enabled: studentId.length > 0,
    placeholderData: keepPreviousData,
  });
}

// ---------------------------------------------------------------------------
// Templates
// ---------------------------------------------------------------------------

export function useCommunicationTemplates(includeArchived = false) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: communicationKeys.templates(includeArchived),
    queryFn: () =>
      authorizedFetch<TemplateView[]>(
        "tenant",
        `/v1/communications/templates${includeArchived ? "?includeArchived=true" : ""}`
      ),
  });
}

function useTemplateMutation<TInput>(request: (input: TInput) => { path: string; init: RequestInit }) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const { path, init } = request(input);
      return authorizedFetch<TemplateView>("tenant", path, init);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...communicationKeys.all, "templates"] });
    },
  });
}

export function useCreateTemplate() {
  return useTemplateMutation<TemplateInput>((input) => ({
    path: "/v1/communications/templates",
    init: { method: "POST", body: JSON.stringify(input) },
  }));
}

export function useUpdateTemplate() {
  return useTemplateMutation<{ id: string; input: TemplateInput }>(({ id, input }) => ({
    path: `/v1/communications/templates/${id}`,
    init: { method: "PUT", body: JSON.stringify(input) },
  }));
}

export function useArchiveTemplate() {
  return useTemplateMutation<string>((id) => ({
    path: `/v1/communications/templates/${id}/archive`,
    init: { method: "POST" },
  }));
}

export function useRecordWhatsAppStatus() {
  return useTemplateMutation<{
    id: string;
    action: "SUBMIT" | "APPROVE" | "REJECT";
    providerTemplateName?: string;
    reason?: string;
  }>(({ id, ...body }) => ({
    path: `/v1/communications/templates/${id}/whatsapp-status`,
    init: { method: "POST", body: JSON.stringify(body) },
  }));
}

export function useEventTemplates() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: communicationKeys.eventTemplates(),
    queryFn: () => authorizedFetch<EventTemplateView[]>("tenant", "/v1/communications/event-templates"),
  });
}

export function useUpdateEventTemplate() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ key, subject, body }: { key: string; subject: string; body: string }) =>
      authorizedFetch<EventTemplateView>("tenant", `/v1/communications/event-templates/${key}`, {
        method: "PUT",
        body: JSON.stringify({ subject, body }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: communicationKeys.eventTemplates() });
    },
  });
}

// ---------------------------------------------------------------------------
// Messaging provider settings (INTEGRATIONS)
// ---------------------------------------------------------------------------

export function useMessagingSettings() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: communicationKeys.messaging(),
    queryFn: () => authorizedFetch<MessagingSettingsView>("tenant", "/v1/tenant-admin/integrations/messaging"),
  });
}

function useMessagingMutation<TInput, TResult>(request: (input: TInput) => { path: string; init: RequestInit }) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: TInput) => {
      const { path, init } = request(input);
      return authorizedFetch<TResult>("tenant", path, init);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: communicationKeys.messaging() });
      void queryClient.invalidateQueries({ queryKey: communicationKeys.channels() });
    },
  });
}

/** `credentials` is sent once and never comes back. */
export function useConfigureMessaging() {
  return useMessagingMutation<
    { channel: "SMS" | "WHATSAPP"; provider: string; senderId: string; credentials: Record<string, string> },
    MessagingAccountView
  >(({ channel, ...body }) => ({
    path: `/v1/tenant-admin/integrations/messaging/${channel}`,
    init: { method: "PUT", body: JSON.stringify(body) },
  }));
}

export function useDisableMessaging() {
  return useMessagingMutation<"SMS" | "WHATSAPP", MessagingAccountView>((channel) => ({
    path: `/v1/tenant-admin/integrations/messaging/${channel}/disable`,
    init: { method: "POST" },
  }));
}

export function useTestMessaging() {
  return useMessagingMutation<
    { channel: "SMS" | "WHATSAPP"; destination: string },
    { channel: string; destination: string; providerMessageId: string }
  >(({ channel, destination }) => ({
    path: `/v1/tenant-admin/integrations/messaging/${channel}/test`,
    init: { method: "POST", body: JSON.stringify({ destination }) },
  }));
}
