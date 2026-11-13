package com.lms.identityaccessservice.api;

import static com.lms.identityaccessservice.api.CapabilitySpec.locked;
import static com.lms.identityaccessservice.api.CapabilitySpec.lockedFor;
import static com.lms.identityaccessservice.api.CapabilitySpec.om;
import static com.lms.identityaccessservice.api.CapabilitySpec.omFor;
import static com.lms.identityaccessservice.api.CapabilitySpec.omt;
import static com.lms.identityaccessservice.api.CapabilitySpec.omtTeacherOff;
import static com.lms.identityaccessservice.api.CapabilitySpec.omtTeacherOffFor;
import static com.lms.identityaccessservice.api.CapabilitySpec.omtTeacherOffNoAuditor;
import static com.lms.identityaccessservice.api.CapabilitySpec.p;
import static com.lms.identityaccessservice.api.CapabilitySpec.unavailable;
import static com.lms.identityaccessservice.api.DomainArea.ACCESS_EXPIRY;
import static com.lms.identityaccessservice.api.DomainArea.ATTENDANCE;
import static com.lms.identityaccessservice.api.DomainArea.AUDIT_LOG;
import static com.lms.identityaccessservice.api.DomainArea.COMMUNICATIONS;
import static com.lms.identityaccessservice.api.DomainArea.COURSES;
import static com.lms.identityaccessservice.api.DomainArea.DEVICES;
import static com.lms.identityaccessservice.api.DomainArea.EXAMS;
import static com.lms.identityaccessservice.api.DomainArea.FINANCE_EXPENSES;
import static com.lms.identityaccessservice.api.DomainArea.FREE_CARDS;
import static com.lms.identityaccessservice.api.DomainArea.INTEGRATIONS;
import static com.lms.identityaccessservice.api.DomainArea.MATERIALS;
import static com.lms.identityaccessservice.api.DomainArea.PAYMENTS_SLIPS;
import static com.lms.identityaccessservice.api.DomainArea.PAYMENT_GATEWAY;
import static com.lms.identityaccessservice.api.DomainArea.STAFF_AND_ROLES;
import static com.lms.identityaccessservice.api.DomainArea.STUDENTS;
import static com.lms.identityaccessservice.api.DomainArea.STUDENT_IMPERSONATION;
import static com.lms.identityaccessservice.api.DomainArea.TEACHERS;
import static com.lms.identityaccessservice.api.DomainArea.TRANSLATIONS;
import static com.lms.identityaccessservice.api.DomainArea.TUTES;
import static com.lms.identityaccessservice.api.PermissionAction.APPROVE;
import static com.lms.identityaccessservice.api.PermissionAction.CREATE_EDIT;
import static com.lms.identityaccessservice.api.PermissionAction.DELETE;
import static com.lms.identityaccessservice.api.PermissionAction.VIEW;

import java.util.Optional;
import java.util.Set;

/**
 * STAFF-MGMT-02 / ADR-034 capability catalog: one constant per Klass permission-matrix row
 * (spec §3). Each capability carries
 * <ul>
 * <li>{@link #group()} / {@link #label()} - Klass-style display;
 * <li>{@link #primary()} - the endpoint's existing static gate: a Tenant Admin / legacy staff
 * sub-role holds the capability exactly when it holds this pair, so static-role access is
 * unchanged;
 * <li>{@link #implied()} - every pair the endpoint's code path checks (always includes the
 * primary); elevated for one request only when an Operator/Manager holds the tenant grant;
 * <li>{@link #configurableFor()} and {@link #availability()} (+ {@link #reason()}).
 * </ul>
 * Seeded staff sub-roles (STAFF-MGMT-03 §6.1) are configurable for every Operator/Manager
 * capability (Read-only Auditor: view-only capabilities only); their default is the primary gate.
 * Invariant (unit-tested): every ENFORCED capability's implied pairs are held by Tenant Admin,
 * so a configurable role can never exceed Tenant Admin. Keys ({@link #key()}) are persisted in
 * {@code tenant_role_capability_grant.capability_key}; never rename a constant.
 */
public enum Capability {

	// ---------------------------------------------------------------- Finance
	// GET /finance/reports/periods: calendar-month income/expense buckets.
	FINANCE_DASHBOARD_ANNUAL_STATS(CapabilityGroup.FINANCE, "View Annual Stats", om(p(FINANCE_EXPENSES, VIEW))),
	FINANCE_VIEW_INCOME_SUMMARY(CapabilityGroup.FINANCE, "View Income Summary", om(p(FINANCE_EXPENSES, VIEW))),
	// GET /ledger/outstanding: orders still awaiting payment.
	FINANCE_DASHBOARD_PENDING_PAYMENTS(CapabilityGroup.FINANCE, "View Pending Payments",
			om(p(PAYMENTS_SLIPS, VIEW))),
	FINANCE_DASHBOARD_RECENT_PAYMENTS(CapabilityGroup.FINANCE, "View Recent Payments",
			unavailable("Part of the payments dashboard (View Payments); no separate operation")),
	FINANCE_DASHBOARD_REGISTRATION_PAYMENTS(CapabilityGroup.FINANCE, "View Registration Payments",
			unavailable("Part of the payments dashboard (View Payments); no separate operation")),
	FINANCE_VIEW_EXPENSES(CapabilityGroup.FINANCE, "View Expenses", om(p(FINANCE_EXPENSES, VIEW))),
	FINANCE_DASHBOARD_TRANSACTIONS(CapabilityGroup.FINANCE, "View Transactions",
			unavailable("Part of the payments dashboard / order list (View Payments); no separate operation")),
	// Expenses are append-only; void is a state change (never a row delete).
	FINANCE_ADD_EXPENSE(CapabilityGroup.FINANCE, "Add Expense", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	FINANCE_VOID_EXPENSE(CapabilityGroup.FINANCE, "Void Expense", om(p(FINANCE_EXPENSES, DELETE))),
	FINANCE_DELETE_CATEGORY(CapabilityGroup.FINANCE, "Delete Category", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	FINANCE_ACCOUNT_CHANGE_CONFIGURATION(CapabilityGroup.FINANCE, "Change Account Configuration",
			locked("Payment gateway credentials are Tenant Admin only (ADR-017)", p(PAYMENT_GATEWAY, CREATE_EDIT))),
	// GET /payment-gateway-accounts: status only; the merchant secret is never returned (ADR-017).
	FINANCE_ACCOUNT_VIEW_CONFIGURATION(CapabilityGroup.FINANCE, "View Account Configuration",
			om(p(PAYMENT_GATEWAY, VIEW))),
	FINANCE_VIEW_CATEGORY(CapabilityGroup.FINANCE, "View Categories", om(p(FINANCE_EXPENSES, VIEW))),
	FINANCE_ADD_CATEGORY(CapabilityGroup.FINANCE, "Add Category", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	FINANCE_ACCOUNT_UPDATE(CapabilityGroup.FINANCE, "Update Account",
			locked("Payment gateway credentials are Tenant Admin only (ADR-017)", p(PAYMENT_GATEWAY, CREATE_EDIT))),
	FINANCE_ACCOUNT_DELETE(CapabilityGroup.FINANCE, "Delete Account",
			locked("Payment gateway credentials are Tenant Admin only (ADR-017)", p(PAYMENT_GATEWAY, CREATE_EDIT))),

	// ---------------------------------------------------------------- Teachers
	TEACHERS_VIEW(CapabilityGroup.TEACHERS, "View Teachers", om(p(TEACHERS, VIEW))),
	TEACHERS_ADD(CapabilityGroup.TEACHERS, "Add Teachers", om(p(TEACHERS, CREATE_EDIT))),
	TEACHERS_EDIT(CapabilityGroup.TEACHERS, "Edit Teachers", om(p(TEACHERS, CREATE_EDIT))),
	TEACHERS_VIEW_WALLET(CapabilityGroup.TEACHERS, "View Wallet", om(p(FINANCE_EXPENSES, VIEW))),
	// Kept for the Klass "Disable Wallet" row (key never renamed). The real wallet actions are the
	// FINANCE_*_SETTLEMENT(S) / FINANCE_EDIT_SHARE_RATES rows below (STAFF-MGMT-03 §6.2).
	TEACHERS_WALLET_ACTIONS(CapabilityGroup.TEACHERS, "Disable Wallet",
			unavailable("There is no wallet disable operation; settlement actions are separate rows")),
	// Settlement runs are idempotent (a period is never settled twice) and append-only:
	// adjustments are new rows referencing the original, which is never changed.
	FINANCE_CALCULATE_SETTLEMENTS(CapabilityGroup.TEACHERS, "Calculate Settlements",
			om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	FINANCE_MARK_SETTLEMENT_PAID(CapabilityGroup.TEACHERS, "Mark Settlement Paid",
			om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	FINANCE_ADJUST_SETTLEMENT(CapabilityGroup.TEACHERS, "Adjust Settlement", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	FINANCE_EDIT_SHARE_RATES(CapabilityGroup.TEACHERS, "Edit Share Rates", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	// TEACHER-WALLET-01 / ADR-036: Show Wallet actions, one row each - none implies another.
	// Same static gate as the settlement actions, so no default access is broadened.
	TEACHERS_WALLET_CREATE(CapabilityGroup.TEACHERS, "Create Wallet", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	TEACHERS_WALLET_PAY_IN(CapabilityGroup.TEACHERS, "Record Wallet Pay In", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	TEACHERS_WALLET_PAY_OUT(CapabilityGroup.TEACHERS, "Record Wallet Pay Out",
			om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	TEACHERS_WALLET_ADJUST(CapabilityGroup.TEACHERS, "Adjust Wallet Balance", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	TEACHERS_DELETE(CapabilityGroup.TEACHERS, "Delete Teacher",
			unavailable("Teachers are suspended, never deleted")),

	// ---------------------------------------------------------------- Courses
	COURSES_VIEW(CapabilityGroup.COURSES, "View Courses", omt(p(COURSES, VIEW))),
	COURSES_VIEW_STUDENTS(CapabilityGroup.COURSES, "View Course Students", omt(p(COURSES, VIEW))),
	// Course roster paid/Free Card status (owner, 2026-11-10; ADR-028 refinement). Static holders
	// mirror the Unpaid Students report (FINANCE_EXPENSES/VIEW); a Teacher holds it only once the
	// tenant grants it. Checked by CourseRosterService - never elevates the static finance gate.
	COURSES_VIEW_PAYMENT_STATUS(CapabilityGroup.COURSES, "View Course Payment Status",
			omtTeacherOff(p(FINANCE_EXPENSES, VIEW))),
	// Checked by CourseService when GET /courses asks for archived courses (not for Teachers).
	COURSES_VIEW_ARCHIVED(CapabilityGroup.COURSES, "View Archived Courses", om(p(COURSES, VIEW))),
	COURSES_ADD(CapabilityGroup.COURSES, "Add Course", om(p(COURSES, CREATE_EDIT), p(COURSES, VIEW))),
	COURSES_EDIT(CapabilityGroup.COURSES, "Edit Course", om(p(COURSES, CREATE_EDIT), p(COURSES, VIEW))),
	COURSES_ARCHIVE(CapabilityGroup.COURSES, "Archive Course", om(p(COURSES, CREATE_EDIT))),
	COURSES_TOGGLE_REGISTRATION(CapabilityGroup.COURSES, "Toggle Course Registration Button",
			om(p(COURSES, CREATE_EDIT))),
	COURSES_DELETE(CapabilityGroup.COURSES, "Delete Course", om(p(COURSES, DELETE))),
	// POST /students/{id}/enroll checks STUDENTS/CREATE_EDIT (@PreAuthorize) and then
	// PAYMENTS_SLIPS/APPROVE (it records a staff-granted CONFIRMED payment + ledger entry and the
	// paid entitlement). Only Tenant Admin holds both statically, so it is the only default holder.
	COURSES_ENROLL_STUDENTS(CapabilityGroup.COURSES, "Enroll Students",
			omFor(new String[] { "TENANT_ADMIN" }, p(STUDENTS, CREATE_EDIT), p(PAYMENTS_SLIPS, APPROVE))),
	COURSES_ADD_TIMETABLE(CapabilityGroup.COURSES, "Add Timetable",
			locked("Same operation as Timetable - Edit Timetable", p(COURSES, CREATE_EDIT))),
	COURSES_REVIEWS_VIEW(CapabilityGroup.COURSES, "View Reviews", unavailable("No course review feature")),
	COURSES_REVIEWS_APPROVE_REJECT(CapabilityGroup.COURSES, "Approve Reject Reviews",
			unavailable("No course review feature")),
	COURSES_REVIEWS_DELETE(CapabilityGroup.COURSES, "Delete Reviews", unavailable("No course review feature")),
	COURSES_REVIEWS_TOGGLE(CapabilityGroup.COURSES, "Toggle Course Reviews", unavailable("No course review feature")),

	// ---------------------------------------------------------------- Modules
	MODULES_VIEW(CapabilityGroup.MODULES, "View Modules", omt(p(COURSES, VIEW))),
	MODULES_ADD(CapabilityGroup.MODULES, "Add Module", omt(p(COURSES, CREATE_EDIT))),
	MODULES_DELETE(CapabilityGroup.MODULES, "Delete Module", omt(p(COURSES, CREATE_EDIT))),
	// Module question bank (ExamAccessGuard: Teachers stay ownership-scoped; not teacher-configurable).
	MODULES_QUESTIONS_VIEW(CapabilityGroup.MODULES, "View Questions", om(p(EXAMS, VIEW))),
	MODULES_QUESTIONS_ADD(CapabilityGroup.MODULES, "Add Questions", om(p(EXAMS, CREATE_EDIT))),
	MODULES_QUESTIONS_EDIT(CapabilityGroup.MODULES, "Edit Questions", om(p(EXAMS, CREATE_EDIT))),
	MODULES_QUESTIONS_DELETE(CapabilityGroup.MODULES, "Delete Questions",
			unavailable("Deleting a question needs exam DELETE, which no staff role holds (owning teacher only)")),
	MODULES_IMPORT(CapabilityGroup.MODULES, "Import Modules", unavailable("No module import operation")),
	MODULES_QUESTIONS_IMPORT(CapabilityGroup.MODULES, "Import Questions",
			om(p(EXAMS, CREATE_EDIT), p(EXAMS, VIEW))),

	// ---------------------------------------------------------------- Students
	STUDENTS_VIEW(CapabilityGroup.STUDENTS, "View Students", om(p(STUDENTS, VIEW))),
	STUDENTS_ADD(CapabilityGroup.STUDENTS, "Add Student", om(p(STUDENTS, CREATE_EDIT))),
	STUDENTS_VIEW_PROFILE(CapabilityGroup.STUDENTS, "View Student Profile", om(p(STUDENTS, VIEW))),
	STUDENTS_VIEW_DEVICES(CapabilityGroup.STUDENTS, "View Student Devices", om(p(DEVICES, VIEW))),
	STUDENTS_DELETE_DEVICE(CapabilityGroup.STUDENTS, "Delete Student Device", om(p(DEVICES, CREATE_EDIT))),
	STUDENTS_EDIT(CapabilityGroup.STUDENTS, "Edit Student", om(p(STUDENTS, CREATE_EDIT))),
	STUDENTS_SEND_NOTIFICATION(CapabilityGroup.STUDENTS, "Send Notification",
			unavailable("No single-student send operation")),
	STUDENTS_RESET_PASSWORD(CapabilityGroup.STUDENTS, "Reset Password", om(p(STUDENTS, CREATE_EDIT))),
	STUDENTS_DELETE(CapabilityGroup.STUDENTS, "Delete Student", om(p(STUDENTS, DELETE))),
	// Checked by StudentDirectoryService when the PENDING tab is requested (list + CSV export).
	STUDENTS_VIEW_PENDING(CapabilityGroup.STUDENTS, "View Pending Students", om(p(STUDENTS, VIEW))),
	STUDENTS_APPROVE_PENDING(CapabilityGroup.STUDENTS, "Approve Reject Pending Students",
			om(p(STUDENTS, CREATE_EDIT))),
	STUDENTS_EXPORT_CSV(CapabilityGroup.STUDENTS, "Export Students (CSV)", om(p(STUDENTS, VIEW))),
	STUDENTS_EXPORT_QR(CapabilityGroup.STUDENTS, "Export Student QR Labels", om(p(STUDENTS, VIEW))),
	// ADR-033 (amended by STAFF-MGMT-03): actor and subject stay distinct; start/end audited.
	STUDENTS_LOG_AS(CapabilityGroup.STUDENTS, "Log as Student", om(p(STUDENT_IMPERSONATION, CREATE_EDIT))),

	// ---------------------------------------------------------------- Exams
	EXAMS_VIEW(CapabilityGroup.EXAMS, "View Exams", omt(p(EXAMS, VIEW))),
	// POST /exams, POST /exams/courses/{courseId}/exams.
	EXAMS_SCHEDULE(CapabilityGroup.EXAMS, "Schedule New Exam", om(p(EXAMS, CREATE_EDIT), p(EXAMS, VIEW))),
	// Exam enrollments: enroll / remove / candidates / sync paid students.
	EXAMS_MANAGE(CapabilityGroup.EXAMS, "Manage Exams", om(p(EXAMS, CREATE_EDIT), p(EXAMS, VIEW))),
	// PUT /exams/{examId}.
	EXAMS_EDIT(CapabilityGroup.EXAMS, "Edit Exam", om(p(EXAMS, CREATE_EDIT), p(EXAMS, VIEW))),
	EXAMS_DELETE(CapabilityGroup.EXAMS, "Delete Exam",
			unavailable("Deleting an exam needs exam DELETE, which no staff role holds (owning teacher only)")),
	EXAMS_START(CapabilityGroup.EXAMS, "Start Exam",
			unavailable("Exams open on their schedule; there is no manual start operation")),
	EXAMS_STOP(CapabilityGroup.EXAMS, "Stop Exam",
			unavailable("Exams close on their schedule; there is no manual stop operation")),
	EXAMS_VIEW_RESULTS(CapabilityGroup.EXAMS, "View Results", om(p(EXAMS, VIEW))),
	// POST / PUT / DELETE /exam-leaderboards (all EXAMS/CREATE_EDIT in ExamLeaderboardService).
	EXAMS_CREATE_LEADERBOARD(CapabilityGroup.EXAMS, "Create Leaderboard", om(p(EXAMS, CREATE_EDIT))),
	EXAMS_VIEW_ANSWERS(CapabilityGroup.EXAMS, "View Answers", om(p(EXAMS, VIEW))),
	// GET /exams/{examId}/export.csv (results report).
	EXAMS_GENERATE_REPORT(CapabilityGroup.EXAMS, "Generate Report", om(p(EXAMS, VIEW))),

	// ---------------------------------------------------------------- Attendance
	ATTENDANCE_VIEW(CapabilityGroup.ATTENDANCE, "View Attendance", om(p(ATTENDANCE, VIEW))),
	ATTENDANCE_ADD(CapabilityGroup.ATTENDANCE, "Add Attendance", om(p(ATTENDANCE, CREATE_EDIT))),

	// ---------------------------------------------------------------- Payments
	PAYMENTS_VIEW(CapabilityGroup.PAYMENTS, "View Payments", om(p(PAYMENTS_SLIPS, VIEW), p(FREE_CARDS, VIEW))),
	PAYMENTS_ADD(CapabilityGroup.PAYMENTS, "Add Payment", om(p(PAYMENTS_SLIPS, APPROVE), p(PAYMENTS_SLIPS, VIEW))),
	PAYMENTS_ISSUE_FREE_CARDS(CapabilityGroup.PAYMENTS, "Issue Free Cards", om(p(FREE_CARDS, CREATE_EDIT))),
	// Revoke is a state change on the card (reason required, once only); never a row delete.
	PAYMENTS_REVOKE_FREE_CARDS(CapabilityGroup.PAYMENTS, "Revoke Free Cards", om(p(FREE_CARDS, DELETE))),
	PAYMENTS_LIST_UNDO(CapabilityGroup.PAYMENTS, "List Undo Payments", om(p(PAYMENTS_SLIPS, VIEW))),
	PAYMENTS_UNDO(CapabilityGroup.PAYMENTS, "Undo Payments", om(p(PAYMENTS_SLIPS, APPROVE))),
	PAYMENTS_APPROVE_PENDING(CapabilityGroup.PAYMENTS, "Approve Reject Pending Payments",
			om(p(PAYMENTS_SLIPS, APPROVE))),
	// PUT /access-policy, /access-policy/courses/{id}. A course device-limit change additionally
	// needs DEVICES/CREATE_EDIT, which this capability deliberately does not imply.
	PAYMENTS_UPDATE_RULES(CapabilityGroup.PAYMENTS, "Update Payment Rules", om(p(ACCESS_EXPIRY, CREATE_EDIT))),

	// ---------------------------------------------------------------- Media Library
	MEDIA_VIEW(CapabilityGroup.MEDIA_LIBRARY, "View Media Library", om(p(MATERIALS, VIEW))),
	MEDIA_ADD(CapabilityGroup.MEDIA_LIBRARY, "Add Media", om(p(MATERIALS, CREATE_EDIT))),
	MEDIA_DELETE(CapabilityGroup.MEDIA_LIBRARY, "Delete Media", om(p(MATERIALS, DELETE))),
	MEDIA_VIEW_ZOOM_RECORDINGS(CapabilityGroup.MEDIA_LIBRARY, "View Zoom Recordings",
			locked("Provider integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, VIEW))),
	MEDIA_VIEW_YOUTUBE_CHANNEL(CapabilityGroup.MEDIA_LIBRARY, "View Youtube Channel",
			locked("Provider integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, VIEW))),
	MEDIA_UPLOAD_YOUTUBE_CHANNEL(CapabilityGroup.MEDIA_LIBRARY, "Upload Youtube Channel",
			locked("Provider integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, CREATE_EDIT))),
	// MEDIA-FOLDERS-01: Tenant Admin only by default (Content Manager holds MATERIALS/CREATE_EDIT but
	// not these), configurable for Operator/Manager/seeded roles/Teacher, Teacher default off. The
	// folder endpoints also read the library (MATERIALS/VIEW), so that pair is implied too.
	MEDIA_MANAGE_FOLDERS(CapabilityGroup.MEDIA_LIBRARY, "Manage Media Folders",
			omtTeacherOffFor(new String[] { "TENANT_ADMIN" }, p(MATERIALS, CREATE_EDIT), p(MATERIALS, VIEW))),
	MEDIA_SHARE_FOLDERS(CapabilityGroup.MEDIA_LIBRARY, "Share Media Folders",
			omtTeacherOffFor(new String[] { "TENANT_ADMIN" }, p(MATERIALS, CREATE_EDIT), p(MATERIALS, VIEW))),

	// ---------------------------------------------------------------- Notifications
	NOTIFICATIONS_VIEW(CapabilityGroup.NOTIFICATIONS, "Show Notifications", om(p(COMMUNICATIONS, VIEW))),
	NOTIFICATIONS_PUBLISH(CapabilityGroup.NOTIFICATIONS, "Publish Notifications",
			om(p(COMMUNICATIONS, CREATE_EDIT), p(COMMUNICATIONS, VIEW))),
	NOTIFICATIONS_SCHEDULED(CapabilityGroup.NOTIFICATIONS, "Manage Scheduled Notifications",
			unavailable("No scheduled-broadcast management")),

	// ---------------------------------------------------------------- Timetable
	TIMETABLE_VIEW(CapabilityGroup.TIMETABLE, "View Timetable", om(p(COURSES, VIEW))),
	TIMETABLE_EDIT(CapabilityGroup.TIMETABLE, "Edit Timetable", om(p(COURSES, CREATE_EDIT))),

	// ---------------------------------------------------------------- Integrations
	INTEGRATIONS_VIEW(CapabilityGroup.INTEGRATIONS, "View Integrations",
			locked("Integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, VIEW))),
	INTEGRATIONS_ADD(CapabilityGroup.INTEGRATIONS, "Add Integration",
			locked("Integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, CREATE_EDIT))),
	INTEGRATIONS_EDIT(CapabilityGroup.INTEGRATIONS, "Edit Integration",
			locked("Integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, CREATE_EDIT))),
	INTEGRATIONS_DELETE(CapabilityGroup.INTEGRATIONS, "Delete Integration",
			locked("Integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, CREATE_EDIT))),
	INTEGRATIONS_CONFIGURE(CapabilityGroup.INTEGRATIONS, "Configure Integration",
			locked("Integrations are Tenant Admin only (ADR-018)", p(INTEGRATIONS, CREATE_EDIT))),

	// ---------------------------------------------------------------- Tute (TUTE-01 section 8)
	// Tenant Admin holds every TUTES pair statically; Operator / Manager / seeded roles only by
	// explicit grant; Teacher only by explicit grant (default off) and then for own courses only.
	// GET /courses/{id}/tutes, /courses/{id}/tutes/paid-sessions.
	TUTE_VIEW_TUTES(CapabilityGroup.TUTE, "View Tutes", omtTeacherOff(p(TUTES, VIEW))),
	// GET /courses/{id}/tutes/{tuteId}, .../fulfillments.
	TUTE_VIEW_TUTE(CapabilityGroup.TUTE, "View Tute", omtTeacherOff(p(TUTES, VIEW))),
	TUTE_CREATE(CapabilityGroup.TUTE, "Create Tute", omtTeacherOff(p(TUTES, CREATE_EDIT))),
	// PUT /courses/{id}/tutes/{tuteId}, POST .../sync-payments.
	TUTE_UPDATE(CapabilityGroup.TUTE, "Update Tute", omtTeacherOff(p(TUTES, CREATE_EDIT))),
	// POST .../fulfillments (manual assignment), GET .../eligible-students.
	TUTE_ENROLL(CapabilityGroup.TUTE, "Enroll Tute", omtTeacherOff(p(TUTES, CREATE_EDIT))),
	// POST .../fulfillments/{id}/cancel.
	TUTE_UNROLL(CapabilityGroup.TUTE, "Unroll Tute", omtTeacherOff(p(TUTES, CREATE_EDIT))),
	// POST /tute-dispatch/confirm, .../fulfillments/{id}/status.
	TUTE_UPDATE_STATUS(CapabilityGroup.TUTE, "Update Tute Status", omtTeacherOff(p(TUTES, APPROVE))),
	// DELETE /courses/{id}/tutes/{tuteId} (hard delete without history, else archive).
	TUTE_DELETE(CapabilityGroup.TUTE, "Delete Tute", omtTeacherOff(p(TUTES, DELETE))),
	// GET /tute-dispatch/queue, /tute-dispatch/filter-options: names, index numbers, cities.
	TUTE_DISPATCH_VIEW(CapabilityGroup.TUTE, "View Tute Dispatch", omtTeacherOff(p(TUTES, VIEW))),
	// POST /tute-dispatch/labels: the label PDF (full addresses and phones). Never implied by
	// viewing the queue; never configurable for Read-only Auditor (bulk personal-data export).
	TUTE_DISPATCH_PRINT_LABELS(CapabilityGroup.TUTE, "Print Dispatch Labels",
			omtTeacherOffNoAuditor(p(TUTES, VIEW))),

	// ---------------------------------------------------------------- Reports (one per report controller)
	REPORTS_TEACHER_INCOME(CapabilityGroup.REPORTS, "Generate Teacher Reports", om(p(FINANCE_EXPENSES, VIEW))),
	REPORTS_STUDENT_PAYMENTS(CapabilityGroup.REPORTS, "Generate Student Reports", om(p(FINANCE_EXPENSES, VIEW))),
	// The course summary also reads attendance facts (AttendanceReportQueryApi -> ATTENDANCE/VIEW).
	REPORTS_COURSE_SUMMARY(CapabilityGroup.REPORTS, "Generate Course Reports",
			om(p(FINANCE_EXPENSES, VIEW), p(ATTENDANCE, VIEW))),
	REPORTS_ATTENDANCE(CapabilityGroup.REPORTS, "Generate Attendance Report", om(p(ATTENDANCE, VIEW))),
	REPORTS_UNPAID_STUDENTS(CapabilityGroup.REPORTS, "Generate Unpaid Students Report", om(p(FINANCE_EXPENSES, VIEW))),
	REPORTS_REGISTRATIONS(CapabilityGroup.REPORTS, "Generate Registration Report", om(p(FINANCE_EXPENSES, VIEW))),
	REPORTS_STUDENT_BEHAVIOR(CapabilityGroup.REPORTS, "Generate Student Behaviour Report",
			om(p(FINANCE_EXPENSES, VIEW))),
	REPORTS_UNDO_PAYMENTS(CapabilityGroup.REPORTS, "Generate Undo Payments Report", om(p(FINANCE_EXPENSES, VIEW))),

	// ---------------------------------------------------------------- Translations (I18N-01 section 7)
	// Tenant Admin holds every TRANSLATIONS pair statically; Operator / Manager / seeded roles
	// only by explicit grant (off by default); Teacher is not configurable.
	// GET /i18n/admin/entries, /preview-catalog, /export, /releases.
	TRANSLATIONS_VIEW(CapabilityGroup.TRANSLATIONS, "View Translations", om(p(TRANSLATIONS, VIEW))),
	// PUT/DELETE /i18n/admin/drafts/..., POST .../reset, POST /i18n/admin/import.
	TRANSLATIONS_EDIT(CapabilityGroup.TRANSLATIONS, "Edit Translations", om(p(TRANSLATIONS, CREATE_EDIT))),
	// POST /i18n/admin/publish, POST /i18n/admin/releases/{id}/rollback.
	TRANSLATIONS_PUBLISH(CapabilityGroup.TRANSLATIONS, "Publish Translations", om(p(TRANSLATIONS, APPROVE))),

	// ---------------------------------------------------------------- Staff (never configurable)
	STAFF_VIEW(CapabilityGroup.STAFF, "View Staff", locked(CapabilitySpec.TENANT_ADMIN_ONLY, p(STAFF_AND_ROLES, VIEW))),
	STAFF_MANAGE(CapabilityGroup.STAFF, "Manage Staff",
			locked(CapabilitySpec.TENANT_ADMIN_ONLY, p(STAFF_AND_ROLES, CREATE_EDIT))),
	STAFF_ASSIGN_ROLES(CapabilityGroup.STAFF, "Assign Staff Roles",
			locked(CapabilitySpec.TENANT_ADMIN_ONLY, p(STAFF_AND_ROLES, CREATE_EDIT))),
	STAFF_PERMISSIONS_EDIT(CapabilityGroup.STAFF, "Edit Role Permissions",
			locked(CapabilitySpec.TENANT_ADMIN_ONLY, p(STAFF_AND_ROLES, CREATE_EDIT))),
	STAFF_ACTIVITY_VIEW(CapabilityGroup.STAFF, "View Staff Activities",
			lockedFor("Tenant Admin and Read-only Auditor only", p(AUDIT_LOG, VIEW), "TENANT_ADMIN",
					"READ_ONLY_AUDITOR"));

	private final CapabilityGroup group;

	private final String label;

	private final CapabilitySpec spec;

	Capability(CapabilityGroup group, String label, CapabilitySpec spec) {
		this.group = group;
		this.label = label;
		this.spec = spec;
	}

	/** Stable persisted key (= constant name). */
	public String key() {
		return name();
	}

	public CapabilityGroup group() {
		return group;
	}

	public String label() {
		return label;
	}

	public CapabilityAvailability availability() {
		return spec.availability;
	}

	/** Why the capability is LOCKED / UNAVAILABLE; {@code null} when ENFORCED. */
	public String reason() {
		return spec.reason;
	}

	/** The endpoint's existing static gate; {@code null} for UNAVAILABLE capabilities. */
	public PermissionPair primary() {
		return spec.primary;
	}

	/** Every pair the endpoint's code path checks (includes {@link #primary()}). */
	public Set<PermissionPair> implied() {
		return spec.implied;
	}

	/** Roles a Tenant Admin may configure this capability for; empty unless ENFORCED. */
	public Set<ConfigurableRole> configurableFor() {
		return spec.configurableFor;
	}

	public boolean isConfigurableFor(ConfigurableRole role) {
		return spec.availability == CapabilityAvailability.ENFORCED && spec.configurableFor.contains(role);
	}

	/**
	 * Teacher's default grant when the tenant has no explicit row (meaningful only when
	 * {@link #isTeacherConfigurable()}): {@code true} for every historical Teacher capability,
	 * {@code false} for capabilities a Teacher holds only after an explicit grant (TUTE-01).
	 */
	public boolean teacherDefaultGranted() {
		return spec.teacherDefaultGranted;
	}

	/** {@code true} when a Teacher's (ownership-scoped) access to this operation can be narrowed. */
	public boolean isTeacherConfigurable() {
		return isConfigurableFor(ConfigurableRole.TEACHER);
	}

	/**
	 * Optional extra restriction on which static roles hold the capability on top of
	 * {@link #primary()} (e.g. the audit viewer allowlist); {@code null} = no restriction.
	 */
	public Set<String> staticRoleAllowlist() {
		return spec.staticRoleAllowlist;
	}

	/** Null-safe, exception-free lookup by key. */
	public static Optional<Capability> fromKey(String key) {
		if (key == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(valueOf(key));
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
	}

}
