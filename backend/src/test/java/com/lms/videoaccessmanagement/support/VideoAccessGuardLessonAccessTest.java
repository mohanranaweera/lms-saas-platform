package com.lms.videoaccessmanagement.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.common.error.NotFoundException;
import com.lms.contentmanagement.api.MaterialLookupApi;
import com.lms.contentmanagement.api.MaterialVideoOwnership;
import com.lms.enrollmentmanagement.api.AccessPolicyApi;
import com.lms.enrollmentmanagement.api.LessonAccessApi;
import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import com.lms.enrollmentmanagement.api.LessonAccessDeniedException;
import com.lms.enrollmentmanagement.api.LessonAccessStatus;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.PermissionAction;
import com.lms.identityaccessservice.api.PermissionCheckService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * KLASS-T4b: a lesson video is guarded by the lesson access evaluator (not the
 * course-wide rule); a video with no lesson keeps the legacy course rule.
 * KLASS-T8b SEC L3: an asset shared by a FREE and a PAID/COVERED lesson is
 * denied unless the student is ALLOWED on every non-FREE attachment.
 */
@ExtendWith(MockitoExtension.class)
class VideoAccessGuardLessonAccessTest {

	private static final UUID ASSET = UUID.randomUUID();

	private static final UUID COURSE = UUID.randomUUID();

	private static final UUID LESSON = UUID.randomUUID();

	private static final UUID FREE_LESSON = UUID.randomUUID();

	private static final UUID PAID_LESSON = UUID.randomUUID();

	@Mock
	private MaterialLookupApi materialLookupApi;

	@Mock
	private AccessPolicyApi accessPolicyApi;

	@Mock
	private PermissionCheckService permissionCheckService;

	@Mock
	private LessonAccessApi lessonAccessApi;

	private VideoAccessGuard guard;

	@BeforeEach
	void setUp() {
		guard = new VideoAccessGuard(materialLookupApi, accessPolicyApi, permissionCheckService, lessonAccessApi);
		AuthenticatedPrincipalHolder.set(new AuthenticatedPrincipal(UUID.randomUUID(), UUID.randomUUID(), "STUDENT",
				UUID.randomUUID()));
	}

	@AfterEach
	void clear() {
		AuthenticatedPrincipalHolder.clear();
	}

	@Test
	void lessonVideoIsDeniedWhenTheLessonEvaluatorDeniesPayment() {
		when(materialLookupApi.resolveVideoAssetOwnerships(ASSET))
			.thenReturn(List.of(new MaterialVideoOwnership(UUID.randomUUID(), COURSE, UUID.randomUUID(), true,
					LESSON)));
		when(lessonAccessApi.requireLessonAccess(eq(COURSE), eq(LESSON), any()))
			.thenThrow(new LessonAccessDeniedException(decision(LESSON, LessonAccessStatus.PAYMENT_REQUIRED,
					LessonAccessDecision.Payment.REQUIRED)));

		assertThatThrownBy(() -> guard.requireEntitlement(ASSET, PermissionAction.VIEW))
			.isInstanceOf(LessonAccessDeniedException.class);
		verify(accessPolicyApi, never()).requireStudentCourseAccess(any(), any());
	}

	@Test
	void videoWithoutLessonKeepsTheLegacyCourseRule() {
		when(materialLookupApi.resolveVideoAssetOwnerships(ASSET))
			.thenReturn(List.of(new MaterialVideoOwnership(UUID.randomUUID(), COURSE, UUID.randomUUID(), true)));
		when(accessPolicyApi.requireStudentCourseAccess(eq(COURSE), any())).thenThrow(new NotFoundException("x"));

		assertThatThrownBy(() -> guard.requireEntitlement(ASSET, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
		verify(lessonAccessApi, never()).requireLessonAccess(any(), any(), any());
	}

	/**
	 * The FREE/PAID bypass: the same asset is attached to a FREE lesson (the
	 * student may open) and a PAID lesson (not bought). Playback must be denied
	 * through the PAID lesson's own decision - the FREE attachment never
	 * unlocks it, whichever order the attachments come in.
	 */
	@Test
	void sharedAssetOnAFreeAndAnUnpaidPaidLessonIsDenied() {
		MaterialVideoOwnership free = attachment(FREE_LESSON);
		MaterialVideoOwnership paid = attachment(PAID_LESSON);
		when(lessonAccessApi.evaluate(eq(COURSE), any())).thenReturn(Map.of(FREE_LESSON,
				decision(FREE_LESSON, LessonAccessStatus.ALLOWED, LessonAccessDecision.Payment.FREE), PAID_LESSON,
				decision(PAID_LESSON, LessonAccessStatus.PAYMENT_REQUIRED, LessonAccessDecision.Payment.REQUIRED)));
		when(lessonAccessApi.requireLessonAccess(eq(COURSE), eq(PAID_LESSON), any()))
			.thenThrow(new LessonAccessDeniedException(decision(PAID_LESSON, LessonAccessStatus.PAYMENT_REQUIRED,
					LessonAccessDecision.Payment.REQUIRED)));
		for (List<MaterialVideoOwnership> order : List.of(List.of(free, paid), List.of(paid, free))) {
			when(materialLookupApi.resolveVideoAssetOwnerships(ASSET)).thenReturn(order);

			assertThatThrownBy(() -> guard.requireEntitlement(ASSET, PermissionAction.VIEW))
				.isInstanceOf(LessonAccessDeniedException.class);
			verify(lessonAccessApi, never()).requireLessonAccess(eq(COURSE), eq(FREE_LESSON), any());
		}
	}

	@Test
	void sharedAssetIsAllowedWhenEveryNonFreeAttachmentIsAllowed() {
		MaterialVideoOwnership free = attachment(FREE_LESSON);
		MaterialVideoOwnership paid = attachment(PAID_LESSON);
		when(materialLookupApi.resolveVideoAssetOwnerships(ASSET)).thenReturn(List.of(free, paid));
		when(lessonAccessApi.evaluate(eq(COURSE), any())).thenReturn(Map.of(FREE_LESSON,
				decision(FREE_LESSON, LessonAccessStatus.ALLOWED, LessonAccessDecision.Payment.FREE), PAID_LESSON,
				decision(PAID_LESSON, LessonAccessStatus.ALLOWED, LessonAccessDecision.Payment.COVERED)));

		assertThat(guard.requireEntitlement(ASSET, PermissionAction.VIEW)).isEqualTo(free);
		verify(lessonAccessApi).requireLessonAccess(eq(COURSE), eq(FREE_LESSON), any());
	}

	@Test
	void sharedAssetWithAnUnknownAttachmentDecisionFailsClosed() {
		MaterialVideoOwnership free = attachment(FREE_LESSON);
		MaterialVideoOwnership paid = attachment(PAID_LESSON);
		when(materialLookupApi.resolveVideoAssetOwnerships(ASSET)).thenReturn(List.of(free, paid));
		when(lessonAccessApi.evaluate(eq(COURSE), any())).thenReturn(Map.of(FREE_LESSON,
				decision(FREE_LESSON, LessonAccessStatus.ALLOWED, LessonAccessDecision.Payment.FREE)));
		when(lessonAccessApi.requireLessonAccess(eq(COURSE), eq(PAID_LESSON), any()))
			.thenThrow(new NotFoundException("Video not found"));

		assertThatThrownBy(() -> guard.requireEntitlement(ASSET, PermissionAction.VIEW))
			.isInstanceOf(NotFoundException.class);
	}

	private static MaterialVideoOwnership attachment(UUID lessonId) {
		return new MaterialVideoOwnership(UUID.randomUUID(), COURSE, UUID.randomUUID(), true, lessonId);
	}

	private static LessonAccessDecision decision(UUID lessonId, LessonAccessStatus status,
			LessonAccessDecision.Payment payment) {
		return new LessonAccessDecision(lessonId, COURSE, status, LessonAccessDecision.Availability.ALWAYS, payment,
				null);
	}

}
