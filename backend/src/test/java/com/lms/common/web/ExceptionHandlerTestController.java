package com.lms.common.web;

import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Test-only controller used solely to exercise {@link GlobalExceptionHandler}. */
@RestController
@RequestMapping("/test/exceptions")
class ExceptionHandlerTestController {

	@PostMapping("/validate")
	public void validate(@Valid @RequestBody TestRequest request) {
	}

	@PostMapping("/not-found")
	public void notFound() {
		throw new NotFoundException("widget not found");
	}

	@PostMapping("/conflict")
	public void conflict() {
		throw new ConflictException("widget already exists");
	}

	@PostMapping("/unexpected")
	public void unexpected() {
		throw new RuntimeException("boom");
	}

	@PostMapping("/unauthenticated")
	public void unauthenticated() {
		throw new BadCredentialsException("bad credentials");
	}

	@PostMapping("/forbidden")
	public void forbidden() {
		throw new AccessDeniedException("access denied");
	}

	@PostMapping("/data-integrity")
	public void dataIntegrity() {
		throw new DataIntegrityViolationException("duplicate key");
	}

	@PostMapping("/constraint-violation")
	public void constraintViolation() {
		throw new ConstraintViolationException("invalid parameter", Set.of());
	}

	@PostMapping(path = "/missing-multipart-part", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public void missingMultipartPart(@RequestPart("file") MultipartFile file) {
	}

	record TestRequest(@NotBlank String name) {
	}

}
