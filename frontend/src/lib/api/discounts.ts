import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "./error";

/**
 * DISC-01 — tenant-admin discount rules (`/api/v1/admin/discounts`, plan §5).
 *
 * Every call is tenant-scoped server-side (the tenant is the caller's; no
 * tenant field is ever sent) and gated on `DISCOUNTS` VIEW (reads) /
 * CREATE_EDIT (writes). Editing a rule appends a new version server-side;
 * "Delete" archives (history is kept). For a caller without CREATE_EDIT the
 * backend masks `code` as `******`. These hooks only reflect the backend.
 */

export type DiscountType = "ADVANCE_PAYMENT" | "BUNDLE_AMOUNT_OFF" | "BUNDLE_FREE_COURSE" | "DISCOUNT_CODE";
export type SelectionMode = "ALL" | "ANY" | "MIN_COUNT";
export type DiscountValueType = "FIXED" | "PERCENTAGE";
export type DiscountStatusFilter = "ALL" | "ACTIVE" | "INACTIVE";
/** Server-derived (tenant dates): toggle off = INACTIVE; before validFrom = SCHEDULED; after validTo = EXPIRED. */
export type DiscountRuleStatus = "ACTIVE" | "INACTIVE" | "SCHEDULED" | "EXPIRED";

export interface DiscountCourseRef {
  courseId: string;
  name: string;
}

/** Mirrors `DiscountAdminViews.DiscountRuleView`. Money values arrive as JSON numbers. */
export interface DiscountRule {
  id: string;
  name: string;
  description: string | null;
  discountType: DiscountType;
  active: boolean;
  status: DiscountRuleStatus;
  courseIds: string[];
  courses: DiscountCourseRef[];
  selectionMode: SelectionMode;
  minCount: number | null;
  paymentType: "INSTALLMENTS" | null;
  valueType: DiscountValueType | null;
  value: number | string | null;
  /** e.g. "LKR 200.00 OFF", "10% OFF", "1 MONTH FREE". */
  valueLabel: string;
  rewardCourseIds: string[];
  rewardCourses: DiscountCourseRef[];
  freeMonths: number | null;
  /** DISCOUNT_CODE only; "******" for a caller without CREATE_EDIT. */
  code: string | null;
  validFrom: string;
  validTo: string;
  maxUses: number | null;
  minPurchaseAmount: number | string | null;
  currency: string;
  versionNo: number;
  versionCount: number;
  usedCount: number;
  reservedCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface DiscountList {
  items: DiscountRule[];
  counts: { all: number; active: number; inactive: number };
}

/** Mirrors `CourseOptionView` (draft/archived courses are already excluded server-side). */
export interface DiscountCourseOption {
  courseId: string;
  name: string;
  priceLabel: string | null;
  /** MONTHLY-priced — required for free-course rewards and advance-payment courses. */
  monthly: boolean;
  pricingModel: string | null;
  price: number | string | null;
  currency: string | null;
}

/** `POST` / `PUT` body (no tenant field). Fields that don't apply to the type are sent as null. */
export interface DiscountRequest {
  name: string;
  description: string | null;
  discountType: DiscountType;
  courseIds: string[];
  selectionMode: SelectionMode;
  minCount: number | null;
  paymentType: "INSTALLMENTS" | null;
  valueType: DiscountValueType | null;
  value: string | null;
  rewardCourseIds: string[];
  freeMonths: number | null;
  code: string | null;
  validFrom: string;
  validTo: string;
  maxUses: number | null;
  minPurchaseAmount: string | null;
  active: boolean;
}

export const DISCOUNT_TYPE_OPTIONS: { value: DiscountType; label: string; subtitle: string }[] = [
  {
    value: "BUNDLE_AMOUNT_OFF",
    label: "Course Bundle - Amount Off",
    subtitle: "Pay for a set of courses at once and get amount off",
  },
  {
    value: "BUNDLE_FREE_COURSE",
    label: "Course Bundle - Free Course",
    subtitle: "Pay for a set of courses and get other courses free for a number of months",
  },
  { value: "DISCOUNT_CODE", label: "Discount Code", subtitle: "Enter code to get discount" },
  { value: "ADVANCE_PAYMENT", label: "Advance Payment Discount", subtitle: "Get discount for advance payments" },
];

export function discountTypeLabel(type: DiscountType): string {
  return DISCOUNT_TYPE_OPTIONS.find((option) => option.value === type)?.label ?? type;
}

export const SELECTION_MODE_OPTIONS: { value: SelectionMode; label: string }[] = [
  { value: "ALL", label: "All selected courses required" },
  { value: "ANY", label: "Any selected courses required" },
  { value: "MIN_COUNT", label: "Minimum count required" },
];

export function selectionModeHelper(mode: SelectionMode, minCount: number | null): string {
  if (mode === "ALL") return "Discount applies only if all specified courses are selected";
  if (mode === "ANY") return "Discount applies if at least one course is selected";
  return `Discount applies if minimum ${minCount && minCount > 0 ? minCount : "X"} courses are selected`;
}

export const discountKeys = {
  all: ["admin-discounts"] as const,
  lists: () => [...discountKeys.all, "list"] as const,
  list: (status: DiscountStatusFilter) => [...discountKeys.lists(), status] as const,
  detail: (id: string) => [...discountKeys.all, "detail", id] as const,
  courseOptions: () => [...discountKeys.all, "course-options"] as const,
};

const BASE = "/v1/admin/discounts";

export function useDiscounts(status: DiscountStatusFilter) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: discountKeys.list(status),
    queryFn: () => authorizedFetch<DiscountList>("tenant", `${BASE}?status=${status}`),
  });
}

export function useDiscount(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: discountKeys.detail(id),
    queryFn: () => authorizedFetch<DiscountRule>("tenant", `${BASE}/${encodeURIComponent(id)}`),
    enabled: id.length > 0,
  });
}

export function useDiscountCourseOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: discountKeys.courseOptions(),
    queryFn: () => authorizedFetch<DiscountCourseOption[]>("tenant", `${BASE}/course-options`),
  });
}

function useInvalidateDiscounts() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: discountKeys.all });
}

/** 201; 422 `DISCOUNT_VALIDATION` / `DISCOUNT_COURSE_NOT_FOUND` (field errors); 409 `DISCOUNT_CODE_TAKEN`. */
export function useCreateDiscount() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateDiscounts();
  return useMutation({
    mutationFn: (body: DiscountRequest) =>
      authorizedFetch<DiscountRule>("tenant", BASE, { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => void invalidate(),
  });
}

/** A new version of the rule; earlier orders keep the version they were priced with. */
export function useUpdateDiscount(id: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateDiscounts();
  return useMutation({
    mutationFn: (body: DiscountRequest) =>
      authorizedFetch<DiscountRule>("tenant", `${BASE}/${encodeURIComponent(id)}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: () => void invalidate(),
  });
}

export function useSetDiscountActive() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateDiscounts();
  return useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      authorizedFetch<DiscountRule>("tenant", `${BASE}/${encodeURIComponent(id)}/active`, {
        method: "PATCH",
        body: JSON.stringify({ active }),
      }),
    onSuccess: () => void invalidate(),
  });
}

/**
 * Archive. The contract answers `204 No Content` (no `ApiResponse` envelope),
 * which `apiFetch` reports as an error with status 204 - treated as success
 * here. Past orders and redemptions keep their discount snapshot.
 */
export function useArchiveDiscount() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateDiscounts();
  return useMutation({
    mutationFn: async (id: string) => {
      try {
        await authorizedFetch<null>("tenant", `${BASE}/${encodeURIComponent(id)}`, { method: "DELETE" });
      } catch (error) {
        if (isApiClientError(error) && error.status === 204) return;
        throw error;
      }
    },
    onSuccess: () => void invalidate(),
  });
}
