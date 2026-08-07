package com.lms.common.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.AuditorAware;

/**
 * Auditor source for {@code @CreatedBy}/{@code @LastModifiedBy}. Delegates to
 * an optional {@link CurrentActorProvider} (supplied by identity-access-service
 * in the real application context - see {@code AuthenticatedActorProvider})
 * rather than depending on it directly, so the shared kernel stays free of any
 * business-module dependency. Resolves to empty when no provider is
 * registered (test slices that don't load identity-access-service) or when
 * the current request has no authenticated actor (unauthenticated/system
 * writes) - a deliberate empty result, not a fabricated id.
 */
public class AuditorAwareImpl implements AuditorAware<UUID> {

	private final ObjectProvider<CurrentActorProvider> actorProvider;

	public AuditorAwareImpl(ObjectProvider<CurrentActorProvider> actorProvider) {
		this.actorProvider = actorProvider;
	}

	@Override
	public Optional<UUID> getCurrentAuditor() {
		CurrentActorProvider provider = actorProvider.getIfAvailable();
		return provider == null ? Optional.empty() : provider.currentActorId();
	}

}
