package com.lms.localizationmanagement.web.dto;

/** {@code PUT /api/v1/i18n/admin/drafts/{locale}/{key}}; {@code expectedVersion} null = no draft yet. */
public record DraftRequest(String value, Long expectedVersion) {

}
