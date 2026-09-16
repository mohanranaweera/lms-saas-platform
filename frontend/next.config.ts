import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  /**
   * Next.js's dev server only trusts requests whose Origin looks like
   * localhost by default (DNS-rebinding protection) - browsing the app at
   * `demo.lms.test:3000` (needed so the tenant-scoped refresh-token cookie,
   * which is SameSite=Strict, is same-site with the API at
   * `demo.lms.test:8080`; see backend SecurityFilterChainConfig's local CORS
   * javadoc) is otherwise silently rejected, breaking client-side hydration
   * on that origin. Dev-only - `allowedDevOrigins` has no effect on `next
   * build`/`next start`.
   */
  allowedDevOrigins: ["demo.lms.test"],
};

export default nextConfig;
