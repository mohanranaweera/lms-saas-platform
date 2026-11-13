package com.lms.localizationmanagement.web.dto;

/** {@code POST /api/v1/i18n/admin/drafts/{locale}/{key}/reset}; {@code expectedVersion} null = no draft yet. */
public record ResetDraftRequest(Long expectedVersion) {

}
