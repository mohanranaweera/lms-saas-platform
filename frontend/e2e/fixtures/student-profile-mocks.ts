import type { Page } from "@playwright/test";
import { apiError, apiPageSuccess, apiSuccess } from "./auth-mocks";

/**
 * STU-MGMT-02: the Tenant Admin student page is now the full profile, which
 * loads `GET /v1/students/{id}/detail` (plus remarks, QR and photo). Specs
 * written for the legacy detail page (`GET /v1/students/{id}`) call this so
 * the new endpoints are answered from the same legacy fixture. `current` is
 * read on every request, so a spec can flip e.g. `status` mid-test.
 */
export interface LegacyStudentFixture {
  id: string;
  name: string;
  email: string;
  status: string;
}

export function legacyToDetail(student: LegacyStudentFixture) {
  const [firstName, ...rest] = student.name.split(" ");
  return {
    id: student.id,
    indexNumber: "0001",
    email: student.email,
    firstName,
    lastName: rest.join(" ") || null,
    fullName: student.name,
    whatsappNumber: null,
    dateOfBirth: null,
    mobile: null,
    addressLine: null,
    addressLine2: null,
    city: null,
    postalCode: null,
    guardianName: null,
    guardianPhone: null,
    guardianNic: null,
    fixedPhone: null,
    school: null,
    alYear: null,
    stream: null,
    status: student.status,
    pending: false,
    registeredAt: null,
    lastLoginAt: null,
    photoUrl: null,
    enrolledCourseIds: [],
  };
}

export async function mockStudentProfile(
  page: Page,
  current: LegacyStudentFixture | (() => LegacyStudentFixture)
): Promise<void> {
  const read = typeof current === "function" ? current : () => current;
  const id = read().id;
  await page.route(`**/v1/students/${id}/detail`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiSuccess(legacyToDetail(read()))) })
  );
  await page.route(`**/v1/students/${id}/remarks*`, (route) =>
    route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(apiPageSuccess([])) })
  );
  await page.route(`**/v1/students/${id}/photo`, (route) =>
    route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify(apiError("NOT_FOUND", "No photo")) })
  );
  await page.route(`**/v1/students/${id}/qr.png`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "image/png",
      body: Buffer.from(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=",
        "base64"
      ),
    })
  );
}
