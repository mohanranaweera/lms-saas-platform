import { test, expect } from "@playwright/test";
import {
  classifyStorefrontError,
  mayBeStorefrontHost,
  movedTargetHost,
  movedUrl,
  normalizeSiteContext,
  storefrontTargetFromTenantHost,
  storefrontUrlWithSlug,
  tenantHostOf,
} from "../../src/lib/storefront/site-context";
import { toProductRows } from "../../src/app/(tenant-admin)/tenant-admin/teachers/storefronts/[teacherId]/storefront-products-form";
import { storefrontSettingsSchema, storefrontSlugHint } from "../../src/lib/validation/teacher-storefront";
import { composerErrorMessage } from "../../src/components/payments/composer/composer-model";
import { ApiClientError } from "../../src/lib/api/error";

/** Unit tests (no browser) for STORE-01 site-context handling (ADR-037). */
test.describe("site context", () => {
  test("normalizes a storefront payload and fails open to TENANT", () => {
    expect(
      normalizeSiteContext({
        kind: "STOREFRONT",
        storefront: { slug: "alice", teacherId: "t-1", displayName: "Alice Perera", photoUrl: "", bio: "  ", host: "alice.abc.lms.test" },
      })
    ).toEqual({
      kind: "STOREFRONT",
      storefront: { slug: "alice", teacherId: "t-1", displayName: "Alice Perera", photoUrl: null, bio: null, host: "alice.abc.lms.test" },
    });
    expect(normalizeSiteContext({ kind: "TENANT", storefront: null })).toEqual({ kind: "TENANT", storefront: null });
    expect(normalizeSiteContext({ kind: "STOREFRONT", storefront: null })).toEqual({ kind: "TENANT", storefront: null });
    expect(normalizeSiteContext(null)).toEqual({ kind: "TENANT", storefront: null });
    expect(normalizeSiteContext("nope")).toEqual({ kind: "TENANT", storefront: null });
  });

  test("tenant site is the host minus its first label, port kept", () => {
    expect(tenantHostOf("alice.abc.lms.test:3000")).toBe("abc.lms.test:3000");
    expect(tenantHostOf("Alice.ABC.lms.test")).toBe("abc.lms.test");
    expect(tenantHostOf("lms.test")).toBeNull();
    expect(tenantHostOf("localhost:3000")).toBeNull();
  });

  test("only multi-label DNS hosts may be storefront hosts", () => {
    expect(mayBeStorefrontHost("alice.abc.lms.test:3000")).toBe(true);
    expect(mayBeStorefrontHost("abc.lms.test")).toBe(true);
    expect(mayBeStorefrontHost("localhost:3000")).toBe(false);
    expect(mayBeStorefrontHost("127.0.0.1:3000")).toBe(false);
    expect(mayBeStorefrontHost("[::1]:3000")).toBe(false);
    expect(mayBeStorefrontHost("lms.test")).toBe(false);
    expect(mayBeStorefrontHost(null)).toBe(false);
  });

  test("classifies the two storefront codes and ignores everything else", () => {
    const host = "old-alice.abc.lms.test:3000";
    expect(classifyStorefrontError(404, "STOREFRONT_NOT_FOUND", null, host)).toEqual({ kind: "not-found" });
    expect(classifyStorefrontError(404, "STOREFRONT_MOVED", { host: "alice.abc.lms.test" }, host)).toEqual({
      kind: "moved",
      host: "alice.abc.lms.test:3000",
    });
    expect(classifyStorefrontError(404, "NOT_FOUND", null, host)).toBeNull();
    expect(classifyStorefrontError(422, "STOREFRONT_NOT_FOUND", null, host)).toBeNull();
    expect(classifyStorefrontError(404, "TENANT_UNAVAILABLE", null, host)).toBeNull();
  });

  test("a moved target must be another storefront of the same tenant, else not-found", () => {
    const host = "old-alice.abc.lms.test";
    expect(movedTargetHost(host, "alice.abc.lms.test")).toBe("alice.abc.lms.test");
    expect(movedTargetHost(host, "alice.abc.lms.test:8443")).toBe("alice.abc.lms.test:8443");
    expect(movedTargetHost(host, "alice.other.lms.test")).toBeNull();
    expect(movedTargetHost(host, "evil.test")).toBeNull();
    expect(movedTargetHost(host, "https://alice.abc.lms.test")).toBeNull();
    expect(movedTargetHost(host, "alice.abc.lms.test/path")).toBeNull();
    expect(movedTargetHost(host, "old-alice.abc.lms.test")).toBeNull();
    expect(movedTargetHost(host, 42)).toBeNull();
    expect(classifyStorefrontError(404, "STOREFRONT_MOVED", { host: "evil.test" }, host)).toEqual({ kind: "not-found" });
    expect(classifyStorefrontError(404, "STOREFRONT_MOVED", null, host)).toEqual({ kind: "not-found" });
  });

  test("COURSE_ON_STOREFRONT on the institute host moves only to a storefront of this tenant", () => {
    const host = "abc.lms.test:3000";
    expect(storefrontTargetFromTenantHost(host, "alice.abc.lms.test")).toBe("alice.abc.lms.test:3000");
    expect(storefrontTargetFromTenantHost("abc.lms.test", "alice.abc.lms.test:8443")).toBe("alice.abc.lms.test:8443");
    expect(storefrontTargetFromTenantHost(host, "alice.other.lms.test")).toBeNull();
    expect(storefrontTargetFromTenantHost(host, "a.alice.abc.lms.test")).toBeNull();
    expect(storefrontTargetFromTenantHost(host, "abc.lms.test")).toBeNull();
    expect(storefrontTargetFromTenantHost(host, "evil.test")).toBeNull();
    expect(storefrontTargetFromTenantHost(host, "https://alice.abc.lms.test")).toBeNull();
    expect(storefrontTargetFromTenantHost(host, null)).toBeNull();
    expect(classifyStorefrontError(404, "COURSE_ON_STOREFRONT", { host: "alice.abc.lms.test" }, host)).toEqual({
      kind: "moved",
      host: "alice.abc.lms.test:3000",
    });
    // An unusable target is an ordinary 404 for the page, never the storefront not-found page.
    expect(classifyStorefrontError(404, "COURSE_ON_STOREFRONT", { host: "evil.test" }, host)).toBeNull();
    expect(classifyStorefrontError(404, "COURSE_ON_STOREFRONT", null, host)).toBeNull();
  });

  test("moved URL keeps path, query and hash", () => {
    expect(
      movedUrl({ protocol: "http:", pathname: "/courses/bio", search: "?x=1", hash: "#top" }, "alice.abc.lms.test:3000")
    ).toBe("http://alice.abc.lms.test:3000/courses/bio?x=1#top");
  });

  test("admin address preview swaps the first host label", () => {
    expect(storefrontUrlWithSlug("https://alice.abc.lms.test/", "alice-p")).toBe("https://alice-p.abc.lms.test/");
    expect(storefrontUrlWithSlug("http://alice.abc.lms.test:3000/", "bob")).toBe("http://bob.abc.lms.test:3000/");
    expect(storefrontUrlWithSlug(null, "bob")).toBeNull();
    expect(storefrontUrlWithSlug("not a url", "bob")).toBeNull();
  });
});

test.describe("storefront admin form helpers", () => {
  test("slug hints mirror the ADR-037 rules (server stays the authority)", () => {
    expect(storefrontSlugHint("alice-perera")).toBeNull();
    expect(storefrontSlugHint("ab")).not.toBeNull();
    expect(storefrontSlugHint("a".repeat(41))).not.toBeNull();
    expect(storefrontSlugHint("-alice")).not.toBeNull();
    expect(storefrontSlugHint("alice--p")).not.toBeNull();
    expect(storefrontSlugHint("alice_p")).not.toBeNull();
    expect(storefrontSlugHint("admin")).toMatch(/reserved/);
    const parsed = storefrontSettingsSchema.safeParse({ enabled: true, slug: "  Alice-P " });
    expect(parsed.success && parsed.data.slug).toBe("alice-p");
  });

  test("product rows: listed first by sort order, then unlisted assignable courses", () => {
    const rows = toProductRows({
      id: "s-1",
      teacherId: "t-1",
      slug: "alice",
      enabled: true,
      previewUrl: null,
      publishedUrl: null,
      previousSlugs: [],
      products: [
        { courseId: "c-2", courseTitle: "Chemistry", courseSlug: "chem", published: false, sortOrder: 5 },
        { courseId: "c-1", courseTitle: "Biology", courseSlug: "bio", published: true, sortOrder: 1 },
      ],
      assignableCourses: [
        { courseId: "c-1", title: "Biology", slug: "bio" },
        { courseId: "c-3", title: "Physics", slug: "phy" },
        { courseId: "c-4", title: "Art", slug: "art" },
      ],
    });
    expect(rows.map((row) => [row.courseId, row.listed, row.published, row.sortOrder])).toEqual([
      ["c-1", true, true, 1],
      ["c-2", true, false, 5],
      ["c-4", false, false, 6],
      ["c-3", false, false, 7],
    ]);
  });

  test("checkout shows a friendly message for an out-of-scope storefront course", () => {
    const error = new ApiClientError(
      { code: "STOREFRONT_PRODUCT_OUT_OF_SCOPE", message: "Course c-9 is not in storefront scope", fieldErrors: [] },
      422
    );
    expect(composerErrorMessage(error)).toMatch(/isn't sold on this teacher's page/);
  });
});
