package com.lms.integrationmanagement.repository;

import com.lms.integrationmanagement.domain.PlatformOAuthAppChange;
import com.lms.integrationmanagement.domain.PlatformOAuthProvider;
import java.util.List;
import java.util.UUID;
import org.springframework.data.repository.Repository;

/**
 * Platform-level, append-only (V74 trigger): only {@code save} and reads -
 * deliberately not a {@code JpaRepository}, so no delete method exists.
 */
public interface PlatformOAuthAppChangeRepository extends Repository<PlatformOAuthAppChange, UUID> {

	PlatformOAuthAppChange save(PlatformOAuthAppChange change);

	List<PlatformOAuthAppChange> findTop20ByProviderOrderByOccurredAtDesc(PlatformOAuthProvider provider);

}
