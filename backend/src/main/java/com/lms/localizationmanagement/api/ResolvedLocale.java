package com.lms.localizationmanagement.api;

/** A canonical locale tag and the resolution step that chose it. */
public record ResolvedLocale(String locale, LocaleSource resolvedFrom) {

}
