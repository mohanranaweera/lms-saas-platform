package com.lms.identityaccessservice.config;

import com.lms.common.persistence.CurrentActorProvider;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Supplies {@link com.lms.common.persistence.AuditorAwareImpl} with the
 * current request's authenticated actor id, closing the placeholder {@code
 * com.lms.common.persistence.AuditorAwareImpl} was left waiting on since
 * Application Foundation. Reads {@link AuthenticatedPrincipalHolder}, which is
 * only ever populated by {@code JwtAuthenticationFilter} on an authenticated
 * request - empty for unauthenticated/system writes (migrations, scheduled
 * jobs), which is a correct empty result, not a bug.
 */
@Component
public class AuthenticatedActorProvider implements CurrentActorProvider {

	@Override
	public Optional<UUID> currentActorId() {
		if (!AuthenticatedPrincipalHolder.isSet()) {
			return Optional.empty();
		}
		return Optional.of(AuthenticatedPrincipalHolder.get().userId());
	}

}
