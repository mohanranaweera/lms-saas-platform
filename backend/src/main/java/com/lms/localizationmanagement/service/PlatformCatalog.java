package com.lms.localizationmanagement.service;

import com.lms.localizationmanagement.api.SupportedLocale;
import com.lms.localizationmanagement.format.MessageFormatter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The generated platform catalog ({@code classpath:i18n/platform-catalog.json}, written by
 * {@code node scripts/i18n/catalog.mjs generate}; never hand-edited), loaded once at startup.
 * Holds the English source registry, the reviewed platform translations and the
 * needs-review suggestions per locale. Read-only: platform catalogs have no runtime write path.
 */
@Component
public class PlatformCatalog {

	static final String RESOURCE = "i18n/platform-catalog.json";

	/** One registered message key. */
	public record SourceMessage(String key, String source, String context, String namespace, String sourceHash) {
	}

	private final String version;

	private final String sourceLocale;

	private final List<SupportedLocale> locales;

	private final Map<String, SourceMessage> source;

	private final Map<String, Map<String, String>> reviewed;

	private final Map<String, Map<String, String>> needsReview;

	@org.springframework.beans.factory.annotation.Autowired
	public PlatformCatalog(ObjectMapper objectMapper) {
		this(read(objectMapper));
	}

	PlatformCatalog(JsonNode root) {
		this.version = root.path("version").asString();
		this.sourceLocale = root.path("sourceLocale").asString();
		List<SupportedLocale> parsedLocales = new ArrayList<>();
		for (JsonNode node : root.path("locales")) {
			List<String> aliases = new ArrayList<>();
			node.path("aliases").forEach(alias -> aliases.add(alias.asString()));
			parsedLocales.add(new SupportedLocale(node.path("tag").asString(), node.path("language").asString(),
					node.path("autonym").asString(), node.path("dir").asString("ltr"), aliases));
		}
		this.locales = List.copyOf(parsedLocales);
		Map<String, SourceMessage> parsedSource = new TreeMap<>();
		root.path("source").properties().forEach(entry -> {
			JsonNode value = entry.getValue();
			String text = value.path("source").asString();
			parsedSource.put(entry.getKey(), new SourceMessage(entry.getKey(), text, value.path("context").asString(""),
					value.path("namespace").asString(namespaceOf(entry.getKey())), sourceHash(text)));
		});
		this.source = Collections.unmodifiableMap(parsedSource);
		Map<String, Map<String, String>> parsedReviewed = new LinkedHashMap<>();
		Map<String, Map<String, String>> parsedNeedsReview = new LinkedHashMap<>();
		root.path("translations").properties().forEach(entry -> {
			parsedReviewed.put(entry.getKey(), usable(entry.getKey(), entry.getValue().path("messages")));
			parsedNeedsReview.put(entry.getKey(), strings(entry.getValue().path("needsReview")));
		});
		this.reviewed = Collections.unmodifiableMap(parsedReviewed);
		this.needsReview = Collections.unmodifiableMap(parsedNeedsReview);
		if (locales.isEmpty() || source.isEmpty() || sourceLocale.isBlank()) {
			throw new IllegalStateException(RESOURCE + " is missing locales/source - run the catalog generator");
		}
	}

	private static JsonNode read(ObjectMapper objectMapper) {
		try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
			return objectMapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read " + RESOURCE, ex);
		}
	}

	/** Reviewed messages that are non-blank and valid against the source (else treated as missing). */
	private Map<String, String> usable(String locale, JsonNode messages) {
		Map<String, String> out = new LinkedHashMap<>();
		messages.properties().forEach(entry -> {
			SourceMessage src = source.get(entry.getKey());
			String text = entry.getValue().asString();
			if (src != null && MessageFormatter.isUsable(text, locale, src.source())) {
				out.put(entry.getKey(), text);
			}
		});
		return Collections.unmodifiableMap(out);
	}

	private static Map<String, String> strings(JsonNode node) {
		Map<String, String> out = new LinkedHashMap<>();
		node.properties().forEach(entry -> out.put(entry.getKey(), entry.getValue().asString()));
		return Collections.unmodifiableMap(out);
	}

	public static String namespaceOf(String key) {
		int dot = key.indexOf('.');
		return dot < 0 ? key : key.substring(0, dot);
	}

	/** SHA-256 (hex) of the English source - stored with each override to detect source changes. */
	public static String sourceHash(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Content hash of the generated catalog (first part of the public catalog {@code version}). */
	public String version() {
		return version;
	}

	public String sourceLocale() {
		return sourceLocale;
	}

	public List<SupportedLocale> locales() {
		return locales;
	}

	/** Every registered key, sorted. */
	public Map<String, SourceMessage> source() {
		return source;
	}

	public Optional<SourceMessage> message(String key) {
		return Optional.ofNullable(key == null ? null : source.get(key));
	}

	/**
	 * The reviewed platform value for {@code key} in {@code locale}: the English source for the
	 * source locale, else the reviewed translation (blank/invalid already dropped at load).
	 */
	public Optional<String> platformValue(String locale, String key) {
		SourceMessage src = source.get(key);
		if (src == null) {
			return Optional.empty();
		}
		if (sourceLocale.equals(locale)) {
			return Optional.of(src.source());
		}
		return Optional.ofNullable(reviewed.getOrDefault(locale, Map.of()).get(key));
	}

	/** The fuzzy (not shipped) platform suggestion for {@code key}, if any. */
	public Optional<String> suggestion(String locale, String key) {
		return Optional.ofNullable(needsReview.getOrDefault(locale, Map.of()).get(key));
	}

}
