import type { Metadata } from "next";
import type { ReactNode } from "react";

/** AUTH-PWD-01: the page URL once carried a one-time token - never send it on as a Referer. */
export const metadata: Metadata = { title: "Reset your password", referrer: "no-referrer" };

export default function ResetPasswordLayout({ children }: { children: ReactNode }) {
  return children;
}
