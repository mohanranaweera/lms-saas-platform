package com.lms.enrollmentmanagement.service;

import com.lms.enrollmentmanagement.api.LessonAccessDecision;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read models (serialized as-is, JSON camelCase) of the student course content
 * endpoints (KLASS-T4b, plan §4.3). Safe metadata only: no resource URL,
 * external URL, storage key or note content ever appears here.
 */
public final class CourseContentViews {

	private CourseContentViews() {
	}

	/** {@code access} of a {@link LessonNode} and of a 403 lesson-access denial. */
	public record AccessView(String status, String availability, String payment, PurchaseView requiredPurchase) {

		public static AccessView of(LessonAccessDecision decision) {
			LessonAccessDecision.RequiredPurchase purchase = decision.requiredPurchase();
			return new AccessView(decision.status().name(), decision.availability().name(),
					decision.payment().name(),
					purchase == null ? null
							: new PurchaseView(purchase.scope(), purchase.courseId(), purchase.moduleId(),
									purchase.lessonId(), purchase.year(), purchase.month()));
		}

	}

	public record PurchaseView(String scope, UUID courseId, UUID moduleId, UUID lessonId, Integer year,
			Integer month) {

	}

	/**
	 * @param courseId the lesson's own (for linked content: SOURCE) course.
	 * @param resourceType {@code LessonResourceType} name or {@code null}.
	 * @param paymentType {@code FREE|COVERED_BY_CLASS_FEES|PAID}.
	 * @param price only for PAID lessons.
	 */
	public record LessonNode(UUID lessonId, UUID courseId, UUID moduleId, String title, String lessonLabel,
			String resourceType, String paymentType, BigDecimal price, Instant startsAt, Instant endsAt,
			boolean viewed, AccessView access) {

	}

	public record ModuleNode(UUID moduleId, String title, int sequence, UUID sourceCourseId,
			List<LessonNode> lessons) {

	}

	public record PeriodPurchase(String scope, UUID courseId, int year, int month) {

	}

	/** @param status {@code PAID|PARTIAL|PENDING|REQUIRED|NOT_REQUIRED}. */
	public record PeriodPayment(String status, PeriodPurchase purchase) {

	}

	/** @param key {@code "yyyy-mm"}, or {@code null} for lessons with no billing month ("Course content"). */
	public record PeriodNode(String key, Integer year, Integer month, String label, PeriodPayment payment,
			List<ModuleNode> modules) {

	}

	public record CourseContent(UUID courseId, int viewedCount, int totalCount, String timezone,
			List<PeriodNode> periods) {

	}

	/** A live-class lesson of {@code GET .../live-classes}: the {@link LessonNode} fields + {@code moduleTitle}. */
	public record LiveClassNode(UUID lessonId, UUID courseId, UUID moduleId, String moduleTitle, String title,
			String lessonLabel, String resourceType, String paymentType, BigDecimal price, Instant startsAt,
			Instant endsAt, boolean viewed, AccessView access) {

	}

	/**
	 * @param kind {@code MATERIAL|CLASS_SESSION}.
	 * @param type material type name (e.g. {@code PDF}, {@code VIDEO}) or the
	 * class session lifecycle status ({@code SCHEDULED|LIVE|COMPLETED}).
	 */
	public record LaunchResource(String kind, UUID id, String title, String type) {

	}

	public record PlayerLinks(String androidUrl, String iosUrl) {

	}

	public record LessonLaunch(AccessView access, List<LaunchResource> resources, boolean playerHandoffRequired,
			PlayerLinks playerLinks) {

	}

}
