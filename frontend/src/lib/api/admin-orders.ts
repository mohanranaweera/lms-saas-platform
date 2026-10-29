import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";
import type { PaymentMethod, PaymentOperationalState } from "./ledger";
import type { PaymentSlipStatus } from "./payment-slips";

/**
 * PAY-HUB-01 staff order reads for the Payments hub (plan §6.1/§6.2):
 * `GET /api/v1/ledger/orders` (one row per order) and
 * `GET /api/v1/orders/{orderId}/admin-detail` (the whole order for the
 * drawer). Both are staff `PAYMENTS_SLIPS`/`VIEW` only — the backend decides;
 * nothing here grants or changes anything.
 */

export interface AdminOrderRow {
  orderId: string;
  orderNumber: number;
  displayOrderNumber: string;
  createdAt: string;
  /** Student profile id (for `/tenant-admin/students/{id}`); `null` with `studentName` if it no longer resolves. */
  studentId: string | null;
  studentName: string | null;
  itemCount: number;
  firstItemTitle: string | null;
  amount: number;
  currency: string;
  operationalState: PaymentOperationalState | null;
  method: PaymentMethod | null;
  /** Set only once a payment is confirmed (the approved slip reference for a bank-transfer slip). */
  reference: string | null;
}

export interface AdminOrderListParams {
  /** `ORD-000123`, `000123` or `123` — exact match. */
  orderNumber?: string;
  /** Inclusive `yyyy-MM-dd` days in the tenant's timezone. */
  from?: string;
  to?: string;
  status?: PaymentOperationalState;
  method?: PaymentMethod;
  page?: number;
  size?: number;
}

export interface AdminOrderDetail {
  orderId: string;
  orderNumber: number;
  displayOrderNumber: string;
  createdAt: string;
  orderStatus: string;
  operationalState: PaymentOperationalState | null;
  student: { studentId: string | null; name: string | null; email: string | null; mobile: string | null };
  currency: string;
  lines: Array<{
    lineNo: number;
    courseId: string | null;
    courseTitle: string | null;
    label: string;
    scope: string;
    unitAmount: number;
    facilityFee: number;
    discountAmount: number;
    lineTotal: number;
  }>;
  subtotal: number | null;
  facilityFee: number | null;
  postage: number | null;
  discount: number | null;
  total: number;
  refunded: number;
  remaining: number;
  delivery: { recipientName: string | null; addressLine: string | null; city: string | null; phone: string | null } | null;
  payments: Array<{
    paymentId: string;
    method: PaymentMethod | null;
    status: string;
    amount: number;
    reference: string | null;
    createdAt: string;
  }>;
  slips: Array<{ slipId: string; referenceNumber: string; status: PaymentSlipStatus; submittedAt: string }>;
}

export const adminOrderKeys = {
  all: ["admin-orders"] as const,
  list: (params: AdminOrderListParams) => [...adminOrderKeys.all, "list", params] as const,
  detail: (orderId: string) => [...adminOrderKeys.all, "detail", orderId] as const,
};

function buildListQuery(params: AdminOrderListParams): string {
  const search = new URLSearchParams();
  if (params.orderNumber?.trim()) search.set("orderNumber", params.orderNumber.trim());
  if (params.from) search.set("from", params.from);
  if (params.to) search.set("to", params.to);
  if (params.status) search.set("status", params.status);
  if (params.method) search.set("method", params.method);
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 20));
  return `?${search.toString()}`;
}

export function useAdminOrders(params: AdminOrderListParams, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: adminOrderKeys.list(params),
    queryFn: () =>
      authorizedFetch<PageResponse<AdminOrderRow>>("tenant", `/v1/ledger/orders${buildListQuery(params)}`),
    enabled: options?.enabled ?? true,
  });
}

export function useAdminOrderDetail(orderId: string | null) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: adminOrderKeys.detail(orderId ?? ""),
    queryFn: () =>
      authorizedFetch<AdminOrderDetail>("tenant", `/v1/orders/${encodeURIComponent(orderId ?? "")}/admin-detail`),
    enabled: !!orderId,
    retry: false,
  });
}
