package com.lms.identityaccessservice.repository;

import com.lms.identityaccessservice.domain.RoleCatalogEntry;
import com.lms.identityaccessservice.domain.RoleCatalogEntry.RoleScope;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Deliberately plain {@link JpaRepository}, NOT {@code TenantAwareRepository}.
 * The {@code role} table is platform-global reference data with no
 * {@code tenant_id} column (see {@link RoleCatalogEntry}'s javadoc) - applying
 * tenant filtering here would be a misapplication of ADR-006, not a missed
 * tenancy checklist item, since this table holds zero tenant-owned rows.
 */
public interface RoleCatalogRepository extends JpaRepository<RoleCatalogEntry, String> {

	List<RoleCatalogEntry> findByScopeAndIsActiveTrueOrderBySortOrderAsc(RoleScope scope);

}
