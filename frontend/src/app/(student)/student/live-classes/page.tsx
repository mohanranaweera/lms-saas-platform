import { redirect } from "next/navigation";

/**
 * Students see live classes inside each course's content page
 * (`student/courses/[courseId]`), not on a separate page. This route stays
 * only so old bookmarks/links land on "My courses" instead of a 404.
 */
export default function StudentLiveClassesRedirect() {
  redirect("/student/courses");
}
