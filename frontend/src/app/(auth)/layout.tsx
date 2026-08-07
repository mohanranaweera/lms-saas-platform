import { CenteredAuthShell } from "@/components/layout/centered-auth-shell";

export default function AuthLayout({ children }: { children: React.ReactNode }) {
  return <CenteredAuthShell>{children}</CenteredAuthShell>;
}
