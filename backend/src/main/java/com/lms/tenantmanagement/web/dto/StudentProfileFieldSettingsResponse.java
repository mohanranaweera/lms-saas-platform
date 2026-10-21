package com.lms.tenantmanagement.web.dto;

import java.util.List;

/** {@code GET/PUT /api/v1/tenant-config/student-profile-fields} response body (STU-PROF-01). */
public record StudentProfileFieldSettingsResponse(List<StudentProfileFieldSettingResponse> fields) {

}
