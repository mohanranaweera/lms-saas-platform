package com.lms.localizationmanagement.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** {@code { locale | null }} - the saved canonical tag, {@code null} when none is saved. */
public record LocalePreferenceResponse(@JsonInclude(JsonInclude.Include.ALWAYS) String locale) {

}
