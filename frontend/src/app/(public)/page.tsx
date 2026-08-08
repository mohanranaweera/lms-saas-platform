import Link from "next/link";
import { Button } from "@/components/ui/button";

export default function HomePage() {
  return (
    <section className="flex flex-1 flex-col items-center justify-center gap-6 px-6 py-24 text-center">
      <h1 className="max-w-2xl text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
        Run your institute on one multi-tenant learning platform
      </h1>
      <p className="max-w-xl text-base text-muted-foreground">
        Courses, enrollments, payments, and exams for schools, tutors, and training
        institutes — all in one place.
      </p>
      <div className="flex flex-col gap-3 sm:flex-row">
        <Button render={<Link href="/register-institute" />} size="lg">
          Get started
        </Button>
        <Button render={<Link href="/login" />} variant="outline" size="lg">
          Sign in
        </Button>
      </div>
    </section>
  );
}
