package com.lms.enrollmentmanagement.web;

import com.lms.common.api.ApiError;
import com.lms.enrollmentmanagement.api.LessonAccessDeniedException;
import com.lms.enrollmentmanagement.service.CourseContentViews.AccessView;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * KLASS-T4b (plan §4.3): a lesson-access denial from ANY endpoint (launch,
 * material download, video playback, class-session join) is a 403 carrying
 * the machine-readable status and the full access decision, so the student UI
 * can show the right prompt (pay / pending / not started / ended). The body
 * keeps the platform's {@code ApiResponse} envelope fields ({@code success},
 * {@code error.code}) and adds {@code code} and {@code access} at the top
 * level. Ordered before the generic {@code GlobalExceptionHandler}.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LessonAccessExceptionHandler {

	public record LessonAccessErrorBody(boolean success, Object data, ApiError error, String code, AccessView access,
			Instant timestamp, String traceId) {

	}

	@ExceptionHandler(LessonAccessDeniedException.class)
	public ResponseEntity<LessonAccessErrorBody> handle(LessonAccessDeniedException ex) {
		String traceId = MDC.get("correlationId");
		return ResponseEntity.status(ex.getHttpStatus())
			.body(new LessonAccessErrorBody(false, null, ApiError.of(ex.getErrorCode(), ex.getMessage()),
					ex.getErrorCode(), AccessView.of(ex.getDecision()), Instant.now(),
					traceId != null ? traceId : "unknown"));
	}

}
