package com.lms.common.config;

import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextNotResolvedException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates a correlation id (and tenant id, once resolved) into MDC for
 * every request, so structured logs can be tied back to a specific request
 * or reported by a client. Never place Authorization headers, tokens, or
 * request bodies into MDC here or anywhere else - see
 * docs/architecture/observability.md.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

	private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

	private static final String MDC_CORRELATION_ID = "correlationId";

	private static final String MDC_TENANT_ID = "tenantId";

	private final TenantContext tenantContext;

	public CorrelationIdFilter(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		String correlationId = request.getHeader(CORRELATION_ID_HEADER);
		if (correlationId == null || correlationId.isBlank()) {
			correlationId = UUID.randomUUID().toString();
		}
		try {
			MDC.put(MDC_CORRELATION_ID, correlationId);
			putTenantIdIfResolved();
			response.setHeader(CORRELATION_ID_HEADER, correlationId);
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove(MDC_CORRELATION_ID);
			MDC.remove(MDC_TENANT_ID);
		}
	}

	private void putTenantIdIfResolved() {
		try {
			MDC.put(MDC_TENANT_ID, tenantContext.getTenantId().toString());
		}
		catch (TenantContextNotResolvedException notResolved) {
			// Expected until identity-access-service resolves a tenant for this request.
		}
	}

}
