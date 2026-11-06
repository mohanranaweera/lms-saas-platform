package com.lms.coursemanagement.calendar.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.calendar.service.ClassOccurrenceService;
import com.lms.coursemanagement.calendar.web.dto.PublicTodayClassesResponse;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous "Today's Classes" for the tenant public home (CAL-01 spec §6.6).
 * The tenant is the one {@code TenantResolutionFilter} resolved from the
 * request host - no tenant id is accepted. Disabled (empty list, {@code
 * enabled = false}) when the public page or {@code
 * BRANDING.show_todays_classes} is switched off.
 */
@RestController
@RequestMapping("/api/v1/public/calendar")
public class PublicCalendarController {

	private final ClassOccurrenceService occurrenceService;

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	public PublicCalendarController(ClassOccurrenceService occurrenceService, TenantConfigApi tenantConfigApi,
			TenantContext tenantContext) {
		this.occurrenceService = occurrenceService;
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
	}

	@GetMapping("/today")
	public ResponseEntity<ApiResponse<PublicTodayClassesResponse>> today() {
		Map<String, Object> branding = tenantConfigApi.resolveDomain(tenantContext.getTenantId(),
				ConfigDomain.BRANDING);
		boolean enabled = isOn(branding, "public_page_enabled") && isOn(branding, "show_todays_classes");
		ClassOccurrenceService.PublicToday today = occurrenceService.publicToday(enabled);
		PublicTodayClassesResponse response = new PublicTodayClassesResponse(today.enabled(), today.timezone(),
				today.serverNow(), today.date(),
				today.occurrences()
					.stream()
					.map(o -> new PublicTodayClassesResponse.Occurrence(o.id(), o.courseName(), o.courseSlug(),
							o.coverUrl(), o.teacherName(), null, o.start(), o.end(),
							ClassScheduleController.hhmm(o.startLocalTime()),
							ClassScheduleController.hhmm(o.endLocalTime()), o.status()))
					.toList());
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(ApiResponse.success(response));
	}

	/** Both switches default to on (registry defaults); only an explicit {@code false} turns one off. */
	private static boolean isOn(Map<String, Object> values, String key) {
		return !Boolean.FALSE.equals(values.get(key));
	}

}
