"use client";

import { TuteDispatchPage } from "@/components/tutes/tute-dispatch-page";
import { TEACHER_TUTE_ROUTES } from "@/components/tutes/tute-routes";

/** TUTE-01 Tute Dispatch queue. Access is decided by the backend (TUTE_DISPATCH_VIEW). */
export default function TeacherTuteDispatchRoute() {
  return <TuteDispatchPage routes={TEACHER_TUTE_ROUTES} />;
}
