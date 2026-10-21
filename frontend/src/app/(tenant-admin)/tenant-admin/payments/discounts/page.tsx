"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AlertCircle, CalendarRange, Percent, Plus } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
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
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageDiscounts } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import {
  discountTypeLabel,
  useArchiveDiscount,
  useDiscounts,
  useSetDiscountActive,
  type DiscountRule,
  type DiscountRuleStatus,
  type DiscountStatusFilter,
} from "@/lib/api/discounts";

const TABS: { value: DiscountStatusFilter; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "ACTIVE", label: "Active" },
  { value: "INACTIVE", label: "Inactive" },
];

const STATUS_BADGE: Record<DiscountRuleStatus, { label: string; variant: "default" | "secondary" | "warning" | "outline" }> = {
  ACTIVE: { label: "Active", variant: "default" },
  INACTIVE: { label: "Inactive", variant: "secondary" },
  SCHEDULED: { label: "Scheduled", variant: "outline" },
  EXPIRED: { label: "Expired", variant: "warning" },
};

/**
 * Tenant Admin Discounts (DISC-01, Klass "Discounts" screen): All / Active /
 * Inactive tabs with server counts, one card per rule with its status, type,
 * value, validity window, usage, an active toggle, Edit and Delete (archive).
 *
 * `canManageDiscounts` only decides which controls render; every endpoint
 * independently enforces `DISCOUNTS` VIEW / CREATE_EDIT and a 403 renders the
 * shared permission-denied state (list) or an inline alert (actions).
 */
export default function TenantAdminDiscountsPage() {
  const router = useRouter();
  const { session } = useAuth();
  const canManage = canManageDiscounts(session?.role ?? null);
  const [tab, setTab] = useState<DiscountStatusFilter>("ALL");
  const [actionError, setActionError] = useState<string | null>(null);
  const [announcement, setAnnouncement] = useState("");
  const query = useDiscounts(tab);
  const counts = query.data?.counts;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Discounts</h1>
          <p className="text-sm text-muted-foreground">
            Course bundle, free course, discount code and advance payment discounts for your institute. Students see the
            discount at checkout; the server decides whether it applies.
          </p>
        </div>
        {canManage ? (
          <Link href="/tenant-admin/payments/discounts/new" className={buttonVariants()} data-testid="discount-create">
            <Plus aria-hidden="true" />
            Create Discount
          </Link>
        ) : null}
      </div>

      <LiveRegion message={announcement} />
      {actionError ? (
        <Alert variant="destructive" data-testid="discount-action-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{actionError}</AlertDescription>
        </Alert>
      ) : null}

      <div role="tablist" aria-label="Discount status" className="flex flex-nowrap gap-1 overflow-x-auto border-b border-border">
        {TABS.map((item) => {
          const selected = item.value === tab;
          const count =
            item.value === "ALL" ? counts?.all : item.value === "ACTIVE" ? counts?.active : counts?.inactive;
          return (
            <button
              key={item.value}
              type="button"
              role="tab"
              id={`discount-tab-${item.value}`}
              aria-selected={selected}
              aria-controls="discount-tabpanel"
              onClick={() => setTab(item.value)}
              data-testid={`discount-tab-${item.value}`}
              className={`shrink-0 border-b-2 px-3 py-2 text-sm font-medium outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50 ${
                selected
                  ? "border-blue-600 text-blue-700 dark:text-blue-300"
                  : "border-transparent text-muted-foreground hover:text-foreground"
              }`}
            >
              {item.label}
              {count !== undefined ? ` (${count})` : ""}
            </button>
          );
        })}
      </div>

      <div role="tabpanel" id="discount-tabpanel" aria-labelledby={`discount-tab-${tab}`}>
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading discounts…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.items.length === 0}
          emptyState={
            tab === "ALL"
              ? {
                  title: "No discounts yet",
                  description: canManage
                    ? "Create a discount to reward course bundles, advance payments or a promo code."
                    : "Your institute hasn't created any discounts yet.",
                  action: canManage
                    ? { label: "Create Discount", onClick: () => router.push("/tenant-admin/payments/discounts/new") }
                    : undefined,
                }
              : {
                  title: tab === "ACTIVE" ? "No active discounts" : "No inactive discounts",
                  description:
                    tab === "ACTIVE"
                      ? "No discount is switched on right now. Switch one on from the All tab."
                      : "Every discount is switched on.",
                  action: { label: "Show all discounts", onClick: () => setTab("ALL") },
                }
          }
        >
          {(data) => (
            <ul className="grid grid-cols-1 gap-4 lg:grid-cols-2" aria-label="Discounts" data-testid="discount-list">
              {data.items.map((rule) => (
                <DiscountCard
                  key={rule.id}
                  rule={rule}
                  canManage={canManage}
                  onError={setActionError}
                  onAnnounce={(message) => {
                    setActionError(null);
                    setAnnouncement(message);
                  }}
                />
              ))}
            </ul>
          )}
        </QueryStateBoundary>
      </div>
    </div>
  );
}

function actionErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    if (error.status === 403) return "You don't have permission to change discounts.";
    if (error.status === 404) return "This discount no longer exists. Refresh the list.";
    return error.message || "Something went wrong. Please try again.";
  }
  return "Something went wrong. Please try again.";
}

function DiscountCard({
  rule,
  canManage,
  onError,
  onAnnounce,
}: {
  rule: DiscountRule;
  canManage: boolean;
  onError: (message: string) => void;
  onAnnounce: (message: string) => void;
}) {
  const setActive = useSetDiscountActive();
  const archive = useArchiveDiscount();
  const [confirmOpen, setConfirmOpen] = useState(false);
  const badge = STATUS_BADGE[rule.status] ?? STATUS_BADGE.INACTIVE;
  const toggleId = `discount-active-${rule.id}`;

  const toggle = async (active: boolean) => {
    try {
      await setActive.mutateAsync({ id: rule.id, active });
      onAnnounce(`${rule.name} switched ${active ? "on" : "off"}.`);
    } catch (error) {
      onError(actionErrorMessage(error));
    }
  };

  const confirmArchive = async () => {
    try {
      await archive.mutateAsync(rule.id);
      setConfirmOpen(false);
      onAnnounce(`${rule.name} deleted.`);
    } catch (error) {
      setConfirmOpen(false);
      onError(actionErrorMessage(error));
    }
  };

  return (
    <li
      className="flex flex-col gap-3 rounded-lg border border-border bg-card p-4"
      data-testid="discount-card"
      data-discount-id={rule.id}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-base font-semibold text-foreground" data-testid="discount-card-name">
            {rule.name}
          </h2>
          <p className="text-sm text-muted-foreground">{discountTypeLabel(rule.discountType)}</p>
        </div>
        <Badge variant={badge.variant} data-testid="discount-card-status">
          {badge.label}
        </Badge>
      </div>

      <dl className="grid grid-cols-1 gap-2 text-sm sm:grid-cols-2">
        <div className="flex items-center gap-2">
          <Percent className="size-4 text-muted-foreground" aria-hidden="true" />
          <dt className="sr-only">Value</dt>
          <dd className="font-medium text-foreground" data-testid="discount-card-value">
            {rule.valueLabel}
          </dd>
        </div>
        <div className="flex items-center gap-2">
          <CalendarRange className="size-4 text-muted-foreground" aria-hidden="true" />
          <dt className="sr-only">Valid</dt>
          <dd className="text-foreground">
            {rule.validFrom} – {rule.validTo}
          </dd>
        </div>
        {rule.code ? (
          <div className="flex items-center gap-2">
            <dt className="text-muted-foreground">Code</dt>
            <dd className="font-mono text-foreground" data-testid="discount-card-code">
              {rule.code}
            </dd>
          </div>
        ) : null}
        <div className="flex items-center gap-2">
          <dt className="text-muted-foreground">Used</dt>
          <dd className="text-foreground">
            {rule.usedCount}
            {rule.maxUses !== null ? ` / ${rule.maxUses}` : ""}
            {rule.reservedCount > 0 ? ` (${rule.reservedCount} pending)` : ""}
          </dd>
        </div>
        <div className="flex items-start gap-2 sm:col-span-2">
          <dt className="shrink-0 text-muted-foreground">Courses</dt>
          <dd className="min-w-0 text-foreground">
            {rule.courses.map((course) => course.name).join(", ") || "—"}
            {rule.rewardCourses.length > 0 ? (
              <span className="block text-muted-foreground">
                Free: {rule.rewardCourses.map((course) => course.name).join(", ")}
              </span>
            ) : null}
          </dd>
        </div>
      </dl>

      <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border pt-3">
        {canManage ? (
          <label htmlFor={toggleId} className="flex items-center gap-2 text-sm text-foreground">
            <input
              id={toggleId}
              type="checkbox"
              role="switch"
              className="size-4 accent-blue-600"
              checked={rule.active}
              disabled={setActive.isPending}
              onChange={(event) => void toggle(event.target.checked)}
              data-testid="discount-card-toggle"
            />
            Active<span className="sr-only"> ({rule.name})</span>
          </label>
        ) : (
          <span className="text-sm text-muted-foreground">{rule.active ? "Switched on" : "Switched off"}</span>
        )}
        <div className="flex gap-2">
          <Link
            href={`/tenant-admin/payments/discounts/${rule.id}`}
            className={buttonVariants({ variant: "outline", size: "sm" })}
            aria-label={`${canManage ? "Edit" : "View"} ${rule.name}`}
            data-testid="discount-card-edit"
          >
            {canManage ? "Edit" : "View"}
          </Link>
          {canManage ? (
            <AlertDialog open={confirmOpen} onOpenChange={setConfirmOpen}>
              <AlertDialogTrigger
                render={
                  <Button
                    type="button"
                    variant="destructive"
                    size="sm"
                    aria-label={`Delete ${rule.name}`}
                    data-testid="discount-card-delete"
                  />
                }
              >
                Delete
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Delete {rule.name}?</AlertDialogTitle>
                  <AlertDialogDescription>
                    Students can no longer get this discount. Orders that already used it keep their discount and
                    history.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogClose render={<Button type="button" variant="outline" />}>Cancel</AlertDialogClose>
                  <Button
                    type="button"
                    variant="destructive"
                    onClick={() => void confirmArchive()}
                    disabled={archive.isPending}
                    aria-busy={archive.isPending}
                    data-testid="discount-delete-confirm"
                  >
                    {archive.isPending ? "Deleting…" : "Delete"}
                  </Button>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          ) : null}
        </div>
      </div>
    </li>
  );
}
