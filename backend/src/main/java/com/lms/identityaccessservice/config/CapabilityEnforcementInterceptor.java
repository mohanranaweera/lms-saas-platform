package com.lms.identityaccessservice.config;

import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.service.CapabilityElevationHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * STAFF-MGMT-02 §2.3: enforces {@link RequiresCapability} on {@code /api/**} handlers. Runs in
 * the DispatcherServlet before the handler (and therefore before the controller's
 * {@code @PreAuthorize} proxy):
 * <ul>
 * <li>OPERATOR/MANAGER: no tenant grant -&gt; 403; granted -&gt; the capability's implied pairs
 * are elevated on this thread for this request only;
 * <li>TEACHER: a revoked teacher-configurable capability -&gt; 403 (never elevates);
 * <li>every other role: no-op.
 * </ul>
 * The elevation is cleared at the start of every request and in {@link #afterCompletion}, which
 * Spring calls whenever {@code preHandle} returned {@code true} (including when the handler
 * throws), so it never leaks to the next request served by this pooled thread.
 */
public class CapabilityEnforcementInterceptor implements HandlerInterceptor {

	private static final Set<String> CONFIGURABLE_STAFF_ROLES = Set.of("OPERATOR", "MANAGER");

	private final PermissionCheckService permissionCheckService;

	public CapabilityEnforcementInterceptor(PermissionCheckService permissionCheckService) {
		this.permissionCheckService = permissionCheckService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		CapabilityElevationHolder.clear();
		if (!(handler instanceof HandlerMethod handlerMethod)) {
			return true;
		}
		RequiresCapability annotation = findAnnotation(handlerMethod);
		if (annotation == null || !AuthenticatedPrincipalHolder.isSet()) {
			// Unauthenticated requests are handled by the security chain / @PreAuthorize.
			return true;
		}
		Capability capability = annotation.value();
		String role = AuthenticatedPrincipalHolder.get().role();
		if (role != null && CONFIGURABLE_STAFF_ROLES.contains(role)) {
			if (!permissionCheckService.hasCapability(capability)) {
				throw new AccessDeniedException("You do not have permission to perform this action");
			}
			CapabilityElevationHolder.set(capability.implied());
		}
		else if ("TEACHER".equals(role) && capability.isTeacherConfigurable()
				&& !permissionCheckService.hasCapability(capability)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		return true;
	}

	@Override
	public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
			Exception ex) {
		CapabilityElevationHolder.clear();
	}

	private static RequiresCapability findAnnotation(HandlerMethod handlerMethod) {
		RequiresCapability onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(),
				RequiresCapability.class);
		if (onMethod != null) {
			return onMethod;
		}
		return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequiresCapability.class);
	}

}
