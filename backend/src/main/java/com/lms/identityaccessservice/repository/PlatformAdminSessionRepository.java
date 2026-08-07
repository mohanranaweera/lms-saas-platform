package com.lms.identityaccessservice.repository;

import com.lms.identityaccessservice.domain.PlatformAdminSession;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Platform-level - {@code platform_admin_session} is never tenant-scoped. */
public interface PlatformAdminSessionRepository extends JpaRepository<PlatformAdminSession, UUID> {

	Optional<PlatformAdminSession> findByRefreshTokenHash(String refreshTokenHash);

}
