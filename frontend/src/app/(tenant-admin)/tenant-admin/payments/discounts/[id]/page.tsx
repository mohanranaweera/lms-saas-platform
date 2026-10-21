"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageDiscounts } from "@/lib/auth/permissions";
import { useDiscount, useDiscountCourseOptions, useUpdateDiscount } from "@/lib/api/discounts";
import { discountFormFromRule } from "@/lib/validation/discounts";
import { DiscountForm } from "../discount-form";

const LIST_HREF = "/tenant-admin/payments/discounts";

/**
 * DISC-01 edit / view one discount. Saving appends a new version server-side
 * (`PUT`); orders already placed keep the version they were priced with. A
 * foreign or unknown id is a 404 from the tenant-scoped backend, shown as the
 * shared error state. Without `DISCOUNTS`/CREATE_EDIT the form is read-only.
 */
export default function EditDiscountPage() {
  const params = useParams<{ id: string }>();
  const id = params?.id ?? "";
  const router = useRouter();
  const { session } = useAuth();
  const canManage = canManageDiscounts(session?.role ?? null);
  const ruleQuery = useDiscount(id);
  const courseOptions = useDiscountCourseOptions();
  const update = useUpdateDiscount(id);

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href={LIST_HREF} className="flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          Discounts
        </Link>
        <h1 className="text-xl font-semibold text-foreground">{canManage ? "Edit Discount" : "Discount"}</h1>
      </div>

      <QueryStateBoundary
        query={ruleQuery}
        loadingLabel="Loading discount…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(rule) => (
          <QueryStateBoundary
            query={courseOptions}
            loadingLabel="Loading courses…"
            loginPath="/login"
            permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          >
            {(options) => (
              <div className="flex flex-col gap-4">
                <p className="text-xs text-muted-foreground" data-testid="discount-version">
                  Version {rule.versionNo} of {rule.versionCount} · used {rule.usedCount} time
                  {rule.usedCount === 1 ? "" : "s"}. Saving creates a new version; orders already placed keep the
                  discount they were given.
                </p>
                <DiscountForm
                  key={`${rule.id}-${rule.versionNo}`}
                  initialValues={discountFormFromRule(rule)}
                  existing={rule}
                  courseOptions={options}
                  readOnly={!canManage}
                  submitLabel="Save Changes"
                  onSubmit={async (body) => {
                    await update.mutateAsync(body);
                    router.push(LIST_HREF);
                  }}
                  onCancel={() => router.push(LIST_HREF)}
                />
              </div>
            )}
          </QueryStateBoundary>
        )}
      </QueryStateBoundary>
    </div>
  );
}
