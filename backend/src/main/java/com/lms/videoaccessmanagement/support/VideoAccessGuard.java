package com.lms.videoaccessmanagement.support;

import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.api.MaterialVideoOwnership;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.ContentAvailability;
import com.lms.enrollmentmanagement.api.LessonAccessApi;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.DomainArea;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/**
 * The combined staff-matrix-or-Teacher-ownership-or-Student-entitlement
 * check every video-asset read/playback action re-runs, mirroring {@code
 * liveclassmanagement.support.LiveClassAccessGuard}/{@code
 * contentmanagement.material.service.MaterialAccessGuard}'s established
 * pattern exactly (Wave 5, plan §7/§9).
 *
 * <p>Resolves ownership via {@link MaterialLookupApi#resolveVideoAssetOwnerships}
 * - the only cross-module dependency permitted on {@code content-management}
 * (never that module's {@code domain}/{@code repository} packages).
 *
 * <p><b>Anti-enumeration rule (plan §7's mandatory cross-tenant test):</b> if
 * {@code videoAssetId} does not resolve to any {@code material} row in the
 * caller's own tenant at all (nonexistent id, cross-tenant id, OR simply "not
 * yet attached to any material" right after upload), this throws {@link
 * NotFoundException} for EVERY role, including Teacher/staff - a video asset
 * id that isn't reachable through the caller's tenant's own material graph
 * does not exist from the caller's point of view. This is a deliberately
 * stricter rule than {@code MaterialAccessGuard}'s (which only applies
 * anti-enumeration to Student), because there is no lesson/course path
 * segment here to already prove tenant membership the way {@code
 * MaterialAccessGuard}'s lesson-id path segment does.
 */
@Component
public class VideoAccessGuard {

	private static final String TEACHER_ROLE = "TEACHER";

	private static final String TEACHER_ASSISTANT_ROLE = "TEACHER_ASSISTANT"; // PROVISIONAL, mirrors MaterialAccessGuard.

	private static final String STUDENT_ROLE = "STUDENT";

	private final MaterialLookupApi materialLookupApi;

	private final AccessPolicyApi accessPolicyApi;

	private final PermissionCheckService permissionCheckService;

	private final LessonAccessApi lessonAccessApi;

	public VideoAccessGuard(MaterialLookupApi materialLookupApi, AccessPolicyApi accessPolicyApi,
			PermissionCheckService permissionCheckService, LessonAccessApi lessonAccessApi) {
		this.materialLookupApi = materialLookupApi;
		this.accessPolicyApi = accessPolicyApi;
		this.permissionCheckService = permissionCheckService;
		this.lessonAccessApi = lessonAccessApi;
	}

	/**
	 * @return the resolved {@link MaterialVideoOwnership} so callers (e.g.
	 * {@code VideoPlaybackSessionService}) don't have to re-resolve it.
	 */
	public MaterialVideoOwnership requireEntitlement(UUID videoAssetId, PermissionAction action) {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();

		List<MaterialVideoOwnership> attachments = materialLookupApi.resolveVideoAssetOwnerships(videoAssetId);
		if (attachments.isEmpty()) {
			throw new NotFoundException("Video not found");
		}

		if (isTeacherRole(principal)) {
			// The asset must be attached to (at least) one lesson of a course the teacher owns.
			return attachments.stream()
				.filter(ownership -> ownership.teacherId().equals(principal.userId()))
				.findFirst()
				.orElseThrow(() -> new AccessDeniedException("You do not have permission to perform this action"));
		}

		if (STUDENT_ROLE.equals(principal.role())) {
			if (action != PermissionAction.VIEW) {
				throw new NotFoundException("Video not found");
			}
			return requireStudentEntitlement(attachments);
		}

		permissionCheckService.requirePermission(DomainArea.MATERIALS, action);
		return attachments.get(0);
	}

	/**
	 * KLASS-T8b SEC L3 - shared video assets. A Media Library asset may be
	 * attached to several lessons (possibly a FREE lesson and a PAID/COVERED
	 * one). Playback carries no lesson context, so the rule is fail-closed:
	 * <ol>
	 * <li>every attachment's lesson is evaluated by the single lesson access
	 * evaluator for the calling student;</li>
	 * <li>if ANY attachment is a non-FREE lesson (COVERED_BY_CLASS_FEES or PAID)
	 * the student is not ALLOWED on, access is decided by - i.e. denied with -
	 * that lesson's own decision (so a FREE attachment can never unlock a paid
	 * lesson's video);</li>
	 * <li>otherwise the student needs at least one ALLOWED attachment.</li>
	 * </ol>
	 * The deciding attachment is then enforced through {@link
	 * LessonAccessApi#requireLessonAccess}, so status codes are unchanged
	 * (UNAVAILABLE/NOT_ENROLLED = 404, device = 403, payment = 403 {@code
	 * {code, access}}). With exactly one attachment this is the pre-existing
	 * single-lesson rule.
	 */
	private MaterialVideoOwnership requireStudentEntitlement(List<MaterialVideoOwnership> attachments) {
		MaterialVideoOwnership deciding = attachments.size() == 1 ? attachments.get(0)
				: decidingAttachment(attachments);
		if (!deciding.courseOpenToEnrolledStudents()) {
			throw new NotFoundException("Video not found");
		}
		if (deciding.lessonId() != null) {
			// KLASS-T4b (ADR-028): a lesson video is a protected leaf - the single
			// lesson evaluator decides (participation, device, window, payment).
			lessonAccessApi.requireLessonAccess(deciding.courseId(), deciding.lessonId(),
					() -> new NotFoundException("Video not found"));
			return deciding;
		}
		// No lesson: legacy course-wide rule (entitlement, expiry/extension/grace, device).
		accessPolicyApi.requireStudentCourseAccess(deciding.courseId(),
				() -> new NotFoundException("Video not found"));
		return deciding;
	}

	private MaterialVideoOwnership decidingAttachment(List<MaterialVideoOwnership> attachments) {
		Map<UUID, LessonAccessDecision> decisions = new HashMap<>();
		Map<UUID, List<UUID>> lessonsByCourse = new LinkedHashMap<>();
		for (MaterialVideoOwnership attachment : attachments) {
			if (attachment.lessonId() != null) {
				lessonsByCourse.computeIfAbsent(attachment.courseId(), ignored -> new ArrayList<>())
					.add(attachment.lessonId());
			}
		}
		lessonsByCourse
			.forEach((courseId, lessonIds) -> decisions.putAll(lessonAccessApi.evaluate(courseId, lessonIds)));

		MaterialVideoOwnership firstAllowed = null;
		for (MaterialVideoOwnership attachment : attachments) {
			if (attachment.lessonId() == null) {
				// A lesson-less (legacy) attachment can never be proven harmless here.
				return attachment;
			}
			LessonAccessDecision decision = decisions.get(attachment.lessonId());
			boolean allowed = attachment.courseOpenToEnrolledStudents() && decision != null && decision.allowed();
			if (!allowed && (decision == null || decision.payment() != LessonAccessDecision.Payment.FREE)) {
				return attachment; // a non-FREE attachment the student may not open - deny through it
			}
			if (allowed && firstAllowed == null) {
				firstAllowed = attachment;
			}
		}
		return firstAllowed != null ? firstAllowed : attachments.get(0);
	}

	/** Wave 10: the video availability window, decided by the central {@link AccessPolicyApi}. */
	public ContentAvailability contentWindow(java.time.Instant availableFrom, java.time.Instant availableUntil) {
		return accessPolicyApi.evaluateContentWindow(availableFrom, availableUntil);
	}

	public boolean isCurrentPrincipalStudent() {
		return STUDENT_ROLE.equals(AuthenticatedPrincipalHolder.get().role());
	}

	public boolean isCurrentPrincipalTeacher() {
		return isTeacherRole(AuthenticatedPrincipalHolder.get());
	}

	private boolean isTeacherRole(AuthenticatedPrincipal principal) {
		return TEACHER_ROLE.equals(principal.role()) || TEACHER_ASSISTANT_ROLE.equals(principal.role());
	}

}
