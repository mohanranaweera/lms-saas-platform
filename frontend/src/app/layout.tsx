import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { QueryProvider } from "@/components/providers/query-provider";
import { AuthProvider } from "@/lib/auth/auth-context";
import { I18nProvider } from "@/i18n/i18n-provider";
import { SCRIPT_FONT_VARIABLE_CLASSES } from "@/lib/fonts/script-fonts";
import { THEME_INIT_SCRIPT } from "@/lib/theme/theme-mode";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: {
    default: "LMS Platform",
    template: "%s | LMS Platform",
  },
  description:
    "Multi-tenant SaaS learning management and institute management platform.",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    // suppressHydrationWarning: THEME_INIT_SCRIPT sets the `dark` class and
    // color-scheme on <html> before hydration (LOGIN-01 Phase A). I18N-01:
    // `lang`/`dir` start as English and are updated by I18nProvider once the
    // locale is resolved.
    <html
      lang="en"
      className={`${geistSans.variable} ${geistMono.variable} ${SCRIPT_FONT_VARIABLE_CLASSES} h-full antialiased`}
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body className="min-h-full flex flex-col bg-background text-foreground">
        <QueryProvider>
          <AuthProvider>
            <I18nProvider>{children}</I18nProvider>
          </AuthProvider>
        </QueryProvider>
      </body>
    </html>
  );
}
