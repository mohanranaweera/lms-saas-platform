import { redirect } from "next/navigation";

/**
 * Former "Browse Courses" page. Its Enroll link sent students to checkout,
 * which ADR-028 retires for enrollment (enrollment is free and never goes
 * through an order). The route stays reachable for old links/bookmarks and
 * lands on the Available Courses view, where Enroll is the free action.
 */
export default function StudentBrowseCoursesPage() {
  redirect("/student/courses?view=available");
}
