package com.lms.tenantmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.FieldError;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.config.ConfigPropertyDefinition;
import com.lms.tenantmanagement.config.ConfigPropertyRegistry;
import com.lms.tenantmanagement.domain.TenantConfigEntry;
import com.lms.tenantmanagement.repository.TenantConfigEntryRepository;
import com.lms.common.tenant.TenantContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestrates the typed tenant configuration framework (Wave 1): resolves
 * a domain's properties (persisted override or registry default) and
 * validates/persists batched updates. Implements {@link TenantConfigApi}
 * for future cross-module/public callers.
 */
@Service
public class TenantConfigService implements TenantConfigApi {

	private final TenantConfigEntryRepository repository;

	private final ConfigPropertyRegistry registry;

	private final TenantContext tenantContext;

	private final AuditLogApi auditLogApi;

	private final ObjectMapper objectMapper;

	public TenantConfigService(TenantConfigEntryRepository repository, ConfigPropertyRegistry registry,
			TenantContext tenantContext, AuditLogApi auditLogApi, ObjectMapper objectMapper) {
		this.repository = repository;
		this.registry = registry;
		this.tenantContext = tenantContext;
		this.auditLogApi = auditLogApi;
		this.objectMapper = objectMapper;
	}

	/** Every domain's summary (all 17), for {@code GET /api/v1/tenant-config/domains}. */
	@Transactional(readOnly = true)
	public List<ConfigDomainSummary> listDomainSummaries() {
		return java.util.Arrays.stream(ConfigDomain.values())
			.map(domain -> new ConfigDomainSummary(domain, !registry.propertiesFor(domain).isEmpty()))
			.toList();
	}

	/**
	 * Resolves every registered property of {@code domain} for the CURRENT
	 * tenant ({@link TenantContext#getTenantId()}): the persisted override if
	 * one exists, else the registry default. Returns an empty list (never
	 * throws/404s) for a domain with no registered properties yet. A {@code
	 * sensitive} property's value is masked to {@code null} here - the only
	 * place read-path masking happens, so every caller (controller, {@link
	 * #resolveDomain}/{@link #resolveValue}) gets it for free.
	 */
	@Transactional(readOnly = true)
	public List<ConfigPropertyValue> getDomain(ConfigDomain domain) {
		List<ConfigPropertyDefinition> definitions = registry.propertiesFor(domain);
		if (definitions.isEmpty()) {
			return List.of();
		}
		Map<String, TenantConfigEntry> persistedByKey = repository.findByConfigDomain(domain.name())
			.stream()
			.collect(Collectors.toMap(TenantConfigEntry::getConfigKey, Function.identity()));
		return definitions.stream().map(definition -> toPropertyValue(definition, persistedByKey.get(definition.key())))
			.toList();
	}

	/**
	 * Validates and persists a batch of key/value changes for {@code domain},
	 * all-or-nothing: if any key is unknown ({@link UnknownConfigKeyException})
	 * or any value fails its property's validator ({@link
	 * InvalidConfigValueException}), nothing in the batch is persisted. On
	 * success, every changed key's upsert AND its {@link AuditLogApi#record}
	 * call happen inside this one {@code @Transactional} method, so an audit
	 * write never commits without its corresponding config change (or vice
	 * versa).
	 */
	@Transactional
	public List<ConfigPropertyValue> updateDomain(ConfigDomain domain, Map<String, Object> changes) {
		if (changes == null || changes.isEmpty()) {
			return getDomain(domain);
		}

		Map<String, ConfigPropertyDefinition> definitionsByKey = registry.propertiesFor(domain)
			.stream()
			.collect(Collectors.toMap(ConfigPropertyDefinition::key, Function.identity()));

		List<FieldError> unknownKeyErrors = changes.keySet()
			.stream()
			.filter(key -> !definitionsByKey.containsKey(key))
			.map(key -> new FieldError(key, "Unknown configuration key '" + key + "' for domain " + domain))
			.toList();
		if (!unknownKeyErrors.isEmpty()) {
			throw new UnknownConfigKeyException(unknownKeyErrors);
		}

		List<FieldError> valueErrors = new ArrayList<>();
		for (Map.Entry<String, Object> change : changes.entrySet()) {
			String key = change.getKey();
			Object value = change.getValue();
			if (value == null) {
				// No "clear a value" support this wave - see class javadoc
				// deviation note in the task report; a NOT NULL jsonb column
				// has no clean "unset" representation without one.
				valueErrors.add(new FieldError(key, "Value must not be null"));
				continue;
			}
			ConfigPropertyDefinition definition = definitionsByKey.get(key);
			if (!definition.validator().test(value, changes)) {
				valueErrors.add(new FieldError(key, "Invalid value for '" + key + "'"));
			}
		}
		if (!valueErrors.isEmpty()) {
			throw new InvalidConfigValueException(valueErrors);
		}

		UUID actorId = AuthenticatedPrincipalHolder.get().userId();
		// Built with a plain loop, not Collectors.toMap - a property with no
		// default and no persisted row yet (e.g. institute_name) resolves to
		// a null value, and Collectors.toMap's accumulator rejects a null
		// value with a NullPointerException.
		Map<String, Object> beforeByKey = new LinkedHashMap<>();
		getDomain(domain).forEach(property -> beforeByKey.put(property.key(), property.value()));

		for (Map.Entry<String, Object> change : changes.entrySet()) {
			String key = change.getKey();
			Object newValue = change.getValue();
			ConfigPropertyDefinition definition = definitionsByKey.get(key);
			String serialized = serialize(newValue);

			TenantConfigEntry saved;
			Optional<TenantConfigEntry> existing = repository.findByConfigDomainAndConfigKey(domain.name(), key);
			if (existing.isPresent()) {
				existing.get().updateValue(serialized);
				saved = repository.save(existing.get());
			}
			else {
				saved = repository.save(new TenantConfigEntry(tenantContext.getTenantId(), domain.name(), key, serialized));
			}

			// AuditLogEntry's constructor calls Map.copyOf(metadata) internally,
			// which throws NullPointerException on a null value (e.g. a
			// property with no prior persisted value/no default, like
			// institute_name on first write) - so a null before/after is
			// omitted entirely rather than stored as a literal null entry.
			Map<String, Object> metadata = new LinkedHashMap<>();
			metadata.put("domain", domain.name());
			metadata.put("key", key);
			Object beforeValue = definition.sensitive() ? null : beforeByKey.get(key);
			Object afterValue = definition.sensitive() ? null : newValue;
			if (beforeValue != null) {
				metadata.put("before", beforeValue);
			}
			if (afterValue != null) {
				metadata.put("after", afterValue);
			}
			auditLogApi.record(
					new AuditLogEntry(actorId, "tenant_config.updated", "tenant_config_entry", saved.getId(), null, metadata));
		}

		return getDomain(domain);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Object> resolveValue(UUID tenantId, ConfigDomain domain, String key) {
		assertMatchesCurrentContext(tenantId);
		return getDomain(domain).stream().filter(property -> property.key().equals(key))
			.map(ConfigPropertyValue::value)
			.findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, Object> resolveDomain(UUID tenantId, ConfigDomain domain) {
		assertMatchesCurrentContext(tenantId);
		Map<String, Object> resolved = new LinkedHashMap<>();
		getDomain(domain).forEach(property -> resolved.put(property.key(), property.value()));
		return resolved;
	}

	private ConfigPropertyValue toPropertyValue(ConfigPropertyDefinition definition, TenantConfigEntry entry) {
		Object resolved = (entry != null) ? deserialize(entry.getValue()) : definition.defaultValue();
		Object exposed = definition.sensitive() ? null : resolved;
		return new ConfigPropertyValue(definition.key(), exposed, definition.valueType(), definition.defaultValue(),
				definition.sensitive());
	}

	/**
	 * {@link #resolveValue}/{@link #resolveDomain} accept an explicit {@code
	 * tenantId} per {@link TenantConfigApi}'s contract, but - since {@code
	 * TenantConfigEntryRepository} always reads through {@code
	 * TenantAwareRepositoryImpl}'s structural filter against {@code
	 * TenantContext} - the only way to honor an explicit, DIFFERENT tenantId
	 * safely would be to call {@code TenantContextHolder.set(...)}, which
	 * {@code TenantResolutionFilter}'s own javadoc documents as "the ONLY
	 * production code path allowed to call set(...)". So, as of this wave,
	 * this guard requires the passed {@code tenantId} to already equal the
	 * current request's resolved tenant (the only real caller today -
	 * {@code PublicBrandingController} - always passes exactly that). A
	 * genuine cross-context/background caller is out of scope for this wave;
	 * see the task report for this explicit deviation from the brief's
	 * "later cross-module" framing.
	 */
	private void assertMatchesCurrentContext(UUID tenantId) {
		if (tenantId == null) {
			throw new IllegalArgumentException("tenantId must not be null");
		}
		UUID current = tenantContext.getTenantId();
		if (!current.equals(tenantId)) {
			throw new IllegalStateException(
					"resolveValue/resolveDomain was called with a tenantId that does not match the current "
							+ "request's resolved TenantContext - cross-tenant/background calls are not supported yet");
		}
	}

	private Object deserialize(String json) {
		try {
			return objectMapper.readValue(json, Object.class);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("Failed to deserialize stored tenant config value", e);
		}
	}

	private String serialize(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("Failed to serialize tenant config value", e);
		}
	}

}
