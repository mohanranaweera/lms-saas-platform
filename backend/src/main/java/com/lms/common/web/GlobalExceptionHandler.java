package com.lms.common.web;

import com.lms.common.api.ApiError;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.FieldError;
import com.lms.common.error.ApplicationException;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Maps every exception type thrown from a controller/service to the common
 * {@link ApiResponse} envelope. This is the single place HTTP status codes
 * are decided for error responses - controllers never set status directly.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		List<FieldError> fieldErrors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(fe -> new FieldError(fe.getField(), fe.getDefaultMessage()))
			.toList();
		ApiError error = ApiError.validation("Request validation failed", fieldErrors);
		return ResponseEntity.badRequest().body(ApiResponse.error(error));
	}

	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
		List<FieldError> fieldErrors = ex.getConstraintViolations()
			.stream()
			.map(cv -> new FieldError(cv.getPropertyPath().toString(), cv.getMessage()))
			.toList();
		ApiError error = ApiError.validation("Request validation failed", fieldErrors);
		return ResponseEntity.badRequest().body(ApiResponse.error(error));
	}

	/**
	 * A path variable that fails type conversion (e.g. a non-UUID string
	 * where {@code @PathVariable UUID id} is declared) previously fell
	 * through to {@link #handleUnexpected}'s generic {@code 500} - this
	 * mapped it to a proper {@code 400} instead. Found during MVP-008's
	 * backend review; the shared handler had no case for it because no
	 * earlier module took as many multi-segment UUID path variables as
	 * course/module/lesson routes do.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		ApiError error = ApiError.of(ApiErrorCodes.VALIDATION_ERROR, "Request parameter '" + ex.getName()
				+ "' has an invalid value");
		return ResponseEntity.badRequest().body(ApiResponse.error(error));
	}

	/**
	 * A request body that fails to deserialize at all (e.g. an invalid
	 * enum value for a field like {@code CourseCreateRequest.status}) fails
	 * before Bean Validation ever runs, and previously fell through to
	 * {@link #handleUnexpected}'s generic {@code 500} - this maps it to a
	 * proper {@code 400} instead. Found during MVP-008's backend review.
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException ex) {
		ApiError error = ApiError.of(ApiErrorCodes.VALIDATION_ERROR, "Request body is malformed or contains an invalid value");
		return ResponseEntity.badRequest().body(ApiResponse.error(error));
	}

	@ExceptionHandler(NotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiResponse.error(ApiError.of(ApiErrorCodes.NOT_FOUND, ex.getMessage())));
	}

	@ExceptionHandler(ConflictException.class)
	public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiError.of(ApiErrorCodes.CONFLICT, ex.getMessage())));
	}

	/**
	 * Generic fallback for any {@link ApplicationException} subclass that does
	 * not have its own dedicated handler above (e.g. identity-access-service's
	 * auth-specific exceptions) - deliberately generic so this class never
	 * needs to import a business/domain module's exception types (see
	 * {@link ApplicationException}'s javadoc).
	 */
	@ExceptionHandler(ApplicationException.class)
	public ResponseEntity<ApiResponse<Void>> handleApplicationException(ApplicationException ex) {
		return ResponseEntity.status(ex.getHttpStatus())
			.body(ApiResponse.error(ApiError.of(ex.getErrorCode(), ex.getMessage())));
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
		log.warn("Data integrity violation", ex);
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiResponse.error(ApiError.of(ApiErrorCodes.CONFLICT, "The request conflicts with existing data")));
	}

	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<ApiResponse<Void>> handleAuthentication(AuthenticationException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(ApiResponse.error(ApiError.of(ApiErrorCodes.UNAUTHENTICATED, "Authentication is required")));
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(ApiResponse.error(ApiError.of(ApiErrorCodes.FORBIDDEN, "You do not have permission to perform this action")));
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
		log.error("Unhandled exception", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(ApiResponse.error(ApiError.of(ApiErrorCodes.INTERNAL_ERROR, "An unexpected error occurred")));
	}

}
