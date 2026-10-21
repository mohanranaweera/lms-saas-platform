import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

/**
 * Real-backend helpers for the KLASS live E2E specs (`e2e/klass-*.spec.ts`).
 *
 * Runs against the developer's LOCAL stack only: Next dev server on the demo
 * tenant host (API calls go through its same-origin `/api` rewrite, exactly
 * like the app) and the native backend on the local `lms_dev` database. Never
 * point this at a shared or production environment.
 *
 * Test data: every spec creates its own uniquely named courses through the
 * real admin API, so reruns never collide. The only tenant-level settings a
 * spec changes are PAYMENT facility-fee / test-only bank fields, which it
 * restores in `afterAll` to the recorded defaults (see `PAYMENT_DEFAULTS`).
 */

export const TENANT_BASE = process.env.KLASS_E2E_BASE_URL ?? "http://demo.lms.test:3000";
export const ADMIN_EMAIL = process.env.KLASS_E2E_ADMIN_EMAIL ?? "tenantadmin@demo.test";
export const STUDENT_EMAIL = process.env.KLASS_E2E_STUDENT_EMAIL ?? "student@demo.test";
const PASSWORD = process.env.KLASS_E2E_PASSWORD ?? "Passw0rd!123";

/** Demo-tenant PAYMENT config as recorded before any KLASS spec ran (all defaults). */
export const PAYMENT_DEFAULTS = {
  facility_fee_mode: "NONE",
  facility_fee_amount: 0,
  bank_name: null,
  bank_branch: null,
  bank_account_holder: null,
  bank_account_number: null,
};

/** Obviously fake, test-only values (D16: never a real account). */
export const PAYMENT_FIXTURE = {
  facility_fee_mode: "PER_LINE",
  facility_fee_amount: 100,
  bank_name: "Test Bank",
  bank_branch: "Test Branch",
  bank_account_holder: "Test Institute",
  bank_account_number: "000-TEST",
};

export function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4).toString(36)}`;
}

export interface ApiResult<T = unknown> {
  status: number;
  body: { success?: boolean; data?: T; error?: { code?: string; message?: string } | null; code?: string };
}

/*
 * Device slots (ADR-022): every login without the backend-issued
 * `lms_device_id` cookie registers a NEW device, and the demo student has a
 * small device limit. All KLASS specs therefore reuse one cached device cookie
 * per account (kept in the OS temp dir, never in the repo), for both API and
 * browser logins, so a run registers at most one device per account.
 */
const DEVICE_COOKIE = "lms_device_id";
const DEVICE_COOKIE_PATH = "/api/v1/auth";
const deviceCacheFile = join(tmpdir(), "klass-e2e-device-cookies.json");

function readDeviceCache(): Record<string, string> {
  if (!existsSync(deviceCacheFile)) return {};
  try {
    return JSON.parse(readFileSync(deviceCacheFile, "utf-8")) as Record<string, string>;
  } catch {
    return {};
  }
}

function cachedDevice(email: string): string | undefined {
  return readDeviceCache()[email];
}

function cacheDevice(email: string, value: string | undefined): void {
  if (!value) return;
  const cache = readDeviceCache();
  cache[email] = value;
  writeFileSync(deviceCacheFile, JSON.stringify(cache));
}

function deviceFromSetCookie(headers: { name: string; value: string }[]): string | undefined {
  for (const header of headers) {
    if (header.name.toLowerCase() !== "set-cookie") continue;
    const match = header.value.match(new RegExp(`(?:^|\n|, )${DEVICE_COOKIE}=([^;]+)`));
    if (match) return match[1];
  }
  return undefined;
}

export async function apiLogin(request: APIRequestContext, email: string): Promise<string> {
  const device = cachedDevice(email);
  const response = await request.post(`${TENANT_BASE}/api/v1/auth/login`, {
    data: { email, password: PASSWORD },
    headers: device ? { Cookie: `${DEVICE_COOKIE}=${device}` } : undefined,
  });
  expect(response.status(), `login ${email}: ${await response.text()}`).toBe(200);
  cacheDevice(email, deviceFromSetCookie(response.headersArray()) ?? device);
  const body = await response.json();
  return body.data.accessToken as string;
}

export async function api<T = unknown>(
  request: APIRequestContext,
  token: string,
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE",
  path: string,
  data?: unknown
): Promise<ApiResult<T>> {
  const response = await request.fetch(`${TENANT_BASE}/api/v1${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}` },
    data,
  });
  const text = await response.text();
  return { status: response.status(), body: text ? JSON.parse(text) : {} };
}

export async function firstTeacherUserId(request: APIRequestContext, adminToken: string): Promise<string> {
  const result = await api<{ userId: string }[]>(request, adminToken, "GET", "/teachers");
  expect(result.status).toBe(200);
  const teacher = (result.body.data ?? []).find((t) => t.userId);
  expect(teacher, "the demo tenant needs at least one teacher").toBeTruthy();
  return teacher!.userId;
}

export async function createCourse(
  request: APIRequestContext,
  adminToken: string,
  fields: Record<string, unknown>
): Promise<{ id: string; name: string }> {
  const result = await api<{ id: string; name: string }>(request, adminToken, "POST", "/courses", {
    status: "PUBLIC",
    ...fields,
  });
  expect(result.status, JSON.stringify(result.body)).toBe(201);
  return result.body.data!;
}

export async function createModule(
  request: APIRequestContext,
  adminToken: string,
  courseId: string,
  title: string
): Promise<string> {
  const result = await api<{ id: string }>(request, adminToken, "POST", `/courses/${courseId}/modules`, { title });
  expect(result.status, JSON.stringify(result.body)).toBe(201);
  return result.body.data!.id;
}

export async function createLesson(
  request: APIRequestContext,
  adminToken: string,
  courseId: string,
  moduleId: string,
  fields: Record<string, unknown>
): Promise<string> {
  const result = await api<{ id: string }>(
    request,
    adminToken,
    "POST",
    `/courses/${courseId}/modules/${moduleId}/lessons`,
    { paymentType: "COVERED_BY_CLASS_FEES", resourceType: "DOCUMENT", ...fields }
  );
  expect(result.status, JSON.stringify(result.body)).toBe(201);
  return result.body.data!.id;
}

export async function setPaymentConfig(
  request: APIRequestContext,
  adminToken: string,
  values: Record<string, unknown>
): Promise<void> {
  const result = await api(request, adminToken, "PUT", "/tenant-config/PAYMENT", values);
  expect(result.status, JSON.stringify(result.body)).toBe(200);
}

export async function catalogIds(
  request: APIRequestContext,
  token: string,
  view: "ENROLLED" | "AVAILABLE",
  search: string
): Promise<string[]> {
  const result = await api<{ content: { courseId: string }[] }>(
    request,
    token,
    "GET",
    `/enrollments/my/catalog?view=${view}&search=${encodeURIComponent(search)}&size=50`
  );
  expect(result.status).toBe(200);
  return (result.body.data?.content ?? []).map((c) => c.courseId);
}

/** Logs in through the real tenant login page (refresh cookie set by the backend), reusing the cached device. */
export async function loginUi(page: Page, email: string): Promise<void> {
  const device = cachedDevice(email);
  if (device) {
    await page.context().addCookies([
      { name: DEVICE_COOKIE, value: device, domain: new URL(TENANT_BASE).hostname, path: DEVICE_COOKIE_PATH },
    ]);
  }
  await page.goto(`${TENANT_BASE}/login`);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/(tenant-admin|student)\/dashboard/, { timeout: 30_000 });
  const issued = (await page.context().cookies(TENANT_BASE + DEVICE_COOKIE_PATH)).find((c) => c.name === DEVICE_COOKIE);
  cacheDevice(email, issued?.value ?? device);
}

/** A small, valid, run-unique PDF so slip duplicate-hash checks never collide across runs. */
export function uniquePdf(seed: string): Buffer {
  return Buffer.from(`%PDF-1.4\n%KLASS-T6 E2E test slip ${seed}\n%%EOF`, "ascii");
}

export const EVIDENCE_DIR = "../docs/qa/klass-evidence";
