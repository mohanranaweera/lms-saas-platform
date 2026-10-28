"use client";

import { useEffect, useState } from "react";
import { useParams } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/button";
import { CourseCover, PriceBadge } from "@/components/courses/student/course-visuals";
import { UserRound } from "lucide-react";
import { EmptyState } from "@/components/states/empty-state";
import { ErrorState } from "@/components/states/error-state";
import { LoadingState } from "@/components/states/loading-state";
import { StudentCourseDetailView } from "@/components/courses/student/student-course-detail";
import { PaymentComposerProvider } from "@/components/payments/composer/payment-composer-provider";
import { EnrollConfirmDialog, enrollErrorMessage } from "@/components/courses/student/enroll-confirm-dialog";
import { usePublicCourse } from "@/lib/api/public-courses";
import { useAuth } from "@/lib/auth/auth-context";
import { useSelfEnroll, useStudentCourse } from "@/lib/api/klass-student";
import { isApiClientError } from "@/lib/api/error";

export default function PublicCourseDetailPage() {
  const params = useParams<{ slug: string }>();
  const publicQuery = usePublicCourse(params.slug);
  const { session, ensureAccessToken } = useAuth();
  const [authReady, setAuthReady] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [enrollError, setEnrollError] = useState<string | null>(null);
  const enrollment = useSelfEnroll();
  const isStudent = session?.kind === "tenant" && session.role === "STUDENT";
  const studentCourseId = isStudent && publicQuery.status === "success" ? publicQuery.data.id : "";
  const studentQuery = useStudentCourse(studentCourseId);

  useEffect(() => {
    let cancelled = false;
    ensureAccessToken("tenant").catch(() => {}).finally(() => {
      if (!cancelled) setAuthReady(true);
    });
    return () => { cancelled = true; };
  }, [ensureAccessToken]);

  if (publicQuery.status === "pending" || !authReady) return <LoadingState label="Loading course…" />;
  if (publicQuery.status === "error") {
    const notFound = isApiClientError(publicQuery.error) && publicQuery.error.status === 404;
    return <div className="mx-auto flex w-full max-w-6xl flex-1 px-4 py-10 sm:px-6">{notFound ? <EmptyState title="Course not found" description="This course may be unpublished or the link may be incorrect." action={{ label: "Back to courses", onClick: () => window.location.assign("/courses") }} /> : <ErrorState message={isApiClientError(publicQuery.error) ? publicQuery.error.message : "Couldn't load course."} onRetry={() => publicQuery.refetch()} />}</div>;
  }

  const publicCourse = publicQuery.data;

  if (!isStudent) {
    return (
      <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-8 sm:px-6">
        <Link href="/courses" className="text-sm text-muted-foreground hover:underline">← Back to courses</Link>
        <section className="overflow-hidden rounded-xl border border-border bg-card shadow-sm">
          <div className="flex flex-col gap-5 p-4 sm:p-6 md:flex-row md:items-center md:gap-8">
            <CourseCover src={publicCourse.coverImageUrl} title={publicCourse.name} className="rounded-lg md:w-2/5 md:max-w-sm" />
            <div className="flex min-w-0 flex-col gap-2">
              {publicCourse.resolvedAmount !== null ? <PriceBadge priceDisplay={{ amount: publicCourse.resolvedAmount, currency: publicCourse.currency, period: publicCourse.pricingModel === "MONTHLY" ? "MONTH" : null }} pricingModel={publicCourse.pricingModel} /> : null}
              <h1 className="break-words text-2xl font-bold text-foreground sm:text-3xl">{publicCourse.name}</h1>
              {publicCourse.teacherName ? <p className="flex items-center gap-1.5 text-sm text-muted-foreground"><UserRound className="size-4" aria-hidden="true" />{publicCourse.teacherName}</p> : null}
              <Button className="mt-2 w-fit" render={<Link href="/login" />}>Register now</Button>
            </div>
          </div>
        </section>
        <p className="rounded-xl border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-800">Register or sign in as a student to enroll and open this course.</p>
      </div>
    );
  }

  if (studentQuery.status === "pending") return <LoadingState label="Loading course…" />;
  if (studentQuery.status === "error") {
    const unavailable = isApiClientError(studentQuery.error) && (studentQuery.error.status === 403 || studentQuery.error.status === 404);
    if (!unavailable) return <ErrorState message="We couldn't load this course." code={isApiClientError(studentQuery.error) ? studentQuery.error.code : undefined} onRetry={() => studentQuery.refetch()} />;
    const canEnroll = publicCourse.registrationEnabled;
    return <div className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-8 sm:px-6"><div className="rounded-xl border border-border bg-card p-6 shadow-sm"><h1 className="text-2xl font-bold text-foreground">{publicCourse.name}</h1><p className="mt-2 text-sm text-muted-foreground">You are not enrolled in this course.</p>{canEnroll ? <Button className="mt-4" disabled={enrollment.isPending} onClick={() => setConfirmOpen(true)}>{enrollment.isPending ? "Enrolling…" : "Enroll now"}</Button> : <p className="mt-4 text-sm text-muted-foreground">Enrollment is not open right now.</p>}{enrollError ? <p role="alert" className="mt-3 text-sm text-destructive">{enrollError}</p> : null}</div><EnrollConfirmDialog courseTitle={publicCourse.name} open={confirmOpen} onOpenChange={setConfirmOpen} onConfirm={async () => { setConfirmOpen(false); setEnrollError(null); try { await enrollment.mutateAsync(publicCourse.id); await studentQuery.refetch(); } catch (error) { setEnrollError(enrollErrorMessage(error)); } }} /></div>;
  }

  return <PaymentComposerProvider><StudentCourseDetailView courseId={publicCourse.id} /></PaymentComposerProvider>;
}
