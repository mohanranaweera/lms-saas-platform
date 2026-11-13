package com.lms.localizationmanagement.web.dto;

/** {@code POST /api/v1/i18n/admin/publish}; {@code expectedVersion} = current release number (0 = none). */
public record PublishRequest(String locale, Integer expectedVersion, String summary) {

}
