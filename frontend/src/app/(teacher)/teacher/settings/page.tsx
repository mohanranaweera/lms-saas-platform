import Link from "next/link";
import { Card, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * Teacher Settings index (MEDIA-INT-01). A pure navigation index - no data
 * fetch, so no loading/error state of its own; each destination owns its
 * request and its own QueryStateBoundary.
 */
export default function TeacherSettingsPage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Settings</h1>
        <p className="text-sm text-muted-foreground">Your own teaching accounts and preferences.</p>
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Link href="/teacher/settings/integrations" className="block">
          <Card className="h-full transition-colors hover:bg-muted/40">
            <CardHeader>
              <CardTitle>Integrations</CardTitle>
              <CardDescription>Connect your own Zoom, YouTube and Vimeo accounts.</CardDescription>
            </CardHeader>
          </Card>
        </Link>
      </div>
    </div>
  );
}
