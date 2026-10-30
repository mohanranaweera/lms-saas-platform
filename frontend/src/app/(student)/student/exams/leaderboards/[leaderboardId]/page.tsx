"use client";

import { useParams } from "next/navigation";
import { StudentLeaderboardRanks } from "@/components/exams/leaderboards/student-leaderboards";

/** Student view of one published leaderboard; the student's own row is highlighted. */
export default function StudentLeaderboardPage() {
  const params = useParams<{ leaderboardId: string }>();
  return <StudentLeaderboardRanks leaderboardId={params.leaderboardId} />;
}
