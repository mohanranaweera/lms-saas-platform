package com.lms.localizationmanagement.service;

import com.lms.localizationmanagement.api.SupportedLocale;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Supported locales (from the generated platform catalog, i.e. {@code i18n/locales.json}) and
 * canonicalization of untrusted input. Aliases only match input; responses and storage always
 * use the canonical tag (doc section 1).
 */
@Component
public class LocaleRegistry {

	/** BCP-47-ish shape accepted as input ({@code _} is normalized to {@code -} first). */
	private static final Pattern INPUT = Pattern.compile("^[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8}){0,4}$");

	private final List<SupportedLocale> locales;

	private final String sourceLocale;

	public LocaleRegistry(PlatformCatalog catalog) {
		this.locales = catalog.locales();
		this.sourceLocale = catalog.sourceLocale();
	}

	public List<SupportedLocale> locales() {
		return locales;
	}

	/** The platform fallback / English source locale ({@code en-LK}). */
	public String sourceLocale() {
		return sourceLocale;
	}

	public boolean isSupported(String canonicalTag) {
		return canonicalTag != null && locales.stream().anyMatch(l -> l.tag().equals(canonicalTag));
	}

	public Optional<SupportedLocale> find(String canonicalTag) {
		return locales.stream().filter(l -> l.tag().equals(canonicalTag)).findFirst();
	}

	/**
	 * Exact tag (case-insensitive) -> alias (case-insensitive) -> language-subtag match. Empty
	 * for blank, malformed or unsupported input.
	 */
	public Optional<String> canonicalize(String input) {
		if (input == null) {
			return Optional.empty();
		}
		String value = input.strip().replace('_', '-');
		if (value.isEmpty() || value.length() > 35 || !INPUT.matcher(value).matches()) {
			return Optional.empty();
		}
		for (SupportedLocale locale : locales) {
			if (locale.tag().equalsIgnoreCase(value)) {
				return Optional.of(locale.tag());
			}
		}
		for (SupportedLocale locale : locales) {
			for (String alias : locale.aliases()) {
				if (alias.equalsIgnoreCase(value)) {
					return Optional.of(locale.tag());
				}
			}
		}
		String language = value.split("-", -1)[0].toLowerCase(Locale.ROOT);
		for (SupportedLocale locale : locales) {
			if (locale.language().equalsIgnoreCase(language)) {
				return Optional.of(locale.tag());
			}
		}
		return Optional.empty();
	}

	/** {@code candidates} canonicalized and de-duplicated, unsupported values dropped, input order. */
	public List<String> canonicalizeAll(Collection<?> candidates) {
		List<String> out = new ArrayList<>();
		if (candidates == null) {
			return out;
		}
		for (Object candidate : candidates) {
			if (candidate instanceof String s) {
				canonicalize(s).filter(tag -> !out.contains(tag)).ifPresent(out::add);
			}
		}
		return out;
	}

}
