package com.lms.reportinganalytics.web;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CalendarOccurrences;
import com.lms.coursemanagement.api.ClassCalendarApi;
import com.lms.reportinganalytics.service.pdf.TimetablePdfRenderer;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAL-01 "Download Timetable" (docs/specs/class-calendar.md §6.5). The data is
 * course-management's {@link ClassCalendarApi} - the same caller scope,
 * occurrence generation, session overrides and sorting as {@code GET
 * /api/v1/calendar/occurrences}, every occurrence of the period (never a
 * client-side subset). A {@code courseId} outside the caller's scope is 404.
 */
@RestController
public class TimetablePdfController {

	private final ClassCalendarApi classCalendarApi;

	private final TimetablePdfRenderer renderer;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public TimetablePdfController(ClassCalendarApi classCalendarApi, TimetablePdfRenderer renderer,
			TenantConfigApi tenantConfigApi, TenantContext tenantContext) {
		this.classCalendarApi = classCalendarApi;
		this.renderer = renderer;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	@GetMapping("/api/v1/calendar/timetable.pdf")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<byte[]> timetable(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) UUID courseId,
			@RequestParam(defaultValue = "MONTH") String view) {
		TimetablePdfRenderer.View layout = parseView(view);
		CalendarOccurrences data = classCalendarApi.listCallerOccurrences(from, to, courseId);
		String institute = tenantConfigApi.resolveValue(tenantContext.getTenantId(), ConfigDomain.GENERAL,
				"institute_name")
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.orElse(null);
		byte[] pdf = renderer.render(new TimetablePdfRenderer.Input(institute, layout, data.from(), data.to(),
				data.courseName(), ZoneId.of(data.timezone()), data.serverNow(), data.occurrences()));
		String filename = "timetable-" + from + "-" + to + ".pdf";
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
			.body(pdf);
	}

	private static TimetablePdfRenderer.View parseView(String view) {
		try {
			return TimetablePdfRenderer.View.valueOf(view.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new FieldValidationException("Choose a timetable view.",
					List.of(new FieldError("view", "The view must be MONTH, WEEK or DAY.")));
		}
	}

}
