"use client";

import { useParams } from "next/navigation";
import { LeaderboardRanksPage } from "@/components/exams/leaderboards/leaderboard-ranks-page";

/** One leaderboard (Slice E, D7): included exams and ranks. */
export default function TeacherExamLeaderboardPage() {
  const params = useParams<{ leaderboardId: string }>();
  return <LeaderboardRanksPage portal="teacher" leaderboardId={params.leaderboardId} />;
}
