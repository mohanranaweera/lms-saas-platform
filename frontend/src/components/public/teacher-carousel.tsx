"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ArrowRight, ChevronLeft, ChevronRight, Pause, Play } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ErrorState } from "@/components/states/error-state";
import { usePublicTeachers, type PublicTeacher } from "@/lib/api/public-teachers";
import { cn } from "@/lib/utils";

const AUTO_ROTATE_MS = 6000;

function subscribeReducedMotion(callback: () => void) {
  const media = window.matchMedia("(prefers-reduced-motion: reduce)");
  media.addEventListener("change", callback);
  return () => media.removeEventListener("change", callback);
}

function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(
    subscribeReducedMotion,
    () => window.matchMedia("(prefers-reduced-motion: reduce)").matches,
    () => true
  );
}

function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  return (words.length > 1 ? `${words[0][0]}${words[words.length - 1][0]}` : (words[0] ?? "?").slice(0, 2)).toUpperCase();
}

function TeacherPhoto({ teacher }: { teacher: PublicTeacher }) {
  const [failed, setFailed] = useState(false);
  return (
    <div className="relative aspect-[4/5] overflow-hidden bg-(--tenant-primary-soft)">
      {teacher.profileImageUrl && !failed ? (
        // eslint-disable-next-line @next/next/no-img-element -- signed, short-lived profile image URL
        <img
          src={teacher.profileImageUrl}
          alt={`${teacher.name} profile photo`}
          className="absolute inset-0 size-full object-cover"
          loading="lazy"
          onError={() => setFailed(true)}
        />
      ) : (
        <span className="absolute inset-0 flex items-center justify-center" aria-hidden="true">
          <span className="font-heading flex size-24 items-center justify-center rounded-full bg-primary text-3xl font-semibold text-primary-foreground shadow-sm">
            {initials(teacher.name)}
          </span>
        </span>
      )}
    </div>
  );
}

function TeacherCard({ teacher }: { teacher: PublicTeacher }) {
  return (
    <article className="flex h-full flex-col overflow-hidden rounded-xl border border-border bg-card text-card-foreground shadow-sm">
      <TeacherPhoto teacher={teacher} />
      <div className="flex flex-1 flex-col gap-1.5 p-4">
        <h3 className="line-clamp-2 text-base font-semibold">{teacher.name}</h3>
        {teacher.qualifications ? <p className="line-clamp-2 text-sm text-muted-foreground">{teacher.qualifications}</p> : null}
        <Link
          href={`/teachers/${encodeURIComponent(teacher.id)}`}
          className="mt-auto inline-flex items-center gap-1 pt-3 text-sm font-semibold text-primary hover:underline"
        >
          View profile<span className="sr-only"> of {teacher.name}</span>
          <ArrowRight className="size-4" aria-hidden="true" />
        </Link>
      </div>
    </article>
  );
}

/**
 * Public teacher carousel (spec R16). CSS scroll-snap track (native touch
 * swipe) with prev/next buttons that stop at the ends — no clones, no
 * infinite loop. Visible cards: 1 / 2 (sm) / 3 (md) / 4 (lg).
 *
 * Optional auto-rotate (tenant setting, default off): advances one card every
 * 6 s, wrapping to the start by scrolling; pauses on hover/focus-within and
 * via a visible Pause button; disabled entirely under prefers-reduced-motion.
 * It only scrolls the track — focus is never moved.
 */
export function TeacherCarousel({ autoRotate }: { autoRotate: boolean }) {
  const query = usePublicTeachers(0, 20);
  const teachers = query.data?.content ?? [];
  const trackRef = useRef<HTMLDivElement>(null);
  const [atStart, setAtStart] = useState(true);
  const [atEnd, setAtEnd] = useState(true);
  const [paused, setPaused] = useState(false);
  const [hovered, setHovered] = useState(false);
  const [focusWithin, setFocusWithin] = useState(false);
  const reducedMotion = usePrefersReducedMotion();

  const updateEdges = useCallback(() => {
    const track = trackRef.current;
    if (!track) return;
    setAtStart(track.scrollLeft <= 2);
    setAtEnd(track.scrollLeft + track.clientWidth >= track.scrollWidth - 2);
  }, []);

  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;
    updateEdges();
    track.addEventListener("scroll", updateEdges, { passive: true });
    const observer = new ResizeObserver(updateEdges);
    observer.observe(track);
    return () => {
      track.removeEventListener("scroll", updateEdges);
      observer.disconnect();
    };
  }, [updateEdges, teachers.length]);

  const step = useCallback(
    (direction: 1 | -1) => {
      const track = trackRef.current;
      const slide = track?.querySelector<HTMLElement>("[data-slide]");
      if (!track || !slide) return;
      const gap = parseFloat(getComputedStyle(track).columnGap) || 0;
      track.scrollBy({ left: direction * (slide.offsetWidth + gap), behavior: reducedMotion ? "auto" : "smooth" });
    },
    [reducedMotion]
  );

  const overflowing = !(atStart && atEnd);
  const rotating = autoRotate && !reducedMotion && overflowing && !paused && !hovered && !focusWithin;

  useEffect(() => {
    if (!rotating) return;
    const timer = window.setInterval(() => {
      const track = trackRef.current;
      if (!track) return;
      if (track.scrollLeft + track.clientWidth >= track.scrollWidth - 2) {
        track.scrollTo({ left: 0, behavior: "smooth" });
      } else {
        step(1);
      }
    }, AUTO_ROTATE_MS);
    return () => window.clearInterval(timer);
  }, [rotating, step]);

  if (query.isPending) {
    return (
      <section className="mx-auto w-full max-w-7xl px-4 py-14 sm:px-6 lg:px-8" aria-busy="true" aria-label="Loading teachers">
        <Skeleton className="h-8 w-56" />
        <div className="mt-8 grid grid-cols-1 gap-5 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4">
          {[0, 1, 2, 3].map((index) => (
            <Skeleton key={index} className={cn("aspect-[4/5] rounded-xl", ["", "hidden sm:block", "hidden md:block", "hidden lg:block"][index])} />
          ))}
        </div>
      </section>
    );
  }

  if (query.isError) {
    return (
      <section className="mx-auto w-full max-w-7xl px-4 py-14 sm:px-6 lg:px-8" aria-label="Teachers">
        <ErrorState message="Teacher profiles are temporarily unavailable." onRetry={() => void query.refetch()} />
      </section>
    );
  }

  if (teachers.length === 0) return null;

  const showAutoControl = autoRotate && !reducedMotion && overflowing;

  return (
    <section id="teachers" className="mx-auto w-full max-w-7xl scroll-mt-24 px-4 py-14 sm:px-6 lg:px-8" aria-labelledby="home-teachers-heading" data-testid="teacher-section">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-widest text-primary">Our teachers</p>
          <h2 id="home-teachers-heading" className="mt-2 text-2xl font-bold tracking-tight sm:text-3xl">
            Meet our teachers
          </h2>
          <p className="mt-2 text-muted-foreground">Experienced educators to guide you toward your goals.</p>
        </div>
        <div className="flex items-center gap-2">
          <Link href="/teachers" className="mr-2 inline-flex items-center gap-1 text-sm font-semibold text-primary hover:underline">
            View all <ArrowRight className="size-4" aria-hidden="true" />
          </Link>
          {showAutoControl ? (
            <Button type="button" variant="outline" size="icon" onClick={() => setPaused((value) => !value)} aria-label={paused ? "Resume teacher rotation" : "Pause teacher rotation"}>
              {paused ? <Play aria-hidden="true" /> : <Pause aria-hidden="true" />}
            </Button>
          ) : null}
          {teachers.length > 1 && overflowing ? (
            <>
              <Button type="button" variant="outline" size="icon" onClick={() => step(-1)} disabled={atStart} aria-label="Previous teachers">
                <ChevronLeft aria-hidden="true" />
              </Button>
              <Button type="button" variant="outline" size="icon" onClick={() => step(1)} disabled={atEnd} aria-label="Next teachers">
                <ChevronRight aria-hidden="true" />
              </Button>
            </>
          ) : null}
        </div>
      </div>
      <div
        ref={trackRef}
        role="region"
        aria-roledescription="carousel"
        aria-label="Teachers"
        data-testid="teacher-track"
        data-rotating={rotating}
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        onFocus={() => setFocusWithin(true)}
        onBlur={(event) => {
          if (!event.currentTarget.contains(event.relatedTarget as Node | null)) setFocusWithin(false);
        }}
        className="mt-8 flex snap-x snap-mandatory gap-5 overflow-x-auto overscroll-x-contain pb-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
      >
        {teachers.map((teacher, index) => (
          <div
            key={teacher.id}
            data-slide
            role="group"
            aria-roledescription="slide"
            aria-label={`${index + 1} of ${teachers.length}`}
            className={cn(
              "relative shrink-0 snap-start",
              teachers.length === 1
                ? "w-full max-w-xs"
                : "basis-full sm:basis-[calc((100%-1.25rem)/2)] md:basis-[calc((100%-2.5rem)/3)] lg:basis-[calc((100%-3.75rem)/4)]"
            )}
          >
            <TeacherCard teacher={teacher} />
          </div>
        ))}
      </div>
    </section>
  );
}
