package com.lms.identityaccessservice.web;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.api.RoleSummary;
import com.lms.identityaccessservice.domain.RoleCatalogEntry;
import com.lms.identityaccessservice.domain.RoleCatalogEntry.RoleScope;
import com.lms.identityaccessservice.repository.RoleCatalogRepository;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RBAC-1's only new REST surface: a read-only listing of tenant-assignable
 * roles (backing a future "assign role" dropdown, e.g. Module 5 Staff
 * Management). Thin controller reading directly from
 * {@link RoleCatalogRepository} and mapping to {@link RoleSummary} - a
 * trivial read-only listing at this size does not warrant a dedicated
 * service-layer indirection, per {@code backend/CLAUDE.md}'s "keep
 * controllers thin, business logic in services" guidance (there is no
 * business logic here beyond a fixed filter + DTO mapping).
 */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleCatalogController {

	private final RoleCatalogRepository roleCatalogRepository;

	public RoleCatalogController(RoleCatalogRepository roleCatalogRepository) {
		this.roleCatalogRepository = roleCatalogRepository;
	}

	@GetMapping
	@PreAuthorize("@permissionCheckService.hasPermission('STAFF_AND_ROLES', 'VIEW')")
	public ApiResponse<List<RoleSummary>> listAssignableRoles() {
		List<RoleSummary> roles = roleCatalogRepository
			.findByScopeAndIsActiveTrueOrderBySortOrderAsc(RoleScope.TENANT)
			.stream()
			.map(RoleCatalogController::toSummary)
			.toList();
		return ApiResponse.success(roles);
	}

	private static RoleSummary toSummary(RoleCatalogEntry entry) {
		return new RoleSummary(entry.getCode(), entry.getScope().name(), entry.getDisplayName(),
				entry.getDescription(), entry.getPortalRouteGroup(), entry.isSelfRegisters(), entry.isProvisional());
	}

}
