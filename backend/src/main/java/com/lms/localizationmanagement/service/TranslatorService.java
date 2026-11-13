package com.lms.localizationmanagement.service;

import com.lms.localizationmanagement.api.Translator;
import com.lms.localizationmanagement.format.MessageFormatter;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * {@link Translator} over the current tenant's effective catalog (published override -> reviewed
 * platform translation -> English source). An unsupported locale falls back to the source
 * locale; a message that cannot be formatted falls back to the English source.
 */
@Service
public class TranslatorService implements Translator {

	private final PlatformCatalog platformCatalog;

	private final LocaleRegistry localeRegistry;

	private final TranslationCatalogService catalogService;

	public TranslatorService(PlatformCatalog platformCatalog, LocaleRegistry localeRegistry,
			TranslationCatalogService catalogService) {
		this.platformCatalog = platformCatalog;
		this.localeRegistry = localeRegistry;
		this.catalogService = catalogService;
	}

	@Override
	public String msg(String key, String locale, Map<String, ?> params) {
		PlatformCatalog.SourceMessage source = platformCatalog.message(key)
			.orElseThrow(() -> new IllegalArgumentException("Unregistered message key: " + key));
		String tag = localeRegistry.canonicalize(locale).orElse(localeRegistry.sourceLocale());
		String text = catalogService.effective(tag).messages().getOrDefault(key, source.source());
		try {
			return MessageFormatter.format(text, tag, params);
		}
		catch (MessageFormatter.MessageSyntaxException ex) {
			return MessageFormatter.format(source.source(), tag, params);
		}
	}

}
