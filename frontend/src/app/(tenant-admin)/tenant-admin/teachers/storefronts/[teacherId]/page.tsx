"use client";

import { useMemo } from "react";
import { useParams, useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Breadcrumbs } from "@/components/ui/breadcrumbs";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StorefrontStatusBadge, storefrontStatus } from "@/components/storefront/storefront-status-badge";
import { isApiClientError } from "@/lib/api/error";
import type { PageResponse } from "@/lib/api/courses";
import {
  teacherStorefrontKeys,
  useTeacherStorefront,
  type StorefrontAdmin,
  type TeacherStorefrontRow,
} from "@/lib/api/teacher-storefronts";
import { StorefrontSettingsForm } from "./storefront-settings-form";
import { StorefrontAddresses } from "./storefront-addresses";
import { StorefrontProductsForm } from "./storefront-products-form";

function emptyStorefront(teacherId: string): StorefrontAdmin {
  return {
    id: null,
    teacherId,
    slug: null,
    enabled: false,
    previewUrl: null,
    publishedUrl: null,
    previousSlugs: [],
    products: [],
  };
}

/** Teacher name from an already-loaded list page (the detail payload has none). */
function useCachedTeacherName(teacherId: string): string | null {
  const queryClient = useQueryClient();
  const pages = queryClient.getQueriesData<PageResponse<TeacherStorefrontRow>>({
    queryKey: [...teacherStorefrontKeys.all, "list"],
  });
  for (const [, page] of pages) {
    const row = page?.content.find((candidate) => candidate.teacherId === teacherId);
    if (row) return row.teacherName;
  }
  return null;
}

/**
 * STORE-01 / ADR-037: one teacher's storefront - on/off switch (off by
 * default), address (slug), previous addresses, and the courses it sells.
 * Every rule (capability, tenant, slug, course assignment) is enforced by the
 * backend; this page shows its answers.
 */
export default function TeacherStorefrontEditPage() {
  const params = useParams<{ teacherId: string }>();
  const teacherId = params.teacherId ?? "";
  const router = useRouter();
  const query = useTeacherStorefront(teacherId);
  const teacherName = useCachedTeacherName(teacherId);
  const notFound = query.status === "error" && isApiClientError(query.error) && query.error.status === 404;
  // Stable placeholder (the forms reset when their `storefront` prop changes).
  const placeholder = useMemo(() => emptyStorefront(teacherId), [teacherId]);
  // 404 = no storefront yet (or not this institute's teacher - the first save
  // then answers 404 too): start from an empty, switched-off storefront.
  const storefront = query.data ?? (notFound ? placeholder : null);
  const title = teacherName ? `${teacherName}'s storefront` : "Teacher storefront";

  return (
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-6">
      <Breadcrumbs
        items={[
          { label: "Teachers", href: "/tenant-admin/teachers" },
          { label: "Storefronts", href: "/tenant-admin/teachers/storefronts" },
          { label: teacherName ?? "Storefront" },
        ]}
      />
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-xl font-semibold break-words text-foreground">{title}</h1>
        {query.data ? <StorefrontStatusBadge status={storefrontStatus(query.data)} /> : null}
      </div>

      {storefront ? (
        // One tree for "not set up" and "exists", so the first save keeps the
        // forms (and their saved/error messages) mounted.
        <div className="flex flex-col gap-6" data-testid={storefront.id ? "storefront-editor" : "storefront-not-set-up"}>
          <StorefrontSettingsForm teacherId={teacherId} storefront={storefront} />
          <StorefrontAddresses storefront={storefront} />
          <StorefrontProductsForm teacherId={teacherId} storefront={storefront} />
          {storefront.id ? null : (
            <div>
              <Button type="button" variant="outline" onClick={() => router.push("/tenant-admin/teachers/storefronts")}>
                Back to storefronts
              </Button>
            </div>
          )}
        </div>
      ) : (
        <QueryStateBoundary
          query={query}
          loadingLabel="Loading storefront…"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          loginPath="/login"
          genericErrorMessage="We couldn't load this storefront."
        >
          {() => null}
        </QueryStateBoundary>
      )}
    </div>
  );
}
