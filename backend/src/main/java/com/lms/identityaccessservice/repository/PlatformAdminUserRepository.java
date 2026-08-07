package com.lms.identityaccessservice.repository;

import com.lms.identityaccessservice.domain.PlatformAdminUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Platform-level - {@code platform_admin_user} is never tenant-scoped. */
public interface PlatformAdminUserRepository extends JpaRepository<PlatformAdminUser, UUID> {

	Optional<PlatformAdminUser> findByEmail(String email);

}
