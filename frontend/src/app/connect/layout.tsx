import { CenteredAuthShell } from "@/components/layout/centered-auth-shell";

/**
 * INST-QR-01: `/connect/v1/{code}` is served from the platform's connection
 * origin, not a tenant host, so it uses neutral platform branding — never a
 * host-resolved tenant's.
 */
export default function ConnectLayout({ children }: { children: React.ReactNode }) {
  return <CenteredAuthShell>{children}</CenteredAuthShell>;
}
