package com.lms.localizationmanagement.web.dto;

/** {@code PUT /api/v1/me/locale} body; {@code null} clears the saved preference. */
public record LocalePreferenceRequest(String locale) {

}
