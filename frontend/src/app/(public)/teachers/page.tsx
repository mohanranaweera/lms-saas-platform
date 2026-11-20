"use client";

import { useState } from "react";
import Link from "next/link";
import { ArrowRight, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { usePublicTeachers, PUBLIC_TEACHERS_PAGE_SIZE } from "@/lib/api/public-teachers";
import { isApiClientError } from "@/lib/api/error";

function TeacherAvatar({ name, imageUrl, large = false }: { name: string; imageUrl: string | null; large?: boolean }) {
  const [failed, setFailed] = useState(false);
  const size = large ? "size-28" : "size-20";
  if (imageUrl && !failed) {
    return <img src={imageUrl} alt={`${name} profile`} className={`${size} rounded-full object-cover ring-4 ring-primary/10`} onError={() => setFailed(true)} />;
  }
  return <span className={`flex ${size} items-center justify-center rounded-full bg-primary/10 text-primary`}><UserRound className={large ? "size-12" : "size-8"} aria-hidden="true" /></span>;
}

export default function PublicTeachersPage() {
  const [page, setPage] = useState(0);
  const query = usePublicTeachers(page);

  if (query.status === "pending") return <LoadingState label="Loading teachers…" />;
  if (query.status === "error") {
    return <div className="mx-auto flex w-full max-w-6xl flex-1 px-4 py-10 sm:px-6"><ErrorState message={isApiClientError(query.error) ? query.error.message : "Couldn't load teachers."} onRetry={() => query.refetch()} /></div>;
  }

  const teacherPage = query.data;
  return (
    <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-10 px-4 py-10 sm:px-6 lg:py-16">
      <header className="mx-auto max-w-2xl text-center">
        <p className="text-sm font-semibold uppercase tracking-[0.2em] text-primary">Meet our teachers</p>
        <h1 className="mt-3 text-3xl font-semibold tracking-tight text-foreground sm:text-5xl">Learn from experienced educators</h1>
        <p className="mt-4 text-base leading-7 text-muted-foreground">Explore our teaching team and discover courses designed to help you reach your goals.</p>
      </header>

      {teacherPage.content.length === 0 ? (
        <EmptyState title="No public teacher profiles yet" description="Teacher profiles will appear here when this institute publishes them." />
      ) : (
        <>
          <ul className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
            {teacherPage.content.map((teacher) => (
              <li key={teacher.id}>
                <Link href={`/teachers/${teacher.id}`} className="group flex h-full flex-col items-center rounded-2xl border border-border bg-card p-6 text-center transition-all hover:-translate-y-1 hover:border-primary/40 hover:shadow-lg">
                  <TeacherAvatar name={teacher.name} imageUrl={teacher.profileImageUrl} />
                  <h2 className="mt-5 text-lg font-semibold text-foreground">{teacher.name}</h2>
                  {teacher.qualifications ? <p className="mt-1 line-clamp-2 text-sm text-primary">{teacher.qualifications}</p> : null}
                  {teacher.description ? <p className="mt-3 line-clamp-3 text-sm leading-6 text-muted-foreground">{teacher.description}</p> : null}
                  <span className="mt-auto flex items-center gap-1 pt-5 text-sm font-medium text-primary">View profile <ArrowRight className="size-4 transition-transform group-hover:translate-x-1" aria-hidden="true" /></span>
                </Link>
              </li>
            ))}
          </ul>
          {teacherPage.totalPages > 1 ? <nav aria-label="Teacher profile pagination" className="flex items-center justify-between gap-3"><Button type="button" variant="outline" disabled={page <= 0} onClick={() => setPage((current) => Math.max(current - 1, 0))}>Previous</Button><span className="text-sm text-muted-foreground" aria-live="polite">Page {teacherPage.page + 1} of {teacherPage.totalPages}</span><Button type="button" variant="outline" disabled={page + 1 >= teacherPage.totalPages} onClick={() => setPage((current) => current + 1)}>Next</Button></nav> : null}
        </>
      )}
    </div>
  );
}
