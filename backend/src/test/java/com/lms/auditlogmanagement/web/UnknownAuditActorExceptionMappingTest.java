package com.lms.auditlogmanagement.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lms.common.config.TenantConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@link com.lms.common.web.GlobalExceptionHandler}'s generic {@code
 * ApplicationException} handler maps {@code UnknownAuditActorException}
 * (thrown by {@code AuditLogService#requireKnownActor}) end-to-end through
 * real MVC dispatch to a distinguishable {@code 500}/{@code
 * UNKNOWN_AUDIT_ACTOR} response, rather than the undifferentiated generic
 * {@code INTERNAL_ERROR} catch-all this Low finding fixed.
 *
 * <p>Deliberately lives in this domain's own test package (not alongside
 * {@code GlobalExceptionHandlerTest} in {@code com.lms.common.web}), with its
 * own {@link UnknownAuditActorExceptionMappingTestController} mirroring
 * {@code ExceptionHandlerTestController}'s exact pattern, so the
 * shared-kernel test package never needs to import a business/domain
 * module's exception type - mirrors the same "common must not depend on a
 * domain module" boundary {@code ApplicationException}'s javadoc documents
 * for the production code. {@code GlobalExceptionHandler} itself is a
 * globally-scoped {@code @RestControllerAdvice} auto-detected by {@code
 * @WebMvcTest} regardless of which package the test controller lives in, so
 * no additional wiring is needed to exercise the real handler here.
 */
@WebMvcTest(controllers = UnknownAuditActorExceptionMappingTestController.class)
@Import(TenantConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class UnknownAuditActorExceptionMappingTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void unknownAuditActorExceptionReturns500WithDistinguishableErrorCodeAndNoLeakedDetail() throws Exception {
		mockMvc.perform(post("/test/audit-exceptions/unknown-actor"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("UNKNOWN_AUDIT_ACTOR"))
			.andExpect(jsonPath("$.error.message").value(
					"Unable to record audit log entry: the acting identity could not be verified against a known user record"));
	}

}
