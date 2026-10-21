package com.lms.coursemanagement.course.service;

import com.lms.coursemanagement.api.LessonPaymentType;
import com.lms.coursemanagement.api.LessonResourceType;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * KLASS-T4a service input for creating/editing a lesson ("Session"). On edit
 * every field except {@code sequence} is replaced ({@code sequence null} =
 * unchanged / appended on create); {@code paymentType null} means
 * {@link LessonPaymentType#COVERED_BY_CLASS_FEES} (the V85 default).
 * {@code billingYear}/{@code billingMonth} are legacy wire fields only;
 * {@code CourseLessonService} derives the actual stored month server-side.
 */
public record LessonInput(String title, Integer sequence, Instant startsAt, Instant endsAt,
		LessonResourceType resourceType, LessonPaymentType paymentType, BigDecimal price, String description,
		String lessonLabel, Integer billingYear, Integer billingMonth) {

	/** The pre-KLASS shape: a title and sequence only. */
	public static LessonInput titleOnly(String title, Integer sequence) {
		return new LessonInput(title, sequence, null, null, null, null, null, null, null, null, null);
	}

}
