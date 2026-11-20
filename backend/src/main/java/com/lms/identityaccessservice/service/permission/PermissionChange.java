package com.lms.identityaccessservice.service.permission;

/** One requested cell change of the permission matrix (validated by {@link RolePermissionMatrixService}). */
public record PermissionChange(String roleCode, String capabilityKey, Boolean granted) {

}
