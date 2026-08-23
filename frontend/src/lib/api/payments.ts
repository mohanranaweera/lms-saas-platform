import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { ledgerKeys } from "./ledger";

/**
 * Typed client + React Query hooks for `payment-management`'s authenticated
 * endpoints (`/api/v1/orders/**`, `/api/v1/payments/**` — see
 * `OrderController`/`PaymentController`/`RefundController`). Follows
 * `lib/api/courses.ts`'s conventions exactly: full `/api/v1/...` paths (not
 * the `/v1/...` shorthand `students.ts`/`teachers.ts` use), every call
 * through `useAuth().authorizedFetch("tenant", ...)`.
 *
 * Enrollment activation is never computed here or anywhere in the frontend —
 * `OrderPaymentStatusResponse`/`PaymentResponse.status` are the only source
 * of truth this client ever reads, and only ever via a real API response
 * (see `useOrderPaymentStatus`'s polling doc comment).
 */

export type OrderStatus = "PLACED" | "PENDING";
export type PaymentStatus = "PENDING" | "CONFIRMED" | "REJECTED" | "REFUNDED";

/** Mirrors `OrderResponse` (backend `.../paymentmanagement/order/web/dto`). */
export interface OrderResponse {
  id: string;
  studentId: string;
  courseId: string;
  amount: number;
  currency: string;
  status: OrderStatus;
  createdAt: string;
  updatedAt: string;
}

/** Mirrors `OrderCreateRequest` — deliberately only `courseId`, no price/tenantId field exists. */
export interface OrderCreateRequest {
  courseId: string;
}

/**
 * Mirrors `OrderPaymentStatusResponse` — the ONLY legitimate source of truth
 * for "is this order paid." Never derive paid/confirmed state from a redirect
 * query param or any other client-visible signal.
 */
export interface OrderPaymentStatusResponse {
  hasPaymentAttempt: boolean;
  paymentId: string | null;
  status: PaymentStatus | null;
  confirmedAt: string | null;
}

/** Mirrors `PaymentInitiationResponse`, returned by `POST /api/v1/orders/{id}/payments`. */
export interface PaymentInitiationResponse {
  paymentId: string;
  orderId: string;
  status: "PENDING";
  gatewayReference: string;
  redirectTarget: string;
}

/** Mirrors `PaymentResponse`. */
export interface PaymentResponse {
  id: string;
  orderId: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  gatewayReference: string | null;
  confirmedAt: string | null;
  createdAt: string;
}

/**
 * Mirrors `RefundCreateRequest`. `idempotencyKey` is optional (backend
 * treats a missing key as "no dedup requested", preserving prior behavior)
 * but every submit from `RefundDialog` sends one — a stable UUID generated
 * once per dialog-open, reused across retries of that same submit attempt so
 * a resubmission (e.g. after a slow/ambiguous network response) replays the
 * original refund instead of creating a second one.
 */
export interface RefundCreateRequest {
  amount: number;
  reason: string;
  idempotencyKey?: string;
}

/** Mirrors `RefundResponse`. */
export interface RefundResponse {
  id: string;
  originalPaymentId: string;
  amount: number;
  reason: string;
  createdAt: string;
}

export const paymentKeys = {
  order: (orderId: string) => ["orders", orderId] as const,
  orderPaymentStatus: (orderId: string) => ["orders", orderId, "payment-status"] as const,
  payment: (paymentId: string) => ["payments", paymentId] as const,
  paymentRefunds: (paymentId: string) => ["payments", paymentId, "refunds"] as const,
};

/**
 * `POST /api/v1/orders` — Student role only, body is `{ courseId }` only
 * (no price/tenantId field exists on this type — structurally impossible to
 * send a client-chosen price). 409 if the course isn't published, 404 if
 * `courseId` doesn't resolve within the caller's tenant.
 */
export function useCreateOrder() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (body: OrderCreateRequest) =>
      authorizedFetch<OrderResponse>("tenant", "/api/v1/orders", {
        method: "POST",
        body: JSON.stringify(body),
      }),
  });
}

/** `GET /api/v1/orders/{id}` — owning student or staff `VIEW`; 403/404 uniform otherwise. */
export function useOrder(orderId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentKeys.order(orderId),
    queryFn: () => authorizedFetch<OrderResponse>("tenant", `/api/v1/orders/${orderId}`),
    enabled: orderId.length > 0,
  });
}

const TERMINAL_PAYMENT_STATUSES: ReadonlySet<PaymentStatus> = new Set([
  "CONFIRMED",
  "REJECTED",
  "REFUNDED",
]);

/**
 * `GET /api/v1/orders/{id}/payment-status` — polled by the awaiting-
 * confirmation screen every 3s until a terminal status is reached. This is
 * the only endpoint that screen reads to decide success/failure; it never
 * stops polling based on anything else (a redirect param, a timer, etc.).
 */
export function useOrderPaymentStatus(orderId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentKeys.orderPaymentStatus(orderId),
    queryFn: () =>
      authorizedFetch<OrderPaymentStatusResponse>(
        "tenant",
        `/api/v1/orders/${orderId}/payment-status`
      ),
    enabled: orderId.length > 0,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      if (status && TERMINAL_PAYMENT_STATUSES.has(status)) {
        return false;
      }
      return 3000;
    },
  });
}

/**
 * `POST /api/v1/orders/{id}/payments` — Student role, owning student only.
 * Initiates a gateway payment attempt. Takes `orderId` as the mutate
 * variable (not a hook param) since the order doesn't exist yet when the
 * checkout screen first renders — mirrors `teachers.ts`'s
 * `useApproveTeacher` shape.
 */
export function useInitiatePayment() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (orderId: string) =>
      authorizedFetch<PaymentInitiationResponse>("tenant", `/api/v1/orders/${orderId}/payments`, {
        method: "POST",
      }),
    onSuccess: (_data, orderId) => {
      queryClient.invalidateQueries({ queryKey: paymentKeys.orderPaymentStatus(orderId) });
    },
  });
}

/** `GET /api/v1/payments/{id}` — owner-student-or-staff-`VIEW`; 403/404 uniform otherwise. */
export function usePayment(paymentId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentKeys.payment(paymentId),
    queryFn: () => authorizedFetch<PaymentResponse>("tenant", `/api/v1/payments/${paymentId}`),
    enabled: paymentId.length > 0,
  });
}

/** `GET /api/v1/payments/{id}/refunds` — same owner-or-staff-`VIEW` rule as payment read. */
export function usePaymentRefunds(paymentId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: paymentKeys.paymentRefunds(paymentId),
    queryFn: () =>
      authorizedFetch<RefundResponse[]>("tenant", `/api/v1/payments/${paymentId}/refunds`),
    enabled: paymentId.length > 0,
  });
}

/**
 * `POST /api/v1/payments/{id}/refunds` — gated server-side by
 * `PAYMENTS_SLIPS`/`APPROVE` (Tenant Admin + Finance Staff only; 403 for
 * every other role including a student). 409 if `amount` exceeds the
 * refundable remainder or the payment isn't `CONFIRMED` — callers must map
 * `error.fieldErrors` (amount/reason) onto the form, falling back to
 * `error.message` as an inline alert.
 */
export function useCreateRefund(paymentId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RefundCreateRequest) =>
      authorizedFetch<RefundResponse>("tenant", `/api/v1/payments/${paymentId}/refunds`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: paymentKeys.paymentRefunds(paymentId) });
      // The Payment Dashboard / Refunds list is derived from the ledger, not
      // from this payment's own detail — invalidate it too so a successful
      // refund is reflected there without a manual reload.
      queryClient.invalidateQueries({ queryKey: ledgerKeys.all });
    },
  });
}
