package com.lms.identityaccessservice.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller used solely to exercise RBAC-2's server-side
 * permission enforcement mechanism through the real Spring Security filter
 * chain. No real Module 4+ domain controller exists yet - this fixture
 * stands in for one, shaped like a real domain controller with one endpoint
 * per {@code PermissionAction} (VIEW/CREATE_EDIT/DELETE/APPROVE), each gated
 * by {@code @PreAuthorize} calling {@code PermissionCheckService}'s
 * String-typed overload with a path-variable domain area.
 */
@RestController
@RequestMapping("/test/permission-check")
class PermissionCheckTestController {

	@GetMapping("/{domainArea}")
	@PreAuthorize("@permissionCheckService.hasPermission(#domainArea, 'VIEW')")
	public void view(@PathVariable String domainArea) {
	}

	@PostMapping("/{domainArea}")
	@PreAuthorize("@permissionCheckService.hasPermission(#domainArea, 'CREATE_EDIT')")
	public void createEdit(@PathVariable String domainArea) {
	}

	@DeleteMapping("/{domainArea}/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission(#domainArea, 'DELETE')")
	public void delete(@PathVariable String domainArea, @PathVariable String id) {
	}

	@PostMapping("/{domainArea}/{id}/approve")
	@PreAuthorize("@permissionCheckService.hasPermission(#domainArea, 'APPROVE')")
	public void approve(@PathVariable String domainArea, @PathVariable String id) {
	}

}
