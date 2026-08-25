package com.lms.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lms.common.config.TenantConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves {@link GlobalExceptionHandler} wiring through real MVC dispatch
 * (not just direct method calls). Security filters are disabled here - this
 * test is about exception-to-response mapping, not authorization; the
 * platform's baseline security posture is covered separately.
 *
 * {@link TenantConfig} is imported explicitly because {@code @WebMvcTest}'s
 * slice excludes plain {@code @Configuration} beans that aren't web-layer
 * -recognized types, but {@link CorrelationIdFilter} (a {@code Filter},
 * which the slice does include) needs a {@code TenantContext} bean to wire.
 */
@WebMvcTest(controllers = ExceptionHandlerTestController.class)
@Import(TenantConfig.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void validationFailureReturns400WithFieldErrors() throws Exception {
		mockMvc
			.perform(post("/test/exceptions/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.error.fieldErrors[0].field").value("name"));
	}

	@Test
	void notFoundReturns404() throws Exception {
		mockMvc.perform(post("/test/exceptions/not-found"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
	}

	@Test
	void conflictReturns409() throws Exception {
		mockMvc.perform(post("/test/exceptions/conflict"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CONFLICT"));
	}

	@Test
	void unexpectedExceptionReturns500WithoutLeakingDetails() throws Exception {
		mockMvc.perform(post("/test/exceptions/unexpected"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.error.message").value("An unexpected error occurred"));
	}

	@Test
	void authenticationExceptionReturns401() throws Exception {
		mockMvc.perform(post("/test/exceptions/unauthenticated"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
	}

	@Test
	void accessDeniedExceptionReturns403() throws Exception {
		mockMvc.perform(post("/test/exceptions/forbidden"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
	}

	@Test
	void dataIntegrityViolationReturns409() throws Exception {
		mockMvc.perform(post("/test/exceptions/data-integrity"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error.code").value("CONFLICT"));
	}

	@Test
	void constraintViolationReturns400() throws Exception {
		mockMvc.perform(post("/test/exceptions/constraint-violation"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

	/**
	 * A multipart request that omits the required {@code file} part must map
	 * to a clean {@code 400} (Fix L1), not the generic {@code 500} it fell
	 * through to before {@link GlobalExceptionHandler#handleMissingServletRequestPart}
	 * existed - this is shared infrastructure exercised here directly, not
	 * re-verified per module (content-management's material upload,
	 * payment-management's slip upload).
	 */
	@Test
	void missingRequiredMultipartPartReturns400NotAGeneric500() throws Exception {
		mockMvc.perform(multipart("/test/exceptions/missing-multipart-part"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.success").value(false))
			.andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
	}

}
