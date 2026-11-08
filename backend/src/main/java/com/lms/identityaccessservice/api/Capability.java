package com.lms.identityaccessservice.api;

import static com.lms.identityaccessservice.api.CapabilitySpec.locked;
import static com.lms.identityaccessservice.api.CapabilitySpec.lockedFor;
import static com.lms.identityaccessservice.api.CapabilitySpec.om;
import static com.lms.identityaccessservice.api.CapabilitySpec.omt;
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
 * Invariant (unit-tested): every ENFORCED capability's implied pairs are held by Tenant Admin,
 * so a configurable role can never exceed Tenant Admin. Keys ({@link #key()}) are persisted in
 * {@code tenant_role_capability_grant.capability_key}; never rename a constant.
 */
public enum Capability {

	// ---------------------------------------------------------------- Finance
	FINANCE_DASHBOARD_ANNUAL_STATS(CapabilityGroup.FINANCE, "View Annual Stats",
			unavailable("Not a separable dashboard widget")),
	FINANCE_VIEW_INCOME_SUMMARY(CapabilityGroup.FINANCE, "View Income Summary", om(p(FINANCE_EXPENSES, VIEW))),
	FINANCE_DASHBOARD_PENDING_PAYMENTS(CapabilityGroup.FINANCE, "View Pending Payments",
			unavailable("Not a separable dashboard widget")),
	FINANCE_DASHBOARD_RECENT_PAYMENTS(CapabilityGroup.FINANCE, "View Recent Payments",
			unavailable("Not a separable dashboard widget")),
	FINANCE_DASHBOARD_REGISTRATION_PAYMENTS(CapabilityGroup.FINANCE, "View Registration Payments",
			unavailable("Not a separable dashboard widget")),
	FINANCE_VIEW_EXPENSES(CapabilityGroup.FINANCE, "View Expenses", om(p(FINANCE_EXPENSES, VIEW))),
	FINANCE_DASHBOARD_TRANSACTIONS(CapabilityGroup.FINANCE, "View Transactions",
			unavailable("Not a separable dashboard widget")),
	FINANCE_DELETE_CATEGORY(CapabilityGroup.FINANCE, "Delete Category", om(p(FINANCE_EXPENSES, CREATE_EDIT))),
	FINANCE_ACCOUNT_CHANGE_CONFIGURATION(CapabilityGroup.FINANCE, "Change Account Configuration",
			locked("Payment gateway credentials are Tenant Admin only (ADR-017)", p(PAYMENT_GATEWAY, CREATE_EDIT))),
	FINANCE_ACCOUNT_VIEW_CONFIGURATION(CapabilityGroup.FINANCE, "View Account Configuration",
			locked("Payment gateway credentials are Tenant Admin only (ADR-017)", p(PAYMENT_GATEWAY, VIEW))),
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
	TEACHERS_WALLET_ACTIONS(CapabilityGroup.TEACHERS, "Disable Wallet",
			locked("Settlement actions are ledger actions (Tenant Admin / Finance Staff)",
					p(FINANCE_EXPENSES, CREATE_EDIT))),
	TEACHERS_DELETE(CapabilityGroup.TEACHERS, "Delete Teacher",
			unavailable("Teachers are suspended, never deleted")),

	// ---------------------------------------------------------------- Courses
	COURSES_VIEW(CapabilityGroup.COURSES, "View Courses", omt(p(COURSES, VIEW))),
	COURSES_VIEW_STUDENTS(CapabilityGroup.COURSES, "View Course Students", omt(p(COURSES, VIEW))),
	COURSES_VIEW_ARCHIVED(CapabilityGroup.COURSES, "View Archived Courses",
			unavailable("Archived courses are a filter of View Courses, not a separate operation")),
	COURSES_ADD(CapabilityGroup.COURSES, "Add Course", om(p(COURSES, CREATE_EDIT), p(COURSES, VIEW))),
	COURSES_EDIT(CapabilityGroup.COURSES, "Edit Course", om(p(COURSES, CREATE_EDIT), p(COURSES, VIEW))),
	COURSES_ARCHIVE(CapabilityGroup.COURSES, "Archive Course", om(p(COURSES, CREATE_EDIT))),
	COURSES_TOGGLE_REGISTRATION(CapabilityGroup.COURSES, "Toggle Course Registration Button",
			om(p(COURSES, CREATE_EDIT))),
	COURSES_DELETE(CapabilityGroup.COURSES, "Delete Course", om(p(COURSES, DELETE))),
	// POST /students/{id}/enroll records a staff-granted CONFIRMED payment + ledger entry and a
	// paid entitlement (PAYMENTS_SLIPS/APPROVE), so it is not a delegable "free enrollment".
	// Free (ADR-028) staff enrollment is part of Add/Edit Student (course selection).
	COURSES_ENROLL_STUDENTS(CapabilityGroup.COURSES, "Enroll Students", lockedFor(
			"Staff enrollment records a staff-granted payment (Tenant Admin only); "
					+ "free enrollment is done from Add/Edit Student",
			p(STUDENTS, CREATE_EDIT), "TENANT_ADMIN")),
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
	MODULES_QUESTIONS_VIEW(CapabilityGroup.MODULES, "View Questions",
			unavailable("The question bank is teacher/exam-owned")),
	MODULES_QUESTIONS_ADD(CapabilityGroup.MODULES, "Add Questions",
			unavailable("The question bank is teacher/exam-owned")),
	MODULES_QUESTIONS_EDIT(CapabilityGroup.MODULES, "Edit Questions",
			unavailable("The question bank is teacher/exam-owned")),
	MODULES_QUESTIONS_DELETE(CapabilityGroup.MODULES, "Delete Questions",
			unavailable("The question bank is teacher/exam-owned")),
	MODULES_IMPORT(CapabilityGroup.MODULES, "Import Modules", unavailable("No module import operation")),
	MODULES_QUESTIONS_IMPORT(CapabilityGroup.MODULES, "Import Questions",
			unavailable("The question bank is teacher/exam-owned")),

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
	STUDENTS_VIEW_PENDING(CapabilityGroup.STUDENTS, "View Pending Students",
			unavailable("Pending students are a filter of View Students")),
	STUDENTS_APPROVE_PENDING(CapabilityGroup.STUDENTS, "Approve Reject Pending Students",
			om(p(STUDENTS, CREATE_EDIT))),
	STUDENTS_EXPORT_CSV(CapabilityGroup.STUDENTS, "Export Students (CSV)", om(p(STUDENTS, VIEW))),
	STUDENTS_EXPORT_QR(CapabilityGroup.STUDENTS, "Export Student QR Labels", om(p(STUDENTS, VIEW))),
	STUDENTS_LOG_AS(CapabilityGroup.STUDENTS, "Log as Student",
			locked("Tenant Admin only (ADR-033)", p(STUDENT_IMPERSONATION, CREATE_EDIT))),

	// ---------------------------------------------------------------- Exams
	EXAMS_VIEW(CapabilityGroup.EXAMS, "View Exams", omt(p(EXAMS, VIEW))),
	EXAMS_SCHEDULE(CapabilityGroup.EXAMS, "Schedule New Exam",
			unavailable("Exam authoring stays with teachers / Exam Manager")),
	EXAMS_MANAGE(CapabilityGroup.EXAMS, "Manage Exams", unavailable("Exam authoring stays with teachers / Exam Manager")),
	EXAMS_EDIT(CapabilityGroup.EXAMS, "Edit Exam", unavailable("Exam authoring stays with teachers / Exam Manager")),
	EXAMS_DELETE(CapabilityGroup.EXAMS, "Delete Exam", unavailable("Exam authoring stays with teachers / Exam Manager")),
	EXAMS_START(CapabilityGroup.EXAMS, "Start Exam", unavailable("Exam authoring stays with teachers / Exam Manager")),
	EXAMS_STOP(CapabilityGroup.EXAMS, "Stop Exam", unavailable("Exam authoring stays with teachers / Exam Manager")),
	EXAMS_VIEW_RESULTS(CapabilityGroup.EXAMS, "View Results", om(p(EXAMS, VIEW))),
	EXAMS_CREATE_LEADERBOARD(CapabilityGroup.EXAMS, "Create Leaderboard",
			unavailable("Exam authoring stays with teachers / Exam Manager")),
	EXAMS_VIEW_ANSWERS(CapabilityGroup.EXAMS, "View Answers", om(p(EXAMS, VIEW))),
	EXAMS_GENERATE_REPORT(CapabilityGroup.EXAMS, "Generate Report",
			unavailable("Exam authoring stays with teachers / Exam Manager")),

	// ---------------------------------------------------------------- Attendance
	ATTENDANCE_VIEW(CapabilityGroup.ATTENDANCE, "View Attendance", om(p(ATTENDANCE, VIEW))),
	ATTENDANCE_ADD(CapabilityGroup.ATTENDANCE, "Add Attendance", om(p(ATTENDANCE, CREATE_EDIT))),

	// ---------------------------------------------------------------- Payments
	PAYMENTS_VIEW(CapabilityGroup.PAYMENTS, "View Payments", om(p(PAYMENTS_SLIPS, VIEW), p(FREE_CARDS, VIEW))),
	PAYMENTS_ADD(CapabilityGroup.PAYMENTS, "Add Payment", om(p(PAYMENTS_SLIPS, APPROVE), p(PAYMENTS_SLIPS, VIEW))),
	PAYMENTS_ISSUE_FREE_CARDS(CapabilityGroup.PAYMENTS, "Issue Free Cards", om(p(FREE_CARDS, CREATE_EDIT))),
	PAYMENTS_LIST_UNDO(CapabilityGroup.PAYMENTS, "List Undo Payments", om(p(PAYMENTS_SLIPS, VIEW))),
	PAYMENTS_UNDO(CapabilityGroup.PAYMENTS, "Undo Payments", om(p(PAYMENTS_SLIPS, APPROVE))),
	PAYMENTS_APPROVE_PENDING(CapabilityGroup.PAYMENTS, "Approve Reject Pending Payments",
			om(p(PAYMENTS_SLIPS, APPROVE))),
	PAYMENTS_UPDATE_RULES(CapabilityGroup.PAYMENTS, "Update Payment Rules",
			locked("Access policy and gateway settings are Tenant Admin only", p(ACCESS_EXPIRY, CREATE_EDIT))),

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
	MEDIA_MANAGE_FOLDERS(CapabilityGroup.MEDIA_LIBRARY, "Manage Media Folders", unavailable("No media folders")),
	MEDIA_SHARE_FOLDERS(CapabilityGroup.MEDIA_LIBRARY, "Share Media Folders", unavailable("No media folders")),

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

	// ---------------------------------------------------------------- Tute
	TUTE_VIEW_TUTES(CapabilityGroup.TUTE, "View Tutes", unavailable("Tute Dispatch is not built")),
	TUTE_VIEW_TUTE(CapabilityGroup.TUTE, "View Tute", unavailable("Tute Dispatch is not built")),
	TUTE_CREATE(CapabilityGroup.TUTE, "Create Tute", unavailable("Tute Dispatch is not built")),
	TUTE_UPDATE(CapabilityGroup.TUTE, "Update Tute", unavailable("Tute Dispatch is not built")),
	TUTE_ENROLL(CapabilityGroup.TUTE, "Enroll Tute", unavailable("Tute Dispatch is not built")),
	TUTE_UNROLL(CapabilityGroup.TUTE, "Unroll Tute", unavailable("Tute Dispatch is not built")),
	TUTE_UPDATE_STATUS(CapabilityGroup.TUTE, "Update Tute Status", unavailable("Tute Dispatch is not built")),
	TUTE_DELETE(CapabilityGroup.TUTE, "Delete Tute", unavailable("Tute Dispatch is not built")),

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
