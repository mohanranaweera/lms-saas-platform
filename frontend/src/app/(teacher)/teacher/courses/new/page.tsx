import { CourseCreateForm } from "@/components/courses/course-create-form";

export default function NewCoursePage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Create a course</h1>
        <p className="text-sm text-muted-foreground">
          You&apos;ll own this course as its teacher. Work through each step, then create it as
          Draft, Private, or Public.
        </p>
      </div>
      <CourseCreateForm />
    </div>
  );
}
