package com.lms.integrationmanagement.repository;

import com.lms.integrationmanagement.domain.PlatformOAuthApp;
import com.lms.integrationmanagement.domain.PlatformOAuthProvider;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Platform-level (V74, VIDEO Phase 5c): {@code platform_oauth_app} holds the
 * platform's own OAuth app per provider, not tenant data, so this is a plain
 * {@link JpaRepository} - the documented exception to {@code
 * TenantAwareRepository}, like {@code PlatformAdminSessionRepository}.
 */
public interface PlatformOAuthAppRepository extends JpaRepository<PlatformOAuthApp, UUID> {

	Optional<PlatformOAuthApp> findByProvider(PlatformOAuthProvider provider);

}
