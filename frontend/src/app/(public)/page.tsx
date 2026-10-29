"use client";

import Link from "next/link";
import { useState } from "react";
import { ArrowRight, BookOpen, ChevronLeft, ChevronRight, CircleHelp, LifeBuoy, LogIn, Mail, Phone, UserPlus } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { TeacherCarousel } from "@/components/public/teacher-carousel";
import { TENANT_FALLBACK_NAME } from "@/lib/branding/names";
import { getCoursePriceLabel } from "@/components/courses/course-price-display";
import { usePublicHome, type PublicHomeResponse } from "@/lib/api/public-home";
import { usePublicCourses } from "@/lib/api/public-courses";
import type { CoursePricingModel } from "@/lib/api/courses";
import { safeYouTubeEmbedUrl } from "@/lib/validation/appearance";
import { cn } from "@/lib/utils";

/**
 * Tenant public home — body only; the header/footer/theme come from
 * `(public)/layout.tsx` (`PublicChrome`). Sections follow the Appearance
 * settings: hero (uploaded banners or a branded hero), optional sub-hero,
 * featured courses, teacher carousel, support (#support), optional CTA.
 * Nothing here grants access: pricing labels are display-only and every
 * action links into backend-authorized flows.
 */
export default function HomePage() {
  const home = usePublicHome();

  if (home.isPending) return <LoadingState label="Loading institute home…" className="flex-1 py-24" />;
  if (home.isError) {
    return (
      <div className="mx-auto w-full max-w-3xl px-4 py-16">
        <ErrorState message="We couldn't load this institute's home page." onRetry={() => void home.refetch()} />
      </div>
    );
  }

  const config = home.data;
  const name = config.instituteName?.trim() || TENANT_FALLBACK_NAME;

  if (!config.publicPageEnabled) {
    return (
      <section className="flex flex-1 items-center justify-center px-4 py-24">
        <Card className="w-full max-w-xl text-center shadow-sm">
          <CardContent className="flex flex-col items-center gap-4 p-8">
            <CircleHelp className="size-10 text-primary" aria-hidden="true" />
            <h1 className="text-2xl font-semibold">Public page unavailable</h1>
            <p className="text-muted-foreground">{name} has not published its public home page. Students and staff can still sign in.</p>
            <Link href="/login" className={buttonVariants({ size: "lg" })}>
              Sign in to the portal
            </Link>
          </CardContent>
        </Card>
      </section>
    );
  }

  return (
    <div className="flex flex-col">
      <Hero config={config} name={name} />
      {config.subHeroEnabled ? <SubHero /> : null}
      <FeaturedCourses config={config} />
      <TeacherCarousel autoRotate={config.teacherAutoRotate === true} />
      <SupportSection config={config} />
      {config.ctaEnabled ? <CtaBanner name={name} /> : null}
    </div>
  );
}

function Hero({ config, name }: { config: PublicHomeResponse; name: string }) {
  const desktop = config.desktopBannerUrls ?? [];
  const mobile = config.mobileBannerUrls ?? [];
  const count = desktop.length || mobile.length;
  const [index, setIndex] = useState(0);

  if (count === 0) return <BrandedHero name={name} />;

  const active = index % count;
  const desktopSrc = desktop.length ? desktop[active % desktop.length] : null;
  const mobileSrc = mobile.length ? mobile[active % mobile.length] : null;
  const alt = `${name} banner ${active + 1} of ${count}`;
  const go = (next: number) => setIndex((next + count) % count);

  return (
    <>
      {/* Only mobile banners uploaded: branded hero on wider screens. */}
      <h1 className="sr-only">{name}</h1>
      {!desktopSrc ? <BrandedHero name={name} className="hidden sm:flex" headingAs="p" /> : null}
      <section
        aria-roledescription="carousel"
        aria-label="Featured banners"
        className={cn("relative w-full bg-muted", !desktopSrc && "sm:hidden")}
        data-testid="hero-banners"
      >
        <div className={cn("relative w-full overflow-hidden", mobileSrc ? "aspect-[8/3] sm:aspect-[4/1]" : "aspect-[4/1]")}>
          <picture>
            {mobileSrc && desktopSrc ? <source media="(max-width: 639px)" srcSet={mobileSrc} /> : null}
            <img src={(desktopSrc ?? mobileSrc) as string} alt={alt} className="absolute inset-0 size-full object-cover" />
          </picture>
        </div>
        {count > 1 ? (
          <>
            <button
              type="button"
              onClick={() => go(active - 1)}
              aria-label="Previous banner"
              className="absolute left-2 top-1/2 flex size-9 -translate-y-1/2 items-center justify-center rounded-full bg-black/45 text-white outline-none hover:bg-black/65 focus-visible:ring-3 focus-visible:ring-white sm:left-4"
            >
              <ChevronLeft className="size-5" aria-hidden="true" />
            </button>
            <button
              type="button"
              onClick={() => go(active + 1)}
              aria-label="Next banner"
              className="absolute right-2 top-1/2 flex size-9 -translate-y-1/2 items-center justify-center rounded-full bg-black/45 text-white outline-none hover:bg-black/65 focus-visible:ring-3 focus-visible:ring-white sm:right-4"
            >
              <ChevronRight className="size-5" aria-hidden="true" />
            </button>
            <div className="absolute inset-x-0 bottom-2 flex justify-center gap-1.5 sm:bottom-3">
              {Array.from({ length: count }, (_, dot) => (
                <button
                  key={dot}
                  type="button"
                  onClick={() => go(dot)}
                  aria-label={`Show banner ${dot + 1}`}
                  aria-current={dot === active ? "true" : undefined}
                  className={cn(
                    "h-2 rounded-full bg-white/70 shadow outline-none transition-all focus-visible:ring-3 focus-visible:ring-white",
                    dot === active ? "w-6 bg-white" : "w-2"
                  )}
                />
              ))}
            </div>
            <p className="sr-only" aria-live="polite">
              Banner {active + 1} of {count}
            </p>
          </>
        ) : null}
      </section>
    </>
  );
}

function BrandedHero({ name, className, headingAs: Heading = "h1" }: { name: string; className?: string; headingAs?: "h1" | "p" }) {
  return (
    <section className={cn("relative flex overflow-hidden bg-primary text-primary-foreground", className)} data-testid="branded-hero">
      <span className="pointer-events-none absolute -right-24 -top-24 size-80 rounded-full bg-current opacity-[0.07]" aria-hidden="true" />
      <span className="pointer-events-none absolute -bottom-32 left-1/3 size-72 rounded-full bg-current opacity-[0.05]" aria-hidden="true" />
      <div className="relative mx-auto flex w-full max-w-7xl flex-col items-start gap-5 px-4 py-16 sm:px-6 sm:py-20 lg:px-8 lg:py-24">
        <p className="text-xs font-semibold uppercase tracking-[0.2em] opacity-80">Welcome to</p>
        <Heading className="font-heading max-w-3xl text-3xl font-bold tracking-tight break-words sm:text-5xl">{name}</Heading>
        <p className="max-w-xl text-base opacity-90 sm:text-lg">Explore our courses and start learning with experienced teachers.</p>
        <Link href="/courses" className={cn(buttonVariants({ size: "lg" }), "h-11 bg-card px-5 text-card-foreground hover:bg-card/90")}>
          Explore courses <ArrowRight aria-hidden="true" />
        </Link>
      </div>
    </section>
  );
}

const ACTIONS = [
  { icon: UserPlus, title: "Register for a course", description: "Create your student account, then choose the course you want to join.", href: "/register" },
  { icon: LogIn, title: "Join / sign in", description: "Already registered? Sign in and continue where you left off.", href: "/login" },
  { icon: LifeBuoy, title: "How to use", description: "Watch the guide and find out how to get started.", href: "/#support" },
] as const;

function SubHero() {
  return (
    <section className="bg-(--tenant-primary-soft) py-12 sm:py-16" aria-labelledby="sub-hero-heading" data-testid="sub-hero">
      <div className="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
        <div className="text-center">
          <h2 id="sub-hero-heading" className="text-2xl font-bold tracking-tight sm:text-3xl">
            Get an incredible learning experience
          </h2>
          <p className="mt-2 text-muted-foreground">Everything you need to start learning, in one place.</p>
        </div>
        <ul className="mt-8 grid gap-4 md:grid-cols-3">
          {ACTIONS.map(({ icon: Icon, title, description, href }) => (
            <li key={title}>
              <Link
                href={href}
                className="group flex h-full items-start gap-4 rounded-xl border border-border bg-card p-5 text-card-foreground shadow-sm outline-none transition-shadow hover:shadow-md focus-visible:ring-3 focus-visible:ring-ring/50"
              >
                <span className="flex size-11 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary">
                  <Icon className="size-5" aria-hidden="true" />
                </span>
                <span className="flex min-w-0 flex-1 flex-col gap-1">
                  <span className="font-heading text-base font-semibold">{title}</span>
                  <span className="text-sm text-muted-foreground">{description}</span>
                </span>
                <ArrowRight className="mt-1 size-4 shrink-0 text-primary transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

interface CourseCardData {
  id: string;
  name: string;
  slug: string;
  category: string;
  coverUrl: string | null;
  pricingModel: CoursePricingModel | null;
  amount: number | null;
  currency: string | null;
  requiresManualQuote: boolean;
}

/** Price/access label via the shared helper; null when the projection lacks pricing data. */
function priceLabel(course: CourseCardData): string | null {
  if (!course.pricingModel) return null;
  const needsCurrency = !course.requiresManualQuote && course.pricingModel !== "FREE" && course.amount != null;
  if (needsCurrency && !course.currency) return null;
  return getCoursePriceLabel({
    pricingModel: course.pricingModel,
    resolvedAmount: course.amount,
    currency: course.currency ?? "",
    requiresManualQuote: course.requiresManualQuote,
  });
}

function FeaturedCourses({ config }: { config: PublicHomeResponse }) {
  const configured = config.featuredCourses ?? [];
  const fallback = usePublicCourses({ page: 0, size: 6 });
  const usingFallback = configured.length === 0;

  let courses: CourseCardData[] = configured.map((course) => ({ ...course, coverUrl: course.coverUrl ?? null }));
  if (usingFallback) {
    if (fallback.isPending) return <FeaturedShell loading />;
    if (fallback.isError) {
      return (
        <FeaturedShell>
          <ErrorState message="The course list is temporarily unavailable." onRetry={() => void fallback.refetch()} />
        </FeaturedShell>
      );
    }
    courses = fallback.data.content.slice(0, 6).map((course) => ({
      id: course.id,
      name: course.name,
      slug: course.slug,
      category: course.category,
      coverUrl: course.coverImageUrl,
      pricingModel: course.pricingModel,
      amount: course.resolvedAmount,
      currency: course.currency,
      requiresManualQuote: course.requiresManualQuote,
    }));
  }
  if (courses.length === 0) return null;

  return (
    <FeaturedShell>
      <ul className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {courses.map((course) => (
          <li key={course.id}>
            <CourseCard course={course} />
          </li>
        ))}
      </ul>
    </FeaturedShell>
  );
}

function FeaturedShell({ children, loading = false }: { children?: React.ReactNode; loading?: boolean }) {
  return (
    <section className="mx-auto w-full max-w-7xl px-4 py-14 sm:px-6 lg:px-8" aria-labelledby="featured-heading" aria-busy={loading} data-testid="featured-courses">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-widest text-primary">Featured</p>
          <h2 id="featured-heading" className="mt-2 text-2xl font-bold tracking-tight sm:text-3xl">
            Featured courses
          </h2>
          <p className="mt-2 text-muted-foreground">Hand-picked courses to start with.</p>
        </div>
        <Link href="/courses" className="inline-flex items-center gap-1 text-sm font-semibold text-primary hover:underline">
          View all courses <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </div>
      <div className="mt-8">
        {loading ? (
          <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3" role="status" aria-label="Loading courses">
            {[0, 1, 2].map((key) => (
              <Skeleton key={key} className={cn("h-72 rounded-xl", ["", "hidden sm:block", "hidden lg:block"][key])} />
            ))}
          </div>
        ) : (
          children
        )}
      </div>
    </section>
  );
}

function CourseCard({ course }: { course: CourseCardData }) {
  const [coverFailed, setCoverFailed] = useState(false);
  const label = priceLabel(course);
  return (
    <article className="flex h-full flex-col overflow-hidden rounded-xl border border-border bg-card text-card-foreground shadow-sm transition-shadow hover:shadow-md">
      <div className="relative aspect-[16/9] overflow-hidden bg-(--tenant-primary-soft)">
        {course.coverUrl && !coverFailed ? (
          // eslint-disable-next-line @next/next/no-img-element -- signed course cover URL
          <img src={course.coverUrl} alt="" className="absolute inset-0 size-full object-cover" loading="lazy" onError={() => setCoverFailed(true)} />
        ) : (
          <span className="absolute inset-0 flex items-center justify-center text-primary" aria-hidden="true">
            <BookOpen className="size-10 opacity-60" />
          </span>
        )}
        {label ? (
          <span className="absolute bottom-3 left-3 rounded-full bg-card px-3 py-1 text-xs font-semibold text-card-foreground shadow-sm" data-testid="featured-price">
            {label}
          </span>
        ) : null}
      </div>
      <div className="flex flex-1 flex-col gap-2 p-5">
        {course.category ? <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{course.category}</p> : null}
        <h3 className="line-clamp-2 text-lg font-semibold">{course.name}</h3>
        <Link
          href={`/courses/${encodeURIComponent(course.slug)}`}
          className="mt-auto inline-flex items-center justify-end gap-1 border-t border-border pt-4 text-sm font-semibold text-primary hover:underline"
        >
          View details<span className="sr-only"> for {course.name}</span>
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </div>
    </article>
  );
}

function SupportSection({ config }: { config: PublicHomeResponse }) {
  const embedUrl = safeYouTubeEmbedUrl(config.supportVideoUrl);
  const phone = config.supportPhone?.trim();
  const email = config.supportEmail?.trim();
  return (
    <section id="support" className="scroll-mt-24 bg-(--tenant-primary-soft) py-14" aria-labelledby="support-heading" data-testid="support-section">
      <div className="mx-auto max-w-4xl px-4 sm:px-6 lg:px-8">
        <div className="text-center">
          <p className="text-xs font-semibold uppercase tracking-widest text-primary">Support</p>
          <h2 id="support-heading" className="mt-2 text-2xl font-bold tracking-tight sm:text-3xl">
            How to use
          </h2>
          <p className="mt-2 text-muted-foreground">
            {embedUrl ? "Watch this short guide to get started." : "Need a hand getting started? Sign in or contact us and we'll help."}
          </p>
        </div>
        {embedUrl ? (
          <div className="relative mt-8 aspect-video overflow-hidden rounded-xl bg-black shadow-lg">
            <iframe
              title="Support video"
              src={embedUrl}
              className="absolute inset-0 size-full"
              loading="lazy"
              allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
              allowFullScreen
            />
          </div>
        ) : null}
        {phone || email ? (
          <ul className="mt-6 flex flex-wrap justify-center gap-x-6 gap-y-2 text-sm">
            {phone ? (
              <li className="flex items-center gap-2">
                <Phone className="size-4 text-primary" aria-hidden="true" />
                <a href={`tel:${phone.replace(/[^+\d]/g, "")}`} className="hover:text-primary">
                  {phone}
                </a>
              </li>
            ) : null}
            {email ? (
              <li className="flex items-center gap-2">
                <Mail className="size-4 text-primary" aria-hidden="true" />
                <a href={`mailto:${email}`} className="break-all hover:text-primary">
                  {email}
                </a>
              </li>
            ) : null}
          </ul>
        ) : null}
      </div>
    </section>
  );
}

function CtaBanner({ name }: { name: string }) {
  return (
    <section className="mx-auto w-full max-w-7xl px-4 py-14 sm:px-6 lg:px-8" aria-labelledby="cta-heading" data-testid="cta-section">
      <div className="relative overflow-hidden rounded-2xl bg-primary px-6 py-10 text-primary-foreground shadow-lg sm:px-12 sm:py-12">
        <span className="pointer-events-none absolute -right-16 -top-16 size-64 rounded-full bg-current opacity-[0.07]" aria-hidden="true" />
        <span className="pointer-events-none absolute -bottom-24 right-1/3 size-48 rounded-full bg-current opacity-[0.05]" aria-hidden="true" />
        <div className="relative flex flex-col gap-6 lg:flex-row lg:items-center lg:justify-between">
          <div className="max-w-2xl">
            <h2 id="cta-heading" className="text-2xl font-bold tracking-tight sm:text-3xl">
              Start learning with {name}
            </h2>
            <p className="mt-3 opacity-90">Create your account to enrol in courses, join classes and follow your progress from any device.</p>
          </div>
          <div className="flex flex-col gap-3 sm:flex-row">
            <Link href="/register" className={cn(buttonVariants({ size: "lg" }), "h-11 bg-card px-6 text-card-foreground hover:bg-card/90")}>
              Register
            </Link>
            <Link href="/login" className={cn(buttonVariants({ variant: "outline", size: "lg" }), "h-11 border-current bg-transparent px-6 text-current hover:bg-current/10 hover:text-current")}>
              Login
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
