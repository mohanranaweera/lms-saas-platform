"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useId, useState } from "react";
import { ChevronRight, Mail, MapPin, Menu, Phone, X } from "lucide-react";
import { Skeleton } from "@/components/ui/skeleton";
import { PublicLogo, toLogoDisplaySize, type LogoDisplaySize } from "@/components/branding/public-logo";
import { PublicThemeScope } from "@/components/branding/public-theme-scope";
import { PublicAccountActions, usePublicSession } from "@/components/layout/public-header-nav";
import { POLICY_PAGES, usePublicHome, type PolicyKind, type PublicHomeResponse } from "@/lib/api/public-home";
import { InstituteSocialLinks, instituteAddressLines } from "@/components/public/institute-info";
import { ThemeToggle } from "@/components/theme/theme-toggle";
import { useTenantDocumentTitle, useTenantFavicon } from "@/lib/branding/use-tenant-head";
import { PLATFORM_DEFAULT_NAME, TENANT_FALLBACK_NAME } from "@/lib/branding/names";


const NAV_LINKS = [
  { label: "Courses", href: "/courses" },
  { label: "Teachers", href: "/teachers" },
  { label: "Support", href: "/#support" },
] as const;

const SITEMAP_LINKS = [{ label: "Home", href: "/" }, ...NAV_LINKS] as const;

const PAGE_TITLES: Record<string, string> = { "/courses": "Courses", "/teachers": "Teachers" };

function isExternalHttpUrl(value: string | null | undefined): value is string {
  if (!value) return false;
  try {
    const url = new URL(value);
    return url.protocol === "https:" || url.protocol === "http:";
  } catch {
    return false;
  }
}

function LogoLink({ home, size }: { home: PublicHomeResponse | undefined; size: LogoDisplaySize }) {
  const name = home ? home.instituteName?.trim() || TENANT_FALLBACK_NAME : PLATFORM_DEFAULT_NAME;
  const logo = <PublicLogo logoUrl={home?.logoUrl} instituteName={name} size={size} />;
  const className = "flex min-w-0 items-center rounded-md outline-none focus-visible:ring-3 focus-visible:ring-ring/50";
  if (isExternalHttpUrl(home?.homepageUrl)) {
    return (
      <a href={home.homepageUrl} className={className} aria-label={`${name} home`}>
        {logo}
      </a>
    );
  }
  return (
    <Link href="/" className={className} aria-label={`${name} home`}>
      {logo}
    </Link>
  );
}

function PublicSiteHeader({ home, pending, tenantResolved }: { home: PublicHomeResponse | undefined; pending: boolean; tenantResolved: boolean }) {
  const menuId = useId();
  const pathname = usePathname();
  const [menuOpen, setMenuOpen] = useState(false);
  const [menuPath, setMenuPath] = useState(pathname);
  const session = usePublicSession();
  const size = toLogoDisplaySize(home?.logoDisplaySize);

  // Close the mobile menu whenever the route changes.
  if (menuPath !== pathname) {
    setMenuPath(pathname);
    setMenuOpen(false);
  }

  useEffect(() => {
    if (!menuOpen) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setMenuOpen(false);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [menuOpen]);

  return (
    <header className="public-chrome sticky top-0 z-30 border-b border-border bg-card/95 text-card-foreground backdrop-blur" data-testid="public-site-header">
      <div className="mx-auto flex min-h-16 max-w-7xl items-center justify-between gap-4 px-4 py-2 sm:px-6 md:min-h-20 lg:px-8">
        <div className="flex min-w-0 items-center gap-8">
          {pending ? <Skeleton className="h-10 w-40" aria-label="Loading institute" /> : <LogoLink home={home} size={size} />}
          <nav aria-label="Main" className="hidden items-center gap-6 md:flex">
            {NAV_LINKS.map((link) => (
              <Link key={link.href} href={link.href} className="text-sm font-medium text-foreground/90 transition-colors hover:text-primary">
                {link.label}
              </Link>
            ))}
          </nav>
        </div>
        <div className="hidden md:ml-auto md:block">
          <PublicAccountActions state={session} tenantResolved={tenantResolved} />
        </div>
        <ThemeToggle className="ml-auto md:ml-0" />
        <button
          type="button"
          className="inline-flex size-10 items-center justify-center rounded-lg text-foreground hover:bg-muted focus-visible:ring-3 focus-visible:ring-ring/50 md:hidden"
          aria-expanded={menuOpen}
          aria-controls={menuId}
          aria-label={menuOpen ? "Close menu" : "Open menu"}
          onClick={() => setMenuOpen((open) => !open)}
        >
          {menuOpen ? <X className="size-5" aria-hidden="true" /> : <Menu className="size-5" aria-hidden="true" />}
        </button>
      </div>
      <div id={menuId} hidden={!menuOpen} className="border-t border-border px-4 pb-4 pt-2 md:hidden">
        <nav aria-label="Mobile" className="flex flex-col">
          {NAV_LINKS.map((link) => (
            <Link key={link.href} href={link.href} className="rounded-md px-2 py-3 text-base font-medium text-foreground hover:bg-muted" onClick={() => setMenuOpen(false)}>
              {link.label}
            </Link>
          ))}
        </nav>
        <div className="mt-3">
          <PublicAccountActions state={session} tenantResolved={tenantResolved} layout="stack" />
        </div>
      </div>
    </header>
  );
}

function PublicSiteFooter({ home }: { home: PublicHomeResponse | undefined }) {
  const name = home ? home.instituteName?.trim() || TENANT_FALLBACK_NAME : PLATFORM_DEFAULT_NAME;
  const phone = home?.supportPhone?.trim();
  const email = home?.supportEmail?.trim();
  const address = instituteAddressLines(home?.institute);
  const policyLinks = (Object.keys(POLICY_PAGES) as PolicyKind[]).filter((kind) => home?.policies?.[POLICY_PAGES[kind].flag]);
  const year = new Date().getFullYear();

  return (
    <footer className="public-chrome mt-auto border-t border-border bg-card text-card-foreground" data-testid="public-site-footer">
      <div className="mx-auto grid max-w-7xl gap-10 px-4 py-12 sm:px-6 md:grid-cols-[2fr_1fr_1.4fr] lg:px-8">
        <div className="flex flex-col gap-3">
          <PublicLogo logoUrl={home?.logoUrl} instituteName={name} variant="footer" />
          {home?.logoUrl ? <p className="font-heading text-base font-semibold">{name}</p> : null}
          <InstituteSocialLinks info={home?.institute} instituteName={name} className="mt-1" />
        </div>
        <nav aria-labelledby="footer-sitemap-heading">
          <h2 id="footer-sitemap-heading" className="text-xs font-semibold uppercase tracking-widest text-foreground">
            Sitemap
          </h2>
          <span className="mt-2 block h-0.5 w-6 rounded bg-primary" aria-hidden="true" />
          <ul className="mt-4 flex flex-col gap-2.5">
            {SITEMAP_LINKS.map((link) => (
              <li key={link.href}>
                <Link href={link.href} className="inline-flex items-center gap-1 text-sm text-muted-foreground transition-colors hover:text-primary">
                  <ChevronRight className="size-3.5" aria-hidden="true" />
                  {link.label}
                </Link>
              </li>
            ))}
          </ul>
        </nav>
        {phone || email || address.length > 0 ? (
          <div>
            <h2 className="text-xs font-semibold uppercase tracking-widest text-foreground">Contact us</h2>
            <span className="mt-2 block h-0.5 w-6 rounded bg-primary" aria-hidden="true" />
            <ul className="mt-4 flex flex-col gap-4">
              {phone ? (
                <li className="flex items-center gap-3">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <Phone className="size-4" aria-hidden="true" />
                  </span>
                  <span className="flex min-w-0 flex-col">
                    <span className="text-[0.7rem] font-semibold uppercase tracking-wider text-muted-foreground">Phone</span>
                    <a href={`tel:${phone.replace(/[^+\d]/g, "")}`} className="break-words text-sm hover:text-primary">
                      {phone}
                    </a>
                  </span>
                </li>
              ) : null}
              {email ? (
                <li className="flex items-center gap-3">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <Mail className="size-4" aria-hidden="true" />
                  </span>
                  <span className="flex min-w-0 flex-col">
                    <span className="text-[0.7rem] font-semibold uppercase tracking-wider text-muted-foreground">Email</span>
                    <a href={`mailto:${email}`} className="break-all text-sm hover:text-primary">
                      {email}
                    </a>
                  </span>
                </li>
              ) : null}
              {address.length > 0 ? (
                <li className="flex items-start gap-3">
                  <span className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                    <MapPin className="size-4" aria-hidden="true" />
                  </span>
                  <span className="flex min-w-0 flex-col">
                    <span className="text-[0.7rem] font-semibold uppercase tracking-wider text-muted-foreground">Address</span>
                    <address className="text-sm not-italic" data-testid="footer-address">
                      {address.map((line) => (
                        <span key={line} className="block break-words">
                          {line}
                        </span>
                      ))}
                    </address>
                  </span>
                </li>
              ) : null}
            </ul>
          </div>
        ) : null}
      </div>
      <div className="border-t border-border">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-x-6 gap-y-2 px-4 py-4 text-xs text-muted-foreground sm:px-6 lg:px-8">
          <p>
            &copy; {year} {name}. All rights reserved.
          </p>
          {policyLinks.length > 0 ? (
            <nav aria-label="Policies" data-testid="footer-policies">
              <ul className="flex flex-wrap gap-x-4 gap-y-1">
                {policyLinks.map((kind) => (
                  <li key={kind}>
                    <Link href={POLICY_PAGES[kind].href} className="hover:text-primary hover:underline">
                      {POLICY_PAGES[kind].title}
                    </Link>
                  </li>
                ))}
              </ul>
            </nav>
          ) : null}
        </div>
      </div>
    </footer>
  );
}

/**
 * Public route-group chrome: ONE tenant-themed header + footer around every
 * public page (`/`, `/courses`, `/teachers`, …). Branding comes from the
 * host-resolved public home projection; if no institute matches the host
 * (platform domain, network failure) neutral platform defaults are used —
 * never another tenant's cached branding (cache key includes the host).
 */
export function PublicChrome({ children }: { children: React.ReactNode }) {
  const home = usePublicHome();
  const data = home.data;
  useTenantFavicon(data?.faviconUrl);
  useTenantDocumentTitle((pathname) => {
    const name = data?.instituteName?.trim();
    if (!name) return null;
    const page = PAGE_TITLES[pathname];
    return page ? `${page} | ${name}` : name;
  });

  return (
    <PublicThemeScope theme={data?.theme} className="flex min-h-svh flex-col" data-testid="public-theme-scope">
      <a
        href="#public-main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:bg-card focus:px-3 focus:py-2 focus:text-sm focus:shadow"
      >
        Skip to content
      </a>
      <PublicSiteHeader home={data} pending={home.isPending} tenantResolved={home.isSuccess} />
      <main id="public-main" className="flex flex-1 flex-col">
        {children}
      </main>
      {/* No footer until the tenant is known: avoids a flash of platform branding. */}
      {home.isPending ? null : <PublicSiteFooter home={data} />}
    </PublicThemeScope>
  );
}
