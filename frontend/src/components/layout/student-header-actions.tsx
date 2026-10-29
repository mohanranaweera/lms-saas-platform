"use client";

import Link from "next/link";
import { ChevronDown, UserRound } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuLinkItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { StudentNotificationQuickView } from "@/components/notifications/student-notification-quick-view";
import { LogoutControl } from "@/components/auth/logout-control";
import { useAuth } from "@/lib/auth/auth-context";
import { useOwnStudentProfile } from "@/lib/api/students";

export function StudentHeaderActions({ children }: { children: React.ReactNode }) {
  const { session } = useAuth();
  const profileQuery = useOwnStudentProfile();
  const displayName = profileQuery.data?.name ?? "Student account";

  return (
    <div className="flex items-center gap-2">
      {children}
      <StudentNotificationQuickView />
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button type="button" variant="ghost" size="sm" className="max-w-48" aria-label="Open account menu" />}>
          <UserRound aria-hidden="true" />
          <span className="truncate">{displayName}</span>
          <ChevronDown aria-hidden="true" />
        </DropdownMenuTrigger>
        <DropdownMenuContent className="min-w-48">
          <DropdownMenuLinkItem render={<Link href="/student/profile" />}>Profile</DropdownMenuLinkItem>
          <DropdownMenuSeparator />
          <div className="px-1"><LogoutControl kind="tenant" portalLabel="Student Portal" /></div>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}
