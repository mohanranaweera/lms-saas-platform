import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `payment-management`'s Payment Slip
 * Intelligence sub-module (`/api/v1/orders/{orderId}/slips`,
 * `/api/v1/payment-slips/**` — see `SlipController`/`SlipReviewController`).
 * Follows `lib/api/payments.ts`/`lib/api/materials.ts`'s conventions exactly
 * (`/v1/...` paths — the client's `NEXT_PUBLIC_API_BASE_URL` already includes
 * the `/api` prefix — every call through
 * `useAuth().authorizedFetch("tenant", ...)`, query-keys object,
 * `onSuccess` cache invalidation).
 *
 * This client performs zero duplicate-detection logic of its own — flags are
 * rendered verbatim from `PaymentSlipResponse.flags`, exactly as the backend
 * computed them (`.claude/rules/payments.md` §3). Enrollment activation is
 * never inferred here either: a slip's `status` field (driven only by a real
 * `approve`/`reject` API response) is the only signal this client surfaces.
 */

export type PaymentSlipStatus = "SUBMITTED" | "UNDER_REVIEW" | "APPROVED" | "REJECTED";
export type SlipFlagType = "DUPLICATE_REFERENCE" | "DUPLICATE_IMAGE_HASH";

/** Mirrors `PaymentSlipFlagResponse` (backend `com.lms.paymentmanagement.slip.web.dto`) field-for-field. */
export interface PaymentSlipFlagResponse {
  id: string;
  flagType: SlipFlagType;
  detectedAt: string;
}

/**
 * Mirrors `PaymentSlipResponse` field-for-field. `flags` is append-only on
 * the backend (never cleared/overwritten on a re-run) — this client always
 * renders the full array it receives, never deduplicates or hides older
 * entries.
 *
 * `studentEmail` is resolved server-side via a batched cross-module lookup
 * and is expected to be non-null in practice (the underlying id is
 * FK-backed), but that is not schema-guaranteed — treat it as nullable, the
 * same as `reviewerEmail`. `reviewerEmail` mirrors `reviewerId`'s
 * nullability — `null` until the slip has been reviewed, populated after
 * approve/reject. `orderAmount`/`orderCurrency` are the order's expected
 * payment amount/currency, for cross-checking against the uploaded slip
 * evidence.
 */
export interface PaymentSlipResponse {
  id: string;
  orderId: string;
  studentId: string;
  referenceNumber: string;
  status: PaymentSlipStatus;
  submittedAt: string;
  reviewerId: string | null;
  reviewedAt: string | null;
  flags: PaymentSlipFlagResponse[];
  studentEmail: string | null;
  reviewerEmail: string | null;
  orderAmount: number;
  orderCurrency: string;
}

/** Mirrors `SlipDownloadUrlResponse`. */
export interface SlipDownloadUrlResponse {
  url: string;
  expiresAt: string;
}

/** Input to `useUploadSlip` — sent as `multipart/form-data` (`referenceNumber` + `file` parts), never JSON. */
export interface SlipUploadInput {
  referenceNumber: string;
  file: File;
}

export interface SlipReviewQueueParams {
  /** Omit entirely for the real pending queue (`SUBMITTED` + `UNDER_REVIEW` combined) — there is no backend "ALL" value. */
  status?: PaymentSlipStatus;
  page?: number;
  size?: number;
  sort?: string;
}

/** Mirrors `SlipApproveRequest` — `overrideReason` is optional, required only when the slip carries active flags (enforced server-side). */
export interface SlipApproveRequest {
  overrideReason?: string;
}

/** Mirrors `SlipRejectRequest` — `reason` is required, non-blank. */
export interface SlipRejectRequest {
  reason: string;
}

export const paymentSlipKeys = {
  all: ["payment-slips"] as const,
  detail: (slipId: string) => [...paymentSlipKeys.all, "detail", slipId] as const,
  reviewQueueAll: () => [...paymentSlipKeys.all, "review-queue"] as const,
  reviewQueue: (params?: SlipReviewQueueParams) =>
    [...paymentSlipKeys.reviewQueueAll(), params ?? {}] as const,
};

/**
 * `POST /api/v1/orders/{orderId}/slips` (multipart) — Student role, owning
 * student only (server-resolved, never a client-supplied id). The response's
 * `flags` array may already be non-empty — duplicate checks run synchronously
 * at upload time — so callers must render it immediately on success, never
 * assume an empty list.
 */
export function useUploadSlip(orderId: string) {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ referenceNumber, file }: SlipUploadInput) => {
      const formData = new FormData();
      formData.append("referenceNumber", referenceNumber);
      formData.append("file", file);
      return authorizedFetch<PaymentSlipResponse>("tenant", `/v1/orders/${orderId}/slips`, {
        method: "POST",
        body: formData,
      });
    },
  });
}

/**
 * `GET /api/v1/payment-slips/{slipId}` — owner student OR staff
 * `PAYMENTS_SLIPS`/`VIEW`. A student requesting another student's slip
 * (even same-tenant) gets 404, never 403 (anti-enumeration).
 */
export function useSlip(slipId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentSlipKeys.detail(slipId),
    queryFn: () => authorizedFetch<PaymentSlipResponse>("tenant", `/v1/payment-slips/${slipId}`),
    enabled: slipId.length > 0,
  });
}

/**
 * `GET /api/v1/payment-slips/{slipId}/download-url` — a **mutation**, not a
 * query, mirroring `useMaterialDownloadUrl` exactly: a short-lived signed URL
 * must be fetched fresh on every "View" click and never cached/prefetched/
 * stored. Callers trigger it imperatively (`mutateAsync(slipId)`) and
 * immediately `window.open` the returned URL — never render or persist it.
 */
export function useSlipDownloadUrl() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (slipId: string) =>
      authorizedFetch<SlipDownloadUrlResponse>(
        "tenant",
        `/v1/payment-slips/${slipId}/download-url`
      ),
  });
}

function buildReviewQueueQuery(params?: SlipReviewQueueParams): string {
  const search = new URLSearchParams();
  if (params?.status) search.set("status", params.status);
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  if (params?.sort) search.set("sort", params.sort);
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

/**
 * `GET /api/v1/payment-slips/review-queue` — staff `PAYMENTS_SLIPS`/`VIEW`
 * only (403 for a student). Omitting `status` returns the backend's real
 * default pending queue (`SUBMITTED` + `UNDER_REVIEW` combined) — there is no
 * "ALL" enum value, so callers must map their own "pending" filter sentinel
 * to `status: undefined`, never send a fabricated value.
 */
export function useSlipReviewQueue(params?: SlipReviewQueueParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildReviewQueueQuery(params);
  return useQuery({
    queryKey: paymentSlipKeys.reviewQueue(params),
    queryFn: () =>
      authorizedFetch<PageResponse<PaymentSlipResponse>>(
        "tenant",
        `/v1/payment-slips/review-queue${queryString}`
      ),
  });
}

/**
 * `POST /api/v1/payment-slips/{slipId}/approve` — staff
 * `PAYMENTS_SLIPS`/`APPROVE` only (Tenant Admin, Finance Staff). `409` if the
 * slip has active flags and no/blank `overrideReason` was supplied — this
 * hook sends whatever body the caller provides; it never decides on its own
 * whether an override reason is required (that is the backend's exclusive,
 * re-verified authority).
 */
export function useApproveSlip(slipId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SlipApproveRequest) =>
      authorizedFetch<PaymentSlipResponse>("tenant", `/v1/payment-slips/${slipId}/approve`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paymentSlipKeys.detail(slipId) });
      queryClient.invalidateQueries({ queryKey: paymentSlipKeys.reviewQueueAll() });
    },
  });
}

/**
 * `POST /api/v1/payment-slips/{slipId}/reject` — same auth as
 * `useApproveSlip`. `reason` is required, non-blank.
 */
export function useRejectSlip(slipId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SlipRejectRequest) =>
      authorizedFetch<PaymentSlipResponse>("tenant", `/v1/payment-slips/${slipId}/reject`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paymentSlipKeys.detail(slipId) });
      queryClient.invalidateQueries({ queryKey: paymentSlipKeys.reviewQueueAll() });
    },
  });
}
