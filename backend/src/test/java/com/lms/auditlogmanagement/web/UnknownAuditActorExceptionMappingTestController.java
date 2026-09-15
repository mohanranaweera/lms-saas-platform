package com.lms.auditlogmanagement.web;

import com.lms.auditlogmanagement.service.UnknownAuditActorException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller used solely by {@link UnknownAuditActorExceptionMappingTest}
 * to exercise {@link com.lms.common.web.GlobalExceptionHandler}'s mapping of
 * {@link UnknownAuditActorException}. Mirrors {@code ExceptionHandlerTestController}'s
 * pattern (a top-level, package-private controller - {@code @WebMvcTest}'s
 * {@code controllers} attribute does not reliably pick up a nested static
 * inner class).
 */
@RestController
@RequestMapping("/test/audit-exceptions")
class UnknownAuditActorExceptionMappingTestController {

	@PostMapping("/unknown-actor")
	public void unknownActor() {
		throw new UnknownAuditActorException();
	}

}
