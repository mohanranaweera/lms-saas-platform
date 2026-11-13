package com.lms.localizationmanagement.web.dto;

/** {@code POST /api/v1/i18n/admin/releases/{id}/rollback}; {@code expectedVersion} = current release number. */
public record RollbackRequest(Integer expectedVersion, String summary) {

}
