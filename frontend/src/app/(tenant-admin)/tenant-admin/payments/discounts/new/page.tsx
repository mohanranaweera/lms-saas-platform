"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ArrowLeft } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageDiscounts } from "@/lib/auth/permissions";
import { useCreateDiscount, useDiscountCourseOptions } from "@/lib/api/discounts";
import { emptyDiscountForm } from "@/lib/validation/discounts";
import { DiscountForm } from "../discount-form";

const LIST_HREF = "/tenant-admin/payments/discounts";

/** DISC-01 "Create New Discount". `POST /api/v1/admin/discounts` enforces `DISCOUNTS`/CREATE_EDIT. */
export default function NewDiscountPage() {
  const router = useRouter();
  const { session } = useAuth();
  const canManage = canManageDiscounts(session?.role ?? null);
  const courseOptions = useDiscountCourseOptions();
  const create = useCreateDiscount();

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href={LIST_HREF} className="flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          Discounts
        </Link>
        <h1 className="text-xl font-semibold text-foreground">Create New Discount</h1>
      </div>

      <QueryStateBoundary
        query={courseOptions}
        loadingLabel="Loading courses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(options) => (
          <DiscountForm
            initialValues={emptyDiscountForm()}
            courseOptions={options}
            readOnly={!canManage}
            submitLabel="Create Discount"
            onSubmit={async (body) => {
              await create.mutateAsync(body);
              router.push(LIST_HREF);
            }}
            onCancel={() => router.push(LIST_HREF)}
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}
