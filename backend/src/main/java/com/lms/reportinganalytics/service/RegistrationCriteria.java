package com.lms.reportinganalytics.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

record RegistrationCriteria(List<UUID> courseIds, UUID operatorId, String registrationMethod,
		LocalDate startDate, LocalDate endDate) {

}
