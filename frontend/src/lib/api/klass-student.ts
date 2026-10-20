import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "@/lib/api/error";
import type { CourseResponse, PageResponse } from "@/lib/api/courses";
import type { PaymentSlipResponse } from "@/lib/api/payment-slips";

/**
 * Typed client + React Query hooks for the Klass-parity STUDENT contract
 * (`docs/plans/klass-enrollment-payment-parity.md` §4.2-§4.4, ADR-028):
 * catalog + free enrollment, course content tree / live classes / lesson
 * launch, and the payment composer (checkout courses, offers, quote, order).
 *
 * Nothing here decides access, price or payment. Enrollment is shown only
 * after the server confirms it; lesson access comes from the server's
 * evaluator (`access.status`); every amount shown in the composer comes from
 * the server quote. Payment success never unlocks anything client-side - the
 * content tree is simply re-read after the backend has confirmed a payment or
 * a reviewer has approved a slip.
 */

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

/** Money is a decimal string on the wire (e.g. "4000.00"); tolerate a number. */
export type MoneyValue = string | number;

export interface PriceDisplay {
  amount: MoneyValue;
  currency: string;
  /** "MONTH" for monthly courses, otherwise null. */
  period: string | null;
}

export interface CourseTag {
  id: string;
  name: string;
}

// ---------------------------------------------------------------------------
// §4.2 Catalog + free enrollment
// ---------------------------------------------------------------------------

export type CatalogView = "ENROLLED" | "AVAILABLE";
export type CatalogSort = "DEFAULT" | "TITLE";

export interface CatalogCourse {
  courseId: string;
  title: string;
  teacherName: string | null;
  coverImageUrl: string | null;
  priceDisplay: PriceDisplay | null;
  pricingModel: string;
  tags: CourseTag[];
  enrolled: boolean;
  registrationEnabled: boolean;
}

export interface CatalogParams {
  view: CatalogView;
  search?: string;
  tagId?: string | null;
  sort?: CatalogSort;
  page?: number;
  size?: number;
}

export interface SelfEnrollResponse {
  courseId: string;
  enrolledAt: string;
  created: boolean;
}

// ---------------------------------------------------------------------------
// Course header (existing GET /api/v1/courses/{id} + Klass fields)
// ---------------------------------------------------------------------------

export interface CourseTeacherRef {
  id: string;
  name: string;
  primary: boolean;
}

/** `CourseResponse` widened with the §4.1 fields the student header reads. */
export interface StudentCourseDetail extends Omit<CourseResponse, "pricingModel"> {
  pricingModel: string;
  coverImageUrl: string | null;
  priceDisplay: PriceDisplay | null;
  teachers: CourseTeacherRef[] | null;
  whatsappInviteUrl: string | null;
  telegramInviteUrl: string | null;
  timezone: string | null;
  registrationEnabled?: boolean;
}

// ---------------------------------------------------------------------------
// §4.3 Content tree, live classes, launch
// ---------------------------------------------------------------------------

export type LessonAccessStatus =
  | "ALLOWED"
  | "NOT_ENROLLED"
  | "PAYMENT_REQUIRED"
  | "PAYMENT_PENDING"
  | "NOT_STARTED"
  | "EXPIRED"
  | "UNAVAILABLE"
  | "DEVICE_NOT_AUTHORIZED";

export type PurchaseScope = "COURSE" | "MONTHLY" | "DAILY" | "MODULE" | "LESSON" | "INSTALLMENT";

export interface RequiredPurchase {
  scope: PurchaseScope;
  courseId: string;
  moduleId: string | null;
  lessonId: string | null;
  year: number | null;
  month: number | null;
}

export interface LessonAccess {
  status: LessonAccessStatus;
  availability: "NOT_STARTED" | "OPEN" | "ENDED" | "ALWAYS";
  payment: "FREE" | "COVERED" | "PENDING" | "REQUIRED";
  requiredPurchase: RequiredPurchase | null;
}

export type LessonPaymentType = "FREE" | "COVERED_BY_CLASS_FEES" | "PAID";

export interface LessonNode {
  lessonId: string;
  /** The lesson's own (for linked content: SOURCE) course. */
  courseId: string;
  moduleId: string;
  title: string;
  lessonLabel: string | null;
  resourceType: string | null;
  paymentType: LessonPaymentType;
  price: MoneyValue | null;
  startsAt: string | null;
  endsAt: string | null;
  viewed: boolean;
  access: LessonAccess;
}

export interface ModuleNode {
  moduleId: string;
  title: string;
  sequence: number;
  sourceCourseId: string | null;
  lessons: LessonNode[];
}

export type PeriodPaymentStatus = "PAID" | "PARTIAL" | "PENDING" | "REQUIRED" | "NOT_REQUIRED";

export interface PeriodPurchase {
  scope: "MONTHLY";
  courseId: string;
  year: number;
  month: number;
}

export interface PeriodNode {
  /** "yyyy-mm", or null for lessons with no billing month ("Course content"). */
  key: string | null;
  year: number | null;
  month: number | null;
  label: string;
  payment: { status: PeriodPaymentStatus; purchase: PeriodPurchase | null };
  modules: ModuleNode[];
}

export interface CourseContent {
  courseId: string;
  viewedCount: number;
  totalCount: number;
  timezone: string | null;
  periods: PeriodNode[];
}

export interface LiveClassNode extends LessonNode {
  moduleTitle: string;
}

export interface LaunchResource {
  kind: "MATERIAL" | "CLASS_SESSION";
  id: string;
  title: string;
  /** Material type (PDF, VIDEO, ...) or class-session status (SCHEDULED, LIVE, COMPLETED). */
  type: string;
}

export interface LessonLaunchResponse {
  access: LessonAccess;
  resources: LaunchResource[];
  playerHandoffRequired: boolean;
  playerLinks: { androidUrl: string | null; iosUrl: string | null } | null;
}

// ---------------------------------------------------------------------------
// §4.4 Payment composer
// ---------------------------------------------------------------------------

export interface CheckoutCourseTag {
  id: string;
  name: string;
}

export interface CheckoutCourse {
  courseId: string;
  title: string;
  /** R2-D6 (additive): the course's tags, for the composer's tag filter. Absent on older servers. */
  tags?: CheckoutCourseTag[];
}

export type OfferState = "AVAILABLE" | "PAID" | "PENDING";

export interface CheckoutOfferItem {
  scope: PurchaseScope;
  year: number | null;
  month: number | null;
  moduleId: string | null;
  lessonId: string | null;
  label: string;
  amount: MoneyValue;
  state: OfferState;
}

export interface CheckoutOffers {
  scope: PurchaseScope;
  courseId: string;
  currency: string;
  supported: boolean;
  years: number[];
  items: CheckoutOfferItem[];
}

/**
 * One requested item: identifiers only, never an amount. `courseId` is sent on
 * every item (multi-course checkout): the cart may span several courses.
 */
export interface QuoteItemRequest {
  courseId: string;
  scope: PurchaseScope;
  year?: number;
  month?: number;
  moduleId?: string;
  lessonId?: string;
}

/** `POST /api/v1/checkout/quote` body: the cart items + the courses whose postage the student ticked. */
export interface CheckoutQuoteRequest {
  items: QuoteItemRequest[];
  postageCourseIds: string[];
}

export interface QuoteLine {
  lineNo: number;
  scope: PurchaseScope;
  label: string;
  year: number | null;
  month: number | null;
  moduleId: string | null;
  lessonId: string | null;
  unitAmount: MoneyValue;
  facilityFee: MoneyValue;
  lineTotal: MoneyValue;
  /** The course this line belongs to (an order may span several courses). */
  courseId: string | null;
  courseTitle: string | null;
}

export type FacilityFeeSource = "TENANT_DEFAULT" | "COURSE_OVERRIDE" | "NOT_APPLICABLE";

/** Tenant facility-fee mode (`PAYMENT.facility_fee_mode`). */
export type FacilityFeeBasis = "NONE" | "PER_COURSE" | "PER_LINE" | "PER_ORDER";

/** Per-course charges of a quote / order (server-computed; display only). */
export interface QuoteCourseCharge {
  courseId: string;
  courseTitle: string | null;
  currency: string;
  subtotal: MoneyValue;
  /** Facility fee actually charged to this course. */
  facilityFee: MoneyValue;
  effectiveFacilityFee: MoneyValue;
  facilityFeeSource: FacilityFeeSource;
  /** The course's configured postage (0 = no postage charge). */
  postageFee: MoneyValue;
  postageSelected: boolean;
  postageCharge: MoneyValue;
}

/** Delivery address for postage: the student's on-record billing address (or an order's snapshot of it). */
export interface DeliveryDetails {
  recipientName: string | null;
  addressLine: string | null;
  city: string | null;
  phone: string | null;
}

export interface BankDetails {
  bankName: string | null;
  branch: string | null;
  accountHolder: string | null;
  accountNumber: string | null;
}

export interface CheckoutQuote {
  /** The single course of the cart, `null` when it spans several. */
  courseId: string | null;
  currency: string;
  lines: QuoteLine[];
  courses: QuoteCourseCharge[];
  subtotal: MoneyValue;
  facilityFeeTotal: MoneyValue;
  postageTotal: MoneyValue;
  discountTotal: MoneyValue;
  total: MoneyValue;
  feeBasis: FacilityFeeBasis;
  feeNote: string | null;
  /** Postage is selected for at least one course. */
  deliveryRequired: boolean;
  delivery: DeliveryDetails | null;
  /** Hash of the full breakdown; `POST /orders` must echo the latest one. */
  quoteToken: string;
  methods: { online: boolean; slip: boolean };
  bankDetails: BankDetails | null;
}

/** `POST /api/v1/checkout/orders` = the quote body + the latest `quoteToken` + an idempotency key. */
export interface ComposerOrderRequest extends CheckoutQuoteRequest {
  quoteToken: string;
  idempotencyKey: string;
}

/** Existing `OrderResponse` + composer line / per-course charge / postage / delivery fields. */
export interface ComposerOrderResponse {
  id: string;
  studentId?: string;
  /** `null` for a multi-course order. */
  courseId: string | null;
  amount: MoneyValue;
  currency: string;
  billingPeriodId?: string | null;
  status: string;
  createdAt?: string;
  updatedAt?: string;
  lines?: QuoteLine[];
  courses?: QuoteCourseCharge[];
  subtotal?: MoneyValue;
  facilityFeeTotal?: MoneyValue;
  postageTotal?: MoneyValue;
  discountTotal?: MoneyValue;
  feeBasis?: FacilityFeeBasis;
  delivery?: DeliveryDetails | null;
}

// ---------------------------------------------------------------------------
// Query keys
// ---------------------------------------------------------------------------

export const klassStudentKeys = {
  all: ["klass-student"] as const,
  catalogAll: () => [...klassStudentKeys.all, "catalog"] as const,
  catalog: (params: CatalogParams) => [...klassStudentKeys.catalogAll(), params] as const,
  tags: () => [...klassStudentKeys.all, "tags"] as const,
  course: (courseId: string) => [...klassStudentKeys.all, "course", courseId] as const,
  content: (courseId: string) => [...klassStudentKeys.all, "content", courseId] as const,
  liveClasses: (courseId: string) => [...klassStudentKeys.all, "live-classes", courseId] as const,
  checkoutAll: () => [...klassStudentKeys.all, "checkout"] as const,
  checkoutCourses: (scope: PurchaseScope) => [...klassStudentKeys.checkoutAll(), "courses", scope] as const,
  offers: (courseId: string, scope: PurchaseScope, year: number | null) =>
    [...klassStudentKeys.checkoutAll(), "offers", courseId, scope, year] as const,
  quoteAll: () => [...klassStudentKeys.checkoutAll(), "quote"] as const,
  quote: (body: CheckoutQuoteRequest) => [...klassStudentKeys.quoteAll(), body] as const,
};

/** 4xx responses are answers, not transient failures - don't retry them. */
function retryUnlessClientError(failureCount: number, error: unknown): boolean {
  if (isApiClientError(error) && error.status >= 400 && error.status < 500) return false;
  return failureCount < 2;
}

// ---------------------------------------------------------------------------
// Catalog + enrollment hooks
// ---------------------------------------------------------------------------

function catalogQuery(params: CatalogParams): string {
  const search = new URLSearchParams();
  search.set("view", params.view);
  if (params.search?.trim()) search.set("search", params.search.trim());
  if (params.tagId) search.set("tagId", params.tagId);
  search.set("sort", params.sort ?? "DEFAULT");
  search.set("page", String(params.page ?? 0));
  search.set("size", String(params.size ?? 20));
  return search.toString();
}

/** `GET /api/v1/enrollments/my/catalog` - Student only; server-side search/tag/sort. */
export function useStudentCatalog(params: CatalogParams) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.catalog(params),
    queryFn: () =>
      authorizedFetch<PageResponse<CatalogCourse>>(
        "tenant",
        `/v1/enrollments/my/catalog?${catalogQuery(params)}`
      ),
    placeholderData: keepPreviousData,
    retry: retryUnlessClientError,
  });
}

/** `GET /api/v1/course-tags` - tenant tag chips. */
export function useCourseTags() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.tags(),
    queryFn: () => authorizedFetch<CourseTag[]>("tenant", "/v1/course-tags"),
    retry: retryUnlessClientError,
    staleTime: 5 * 60_000,
  });
}

/**
 * `POST /api/v1/enrollments/my/courses/{courseId}` - free, idempotent
 * enrollment (ADR-028). Never creates an order or payment. 404 when the
 * course isn't visible, 409 `REGISTRATION_CLOSED`. The UI shows "enrolled"
 * only from this response / the refetched catalog, never optimistically.
 */
export function useSelfEnroll() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (courseId: string) =>
      authorizedFetch<SelfEnrollResponse>("tenant", `/v1/enrollments/my/courses/${courseId}`, {
        method: "POST",
      }),
    onSuccess: (data) => {
      queryClient.invalidateQueries({ queryKey: klassStudentKeys.catalogAll() });
      queryClient.invalidateQueries({ queryKey: klassStudentKeys.content(data.courseId) });
      queryClient.invalidateQueries({ queryKey: klassStudentKeys.liveClasses(data.courseId) });
      queryClient.invalidateQueries({ queryKey: klassStudentKeys.checkoutAll() });
      // Legacy "my enrollments"/course-summary reads used elsewhere in the portal.
      queryClient.invalidateQueries({ queryKey: ["enrollments"] });
    },
  });
}

// ---------------------------------------------------------------------------
// Course header / content / live classes / launch hooks
// ---------------------------------------------------------------------------

/** Existing `GET /api/v1/courses/{id}` (PUBLIC courses and participants may read it). */
export function useStudentCourse(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.course(courseId),
    queryFn: () => authorizedFetch<StudentCourseDetail>("tenant", `/v1/courses/${courseId}`),
    enabled: courseId.length > 0,
    retry: retryUnlessClientError,
  });
}

/** `GET /api/v1/courses/{courseId}/content` - participant only (404 otherwise). Safe metadata, never URLs. */
export function useCourseContent(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.content(courseId),
    queryFn: () => authorizedFetch<CourseContent>("tenant", `/v1/courses/${courseId}/content`),
    enabled: courseId.length > 0,
    retry: retryUnlessClientError,
  });
}

/** `GET /api/v1/courses/{courseId}/live-classes` - upcoming live lessons, same evaluator as the tree. */
export function useCourseLiveClasses(courseId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.liveClasses(courseId),
    queryFn: () => authorizedFetch<LiveClassNode[]>("tenant", `/v1/courses/${courseId}/live-classes`),
    enabled: courseId.length > 0,
    retry: retryUnlessClientError,
  });
}

/**
 * `POST /api/v1/courses/{courseId}/lessons/{lessonId}/launch` - a mutation:
 * it records progress (D14) and lists the lesson's resource ids, which are
 * then opened through their own lesson-guarded endpoints. 403 carries the
 * access status as `error.code`; 404 = unavailable.
 */
export function useLaunchLesson() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ courseId, lessonId }: { courseId: string; lessonId: string; pageCourseId: string }) =>
      authorizedFetch<LessonLaunchResponse>("tenant", `/v1/courses/${courseId}/lessons/${lessonId}/launch`, {
        method: "POST",
      }),
    onSettled: (_data, _error, variables) => {
      // A launch may record a first view (viewed check / "N of M viewed"),
      // and a denial means our cached access state was stale - re-read both.
      queryClient.invalidateQueries({ queryKey: klassStudentKeys.content(variables.pageCourseId) });
      queryClient.invalidateQueries({ queryKey: klassStudentKeys.liveClasses(variables.pageCourseId) });
    },
  });
}

/**
 * Existing `POST /api/v1/class-sessions/{id}/join` (now lesson-guarded
 * server-side). Mutation: every call mints a fresh, short-lived join URL that
 * must be opened immediately and never cached. The id is a mutate variable
 * because it comes from a lesson launch.
 */
export function useJoinLessonSession() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (sessionId: string) =>
      authorizedFetch<{ joinUrl: string; expiresAt: string }>("tenant", `/v1/class-sessions/${sessionId}/join`, {
        method: "POST",
      }),
  });
}

/** Existing `GET /api/v1/class-sessions/{id}/recording` - fresh short-lived playback URL per call. */
export function useLessonSessionRecording() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (sessionId: string) =>
      authorizedFetch<{ playbackUrl: string; expiresAt: string }>("tenant", `/v1/class-sessions/${sessionId}/recording`),
  });
}

// ---------------------------------------------------------------------------
// Composer hooks
// ---------------------------------------------------------------------------

/** `GET /api/v1/checkout/courses?scope=` - enrolled courses with at least one offer for the scope. */
export function useCheckoutCourses(scope: PurchaseScope, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.checkoutCourses(scope),
    queryFn: () => authorizedFetch<CheckoutCourse[]>("tenant", `/v1/checkout/courses?scope=${scope}`),
    enabled: options?.enabled ?? true,
    retry: retryUnlessClientError,
  });
}

/** `GET /api/v1/checkout/offers?courseId=&scope=&year=` - server-priced offers with PAID/PENDING state. */
export function useCheckoutOffers(
  courseId: string | null,
  scope: PurchaseScope,
  year: number | null,
  options?: { enabled?: boolean }
) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.offers(courseId ?? "", scope, year),
    queryFn: () => {
      const search = new URLSearchParams({ courseId: courseId ?? "", scope });
      if (year !== null) search.set("year", String(year));
      return authorizedFetch<CheckoutOffers>("tenant", `/v1/checkout/offers?${search.toString()}`);
    },
    enabled: Boolean(courseId) && (options?.enabled ?? true),
    retry: retryUnlessClientError,
  });
}

/**
 * `POST /api/v1/checkout/quote` - a server re-price of the current cart
 * (items across one or more courses + selected postage). Modelled as a query
 * keyed by the cart so every change re-quotes, and stale answers for an old
 * cart are never shown as the current one. 422 codes: ITEM_NOT_ELIGIBLE,
 * ITEM_ALREADY_PAID, ITEM_PENDING, EMPTY_SELECTION, CURRENCY_MISMATCH,
 * POSTAGE_COURSE_NOT_IN_CART, DELIVERY_DETAILS_REQUIRED; 404 unknown course.
 */
export function useCheckoutQuote(body: CheckoutQuoteRequest) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: klassStudentKeys.quote(body),
    queryFn: () =>
      authorizedFetch<CheckoutQuote>("tenant", "/v1/checkout/quote", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    enabled: body.items.length > 0,
    retry: retryUnlessClientError,
    staleTime: 0,
  });
}

/**
 * `POST /api/v1/checkout/orders` - the server re-quotes under its own locks;
 * client totals are never sent. 201 new / 200 idempotent replay. A changed
 * breakdown answers 409 `QUOTE_CHANGED` with the fresh quote in
 * `error.details.quote`; a missing token answers 422 `QUOTE_REQUIRED`.
 */
export function useCreateComposerOrder() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (body: ComposerOrderRequest) =>
      authorizedFetch<ComposerOrderResponse>("tenant", "/v1/checkout/orders", {
        method: "POST",
        body: JSON.stringify(body),
      }),
  });
}

/**
 * Existing `POST /api/v1/orders/{orderId}/slips` (multipart, unchanged
 * contract - see `lib/api/payment-slips.ts#useUploadSlip`). Here the order id
 * is a mutate variable because the composer creates the order first. A slip
 * upload is not proof of payment: nothing unlocks until a reviewer approves.
 */
export function useUploadComposerSlip() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ orderId, referenceNumber, file }: { orderId: string; referenceNumber: string; file: File }) => {
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

/** Re-reads everything a confirmed/submitted payment can change (state only; never unlocks locally). */
export function useInvalidateAfterPayment() {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: klassStudentKeys.all });
  };
}

// ---------------------------------------------------------------------------
// Display helpers (formatting only)
// ---------------------------------------------------------------------------

export function toAmount(value: MoneyValue | null | undefined): number {
  if (value === null || value === undefined) return 0;
  const parsed = typeof value === "number" ? value : Number.parseFloat(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

/** "LKR 4,000.00" */
export function formatCurrency(value: MoneyValue | null | undefined, currency: string): string {
  const amount = new Intl.NumberFormat("en-US", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(toAmount(value));
  return `${currency} ${amount}`;
}

/** Badge text: "LKR 4000 / month", "LKR 2000", "Free", "Charged per module", or null. */
export function priceBadgeLabel(priceDisplay: PriceDisplay | null, pricingModel: string): string | null {
  if (pricingModel === "FREE") return "Free";
  if (pricingModel === "MODULE") return "Charged per module";
  if (!priceDisplay) return null;
  const amount = toAmount(priceDisplay.amount);
  const text = Number.isInteger(amount) ? String(amount) : amount.toFixed(2);
  const period = priceDisplay.period === "MONTH" ? " / month" : "";
  return `${priceDisplay.currency} ${text}${period}`;
}

const MONTH_SHORT = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];
const MONTH_LONG = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

export function monthShort(month: number): string {
  return MONTH_SHORT[month - 1] ?? String(month);
}

export function monthLong(month: number): string {
  return MONTH_LONG[month - 1] ?? String(month);
}
