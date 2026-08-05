package com.lms.common.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.AuditorAware;

/**
 * Placeholder auditor source: identity-access-service does not exist yet, so
 * there is no authenticated actor to attribute changes to. Returns empty
 * rather than fabricating an id, so the gap stays visible in the data
 * (null created_by/updated_by) instead of being silently papered over.
 */
public class AuditorAwareImpl implements AuditorAware<UUID> {

	@Override
	public Optional<UUID> getCurrentAuditor() {
		return Optional.empty();
	}

}
