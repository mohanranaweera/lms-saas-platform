import { redirect } from "next/navigation";
import { paymentsHubHref } from "@/lib/payments/hub-routes";

type SearchParams = Promise<Record<string, string | string[] | undefined>>;

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

/** PAY-HUB-01: Student Payments is now the Payments hub "Free Cards" tab (student view). Old links keep working. */
export default async function Page({ searchParams }: { searchParams: SearchParams }) {
  const params = await searchParams;
  redirect(
    paymentsHubHref("free-cards", {
      view: "student",
      studentId: first(params.studentId),
      courseId: first(params.courseId),
    })
  );
}
