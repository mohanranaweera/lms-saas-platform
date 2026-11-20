package com.lms.reportinganalytics.service;

import java.util.UUID;

public record StudentBehaviorRow(UUID studentProfileId, UUID studentId, String indexNumber,
        String studentName, String email, String mobile) {
}
