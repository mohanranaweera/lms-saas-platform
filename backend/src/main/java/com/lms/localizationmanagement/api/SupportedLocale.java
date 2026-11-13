package com.lms.localizationmanagement.api;

import java.util.List;

/** One platform-supported UI locale from {@code i18n/locales.json} (via the generated catalog). */
public record SupportedLocale(String tag, String language, String autonym, String dir, List<String> aliases) {

	public SupportedLocale {
		aliases = aliases == null ? List.of() : List.copyOf(aliases);
	}

}
