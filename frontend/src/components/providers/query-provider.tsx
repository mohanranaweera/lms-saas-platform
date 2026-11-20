"use client";

import { useState, type ReactNode } from "react";
import { MutationCache, QueryCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { isApiClientError } from "@/lib/api/error";

/** Same key as `lib/api/my-permissions.ts#myPermissionsKey` (kept literal to avoid a client-module import cycle). */
const MY_PERMISSIONS_KEY = ["me", "permissions"] as const;

function isForbidden(error: unknown): boolean {
  return isApiClientError(error) && error.status === 403;
}

/**
 * Wraps the app in a TanStack Query client.
 *
 * The client is created via `useState` (not module scope) so each request/session
 * gets its own instance under SSR — a module-level client would leak cached data
 * across requests/users on the server.
 *
 * STAFF-MGMT-02: any 403 (query or mutation) invalidates the caller's cached
 * effective permissions (`GET /v1/me/permissions`), so UI gates catch up with a
 * grant an administrator just revoked. UX only - the 403 itself already
 * enforced the decision server-side.
 */
export function QueryProvider({ children }: { children: ReactNode }) {
  const [queryClient] = useState(() => {
    const invalidateMyPermissions = () => {
      void client.invalidateQueries({ queryKey: MY_PERMISSIONS_KEY });
    };
    const client: QueryClient = new QueryClient({
      queryCache: new QueryCache({
        onError: (error, query) => {
          if (!isForbidden(error)) return;
          const key = query.queryKey;
          if (key[0] === MY_PERMISSIONS_KEY[0] && key[1] === MY_PERMISSIONS_KEY[1]) return;
          invalidateMyPermissions();
        },
      }),
      mutationCache: new MutationCache({
        onError: (error) => {
          if (isForbidden(error)) invalidateMyPermissions();
        },
      }),
      defaultOptions: {
        queries: {
          staleTime: 30_000,
          retry: 1,
        },
      },
    });
    return client;
  });

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
