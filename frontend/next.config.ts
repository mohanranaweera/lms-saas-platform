import type { NextConfig } from "next";

/**
 * TEN-DOM-01 / ADR-021: the browser calls `/api` on the site's own address
 * (platform subdomain or tenant custom domain). In dev, and whenever `next
 * start` serves a request no proxy handled, this rewrite forwards it to the
 * backend. Next's proxy sends the backend its own Host and passes the site's
 * address in `X-Forwarded-Host`, which the backend trusts from loopback/private
 * proxies only (`server.forward-headers-strategy: native`). In production Nginx
 * routes `/api` to the backend before it reaches Next.
 *
 * Server-side only, read when the config loads (build time for `next build`).
 */
const backendOrigin = (process.env.BACKEND_ORIGIN ?? "http://localhost:8080").replace(/\/$/, "");

const nextConfig: NextConfig = {
  /**
   * Next.js's dev server only trusts requests whose Origin looks like
   * localhost by default (DNS-rebinding protection). Tenant sites are browsed
   * at `<subdomain>.lms.test:3000` and, for custom domains, at any `*.test`
   * name mapped in the hosts file (e.g. `learn.abc.test`); `**.test` allows
   * both. Dev-only - `allowedDevOrigins` has no effect on `next build`/`next start`.
   */
  allowedDevOrigins: ["**.test"],
  /**
   * Video providers + video settings were merged into Settings → Video. HTTP
   * redirects (query string kept) so the YouTube connect callback, which the
   * backend still returns to `/settings/video-providers?pending=…`
   * (`OAuthConnectFlowService.TENANT_VIDEO_RETURN_PATH`), and old bookmarks keep working.
   */
  async redirects() {
    return [
      { source: "/tenant-admin/settings/video-providers", destination: "/tenant-admin/settings/video", permanent: false },
      { source: "/tenant-admin/settings/video-configuration", destination: "/tenant-admin/settings/video", permanent: false },
    ];
  },
  async rewrites() {
    return [{ source: "/api/:path*", destination: `${backendOrigin}/api/:path*` }];
  },
};

export default nextConfig;
