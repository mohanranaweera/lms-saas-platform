package com.lms.exammanagement.domain;

/**
 * Mirrors {@code ck_exam_status} (V26) exactly. Per the module plan's boxed
 * note (MVP-017 §7): {@code DRAFT} (authoring, not visible to students) and
 * {@code SCHEDULED} (window/questions locked in by the one manually-triggered
 * {@code APPROVE}-gated transition) are the only two values ever written by a
 * direct user action; {@code PUBLISHED} (window has started) and {@code
 * CLOSED} (window has ended) are system-computed - lazily advanced by {@link
 * com.lms.exammanagement.service.ExamLifecycleService} the first time any
 * read/access-check observes the relevant boundary has passed, never set
 * directly by a controller/request. {@code exam.results_published_at} is a
 * fully separate, independently-set gate on top of this lifecycle - never
 * conflated with {@link #PUBLISHED} above.
 */
public enum ExamStatus {

	DRAFT, SCHEDULED,

	/**
	 * The exam window is open for student attempts (the current time is within
	 * {@code [scheduledStart, scheduledEnd)}). Unrelated to {@code
	 * exam.resultsPublishedAt}/results visibility, which is a fully separate
	 * concept - see this enum's class javadoc and {@link
	 * com.lms.exammanagement.service.ResultsPublishingService}.
	 */
	PUBLISHED,

	CLOSED

}
