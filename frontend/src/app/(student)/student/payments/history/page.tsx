import { redirect } from "next/navigation";

export default function PaymentHistoryPage() {
  redirect("/student/profile?tab=payments");
}
