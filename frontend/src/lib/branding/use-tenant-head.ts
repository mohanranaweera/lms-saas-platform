"use client";

import { useEffect } from "react";
import { usePathname } from "next/navigation";

/** Tenant favicon via a managed <link rel="icon"> appended last in <head> (so it wins). */
export function useTenantFavicon(faviconUrl: string | null | undefined) {
  useEffect(() => {
    if (!faviconUrl) return;
    const link = document.createElement("link");
    link.rel = "icon";
    link.href = faviconUrl;
    link.setAttribute("data-tenant-favicon", "");
    document.head.appendChild(link);
    return () => link.remove();
  }, [faviconUrl]);
}

/** Sets the tab title from tenant branding; re-applied on every route change. */
export function useTenantDocumentTitle(title: (pathname: string) => string | null) {
  const pathname = usePathname() ?? "";
  const value = title(pathname);
  useEffect(() => {
    if (value) document.title = value;
  }, [value, pathname]);
}
