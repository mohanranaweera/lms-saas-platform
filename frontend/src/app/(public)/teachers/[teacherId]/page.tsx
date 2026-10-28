"use client";

import { useParams } from "next/navigation";
import { useState } from "react";
import Link from "next/link";
import { ArrowLeft, BookOpen, UserRound } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { usePublicTeacher } from "@/lib/api/public-teachers";
import { isApiClientError } from "@/lib/api/error";

function CourseCover({ course }: { course: { name: string; coverImageUrl: string | null } }) {
  const [failed, setFailed] = useState(false);
  if (course.coverImageUrl && !failed) {
    return <img src={course.coverImageUrl} alt={`${course.name} cover`} className="h-40 w-full object-cover" onError={() => setFailed(true)} />;
  }
  return <div className="flex h-40 items-center justify-center bg-primary/10 text-primary"><BookOpen className="size-12" aria-hidden="true" /></div>;
}

export default function PublicTeacherDetailPage() {
  const params = useParams<{ teacherId: string }>();
  const query = usePublicTeacher(params.teacherId);

  if (query.status === "pending") return <LoadingState label="Loading teacher profile…" />;
  if (query.status === "error") {
    const notFound = isApiClientError(query.error) && query.error.status === 404;
    return <div className="mx-auto flex w-full max-w-4xl flex-1 flex-col gap-5 px-4 py-10 sm:px-6">{notFound ? <EmptyState title="Teacher profile not found" description="This profile may be unpublished or the link may be incorrect." action={{ label: "Back to teachers", onClick: () => window.location.assign("/teachers") }} /> : <ErrorState message={isApiClientError(query.error) ? query.error.message : "Couldn't load teacher profile."} onRetry={() => query.refetch()} />}</div>;
  }

  const teacher = query.data;
  return (
    <div className="mx-auto flex w-full max-w-5xl flex-1 flex-col gap-8 px-4 py-10 sm:px-6 lg:py-16">
      <Link href="/teachers" className="inline-flex items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground"><ArrowLeft className="size-4" aria-hidden="true" />All teachers</Link>
      <section className="flex flex-col items-center gap-6 rounded-3xl border border-border bg-card p-6 text-center sm:p-10">
        {teacher.profileImageUrl ? <img src={teacher.profileImageUrl} alt={`${teacher.name} profile`} className="size-32 rounded-full object-cover ring-4 ring-primary/10" /> : <span className="flex size-32 items-center justify-center rounded-full bg-primary/10 text-primary"><UserRound className="size-14" aria-hidden="true" /></span>}
        <div className="max-w-2xl"><h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">{teacher.name}</h1>{teacher.qualifications ? <p className="mt-2 text-base font-medium text-primary">{teacher.qualifications}</p> : null}{teacher.description ? <p className="mt-5 whitespace-pre-line text-sm leading-7 text-muted-foreground">{teacher.description}</p> : null}</div>
      </section>
      <section className="flex flex-col gap-4">
        <div><h2 className="text-2xl font-semibold text-foreground">Courses by {teacher.name}</h2><p className="mt-1 text-sm text-muted-foreground">Explore publicly available courses from this teacher.</p></div>
        {teacher.courses.length === 0 ? <EmptyState icon={<BookOpen className="size-7" aria-hidden="true" />} title="No public courses yet" description="This teacher has not published any courses yet." /> : <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">{teacher.courses.map((course) => <li key={course.id}><Link href={`/courses/${course.slug}`} className="flex h-full flex-col overflow-hidden rounded-xl border border-border bg-card transition-colors hover:border-primary/40 hover:bg-muted/40"><CourseCover course={course} /><div className="flex flex-1 flex-col gap-3 p-5"><span className="text-xs font-medium uppercase tracking-wide text-primary">{course.category}</span><h3 className="text-base font-semibold text-foreground">{course.name}</h3><span className="mt-auto text-sm font-medium text-primary">View course</span></div></Link></li>)}</ul>}
      </section>
    </div>
  );
}
