import type { Page } from "@playwright/test";
import { apiSuccess, mockJson } from "./auth-mocks";

/**
 * MEDIA-INT-01 mock builders for `docs/api/integrations.md`. All values are
 * synthetic; no response here ever contains a token or API key, matching
 * the real backend.
 */

export type Provider = "ZOOM" | "YOUTUBE" | "VIMEO" | "DYNTUBE" | "BUNNY";

export interface ConnectionMock {
  id: string;
  provider: Provider;
  scope: "TEACHER" | "TENANT";
  status: "ACTIVE" | "NEEDS_RECONNECT" | "DISCONNECTED";
  accountLabel: string | null;
  externalAccountIdMasked: string | null;
  accountType: string | null;
  grantedScopes: string[];
  settings: Record<string, unknown> | null;
  secretConfigured: boolean;
  connectedAt: string;
  lastVerifiedAt: string | null;
  lastError: string | null;
  disconnectedBy: string | null;
  disconnectedAt: string | null;
  disconnectedReason: string | null;
}

export function connection(overrides: Partial<ConnectionMock> & { id: string; provider: Provider }): ConnectionMock {
  const teacherScoped = ["ZOOM", "YOUTUBE", "VIMEO"].includes(overrides.provider);
  return {
    scope: teacherScoped ? "TEACHER" : "TENANT",
    status: "ACTIVE",
    accountLabel: teacherScoped ? "teacher@example.test" : null,
    externalAccountIdMasked: "****1234",
    accountType: overrides.provider === "ZOOM" ? "LICENSED" : null,
    grantedScopes: [],
    settings: null,
    secretConfigured: true,
    connectedAt: "2026-09-20T08:00:00Z",
    lastVerifiedAt: "2026-09-27T08:00:00Z",
    lastError: null,
    disconnectedBy: null,
    disconnectedAt: null,
    disconnectedReason: null,
    ...overrides,
  };
}

export interface TeacherProviderMock {
  provider: "ZOOM" | "YOUTUBE" | "VIMEO";
  enabledByTenant: boolean;
  available: boolean;
  connection: ConnectionMock | null;
}

export type BunnyLibraryStatusMock = "PROVISIONING" | "ACTIVE" | "FAILED" | "UNAVAILABLE";

export function teacherIntegrations(
  overrides: Partial<Record<"ZOOM" | "YOUTUBE" | "VIMEO", Partial<TeacherProviderMock>>> = {},
  bunny: {
    bunnyConnected?: boolean;
    bunnyLibrary?: { status: BunnyLibraryStatusMock; libraryName: string } | null;
  } = {}
): {
  providers: TeacherProviderMock[];
  bunnyConnected: boolean;
  bunnyLibrary: { status: BunnyLibraryStatusMock; libraryName: string } | null;
} {
  return {
    bunnyConnected: bunny.bunnyConnected ?? false,
    bunnyLibrary: bunny.bunnyLibrary ?? null,
    providers: (["ZOOM", "YOUTUBE", "VIMEO"] as const).map((provider) => ({
      provider,
      enabledByTenant: true,
      available: true,
      connection: null,
      ...overrides[provider],
    })),
  };
}

export async function mockTeacherIntegrations(
  page: Page,
  view: ReturnType<typeof teacherIntegrations>
): Promise<void> {
  await mockJson(page, "**/v1/teacher/integrations", 200, apiSuccess(view));
}

export function tenantIntegrations(overrides: Record<string, unknown> = {}) {
  return {
    teacherProviders: {
      zoom: { enabled: true, available: true },
      youtube: { enabled: true, available: true },
      vimeo: { enabled: true, available: true },
    },
    videoHost: "NONE",
    dyntube: null,
    bunny: null,
    teacherConnections: [],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// MEDIA-INT-01A — teacher Bunny libraries
// ---------------------------------------------------------------------------

export interface BunnyLibraryMock {
  id: string;
  status: BunnyLibraryStatusMock;
  libraryName: string;
  createdAt: string;
  lastError: string | null;
}

export function bunnyLibrary(overrides: Partial<BunnyLibraryMock> = {}): BunnyLibraryMock {
  return {
    id: "library-1",
    status: "ACTIVE",
    libraryName: "Demo Institute - Jane Perera [a1b2c3d4e5f6]",
    createdAt: "2026-09-29T08:00:00Z",
    lastError: null,
    ...overrides,
  };
}

/** `canCreateLibrary` defaults to what the backend returns for a connected account and an approved teacher. */
export function teacherLibraryRow(
  teacherUserId: string,
  teacherName: string,
  library: BunnyLibraryMock | null = null,
  canCreateLibrary: boolean = library === null || library.status === "FAILED"
) {
  return { teacherUserId, teacherName, library, canCreateLibrary };
}

export async function mockBunnyLibraries(
  page: Page,
  rows: ReturnType<typeof teacherLibraryRow>[]
): Promise<void> {
  await mockJson(page, "**/v1/tenant-admin/integrations/bunny/libraries", 200, apiSuccess(rows));
}
