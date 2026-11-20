"use client";

import { LeaderboardListPage } from "@/components/exams/leaderboards/leaderboard-list-page";

/** Exam leaderboards (Slice E, D7): list, add, edit, delete. */
export default function TenantAdminExamLeaderboardsPage() {
  return <LeaderboardListPage portal="tenant-admin" />;
}
