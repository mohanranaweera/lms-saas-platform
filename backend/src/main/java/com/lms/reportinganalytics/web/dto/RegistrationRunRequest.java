package com.lms.reportinganalytics.web.dto;

import java.util.List;
import java.util.UUID;

/** Plain Registration Report filter values; tenant and authorization never come from this body. */
public record RegistrationRunRequest(List<UUID> courseIds, UUID operatorId, String registrationMethod,
		String startDate, String endDate) {

}
