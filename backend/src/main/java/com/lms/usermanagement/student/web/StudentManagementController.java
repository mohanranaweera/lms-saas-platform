package com.lms.usermanagement.student.web;

import com.lms.identityaccessservice.api.RequiresCapability;
import com.lms.identityaccessservice.api.Capability;
import com.lms.common.api.ApiResponse;
import com.lms.common.api.PageResponse;
import com.lms.common.qr.QrCodes;
import com.lms.contentmanagement.api.StudentProfileMediaApi.SignedImage;
import com.lms.usermanagement.student.service.StudentDirectoryService;
import com.lms.usermanagement.student.service.StudentDirectoryService.Field;
import com.lms.usermanagement.student.service.StudentDirectoryService.Tab;
import com.lms.usermanagement.student.service.StudentImpersonationService;
import com.lms.usermanagement.student.service.StudentManagementService;
import com.lms.usermanagement.student.service.StudentManagementService.QrView;
import com.lms.usermanagement.student.service.StudentManagementService.ResolvedStudent;
import com.lms.usermanagement.student.service.StudentPhotoService;
import com.lms.usermanagement.student.service.StudentRemarkService;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.ImpersonatedStudent;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.ImpersonationStartRequest;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.ImpersonationStartResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.PhotoUrlResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.QrResolveResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.QrResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.QrRotateResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.RemarkCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.RemarkResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentCreatedResponse;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentDetail;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentDirectoryRow;
import com.lms.usermanagement.student.web.dto.StudentManagementDtos.StudentRemovalRequest;
import com.lms.usermanagement.student.web.dto.StudentRegistrationForm;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * STU-MGMT-02 Klass-parity student management endpoints (spec section 5). Thin: every
 * rule lives in the services, which re-check permissions. Tenant comes from the
 * resolved auth context only. The QR label PDF ({@code POST /students/qr-export}) is
 * served by reporting-analytics' {@code StudentQrLabelPdfController}.
 */
@RestController
@RequestMapping("/api/v1/students")
public class StudentManagementController {

	private static final int QR_PIXELS_PER_MODULE = 10;

	private final StudentDirectoryService directoryService;

	private final StudentManagementService managementService;

	private final StudentPhotoService photoService;

	private final StudentRemarkService remarkService;

	private final StudentImpersonationService impersonationService;

	public StudentManagementController(StudentDirectoryService directoryService,
			StudentManagementService managementService, StudentPhotoService photoService,
			StudentRemarkService remarkService, StudentImpersonationService impersonationService) {
		this.directoryService = directoryService;
		this.managementService = managementService;
		this.photoService = photoService;
		this.remarkService = remarkService;
		this.impersonationService = impersonationService;
	}

	// ---- Directory ------------------------------------------------------

	@GetMapping("/directory")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	@RequiresCapability(Capability.STUDENTS_VIEW)
	public ResponseEntity<ApiResponse<PageResponse<StudentDirectoryRow>>> directory(
			@RequestParam(defaultValue = "ACTIVE") String tab, @RequestParam(defaultValue = "ALL") String field,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ResponseEntity
			.ok(ApiResponse.success(directoryService.directory(tab(tab), field(field), q, page, size)));
	}

	@GetMapping("/directory/export.csv")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	@RequiresCapability(Capability.STUDENTS_EXPORT_CSV)
	public ResponseEntity<byte[]> exportCsv(@RequestParam(defaultValue = "ACTIVE") String tab,
			@RequestParam(defaultValue = "ALL") String field, @RequestParam(required = false) String q) {
		byte[] csv = directoryService.exportCsv(tab(tab), field(field), q);
		return ResponseEntity.ok()
			.contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.CONTENT_DISPOSITION,
					ContentDisposition.attachment().filename("students.csv").build().toString())
			.body(csv);
	}

	// ---- Create / detail / edit / approve / remove -----------------------

	@PostMapping("/v2")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	@RequiresCapability(Capability.STUDENTS_ADD)
	public ResponseEntity<ApiResponse<StudentCreatedResponse>> create(@Valid @RequestBody StudentRegistrationForm form) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(managementService.create(form)));
	}

	@GetMapping("/{id}/detail")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	@RequiresCapability(Capability.STUDENTS_VIEW_PROFILE)
	public ResponseEntity<ApiResponse<StudentDetail>> detail(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(managementService.detail(id)));
	}

	@PutMapping("/{id}/detail")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	@RequiresCapability(Capability.STUDENTS_EDIT)
	public ResponseEntity<ApiResponse<StudentDetail>> update(@PathVariable UUID id,
			@Valid @RequestBody StudentRegistrationForm form) {
		return ResponseEntity.ok(ApiResponse.success(managementService.update(id, form)));
	}

	@PostMapping("/{id}/approve")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	@RequiresCapability(Capability.STUDENTS_APPROVE_PENDING)
	public ResponseEntity<ApiResponse<StudentDetail>> approve(@PathVariable UUID id) {
		return ResponseEntity.ok(ApiResponse.success(managementService.approve(id)));
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'DELETE')")
	@RequiresCapability(Capability.STUDENTS_DELETE)
	public ResponseEntity<Void> remove(@PathVariable UUID id, @Valid @RequestBody StudentRemovalRequest request) {
		managementService.remove(id, request.reason());
		return ResponseEntity.noContent().build();
	}

	// ---- Photo ------------------------------------------------------------

	@PostMapping(path = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	@RequiresCapability(Capability.STUDENTS_EDIT)
	public ResponseEntity<ApiResponse<StudentDetail>> uploadPhoto(@PathVariable UUID id,
			@RequestPart("file") MultipartFile file) {
		return ResponseEntity.ok(ApiResponse.success(photoService.uploadForStudent(id, file)));
	}

	@DeleteMapping("/{id}/photo")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	@RequiresCapability(Capability.STUDENTS_EDIT)
	public ResponseEntity<Void> removePhoto(@PathVariable UUID id) {
		photoService.removeForStudent(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/{id}/photo")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	@RequiresCapability(Capability.STUDENTS_VIEW_PROFILE)
	public ResponseEntity<ApiResponse<PhotoUrlResponse>> photo(@PathVariable UUID id) {
		return photoResponse(photoService.urlForStudent(id));
	}

	@PostMapping(path = "/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PhotoUrlResponse>> uploadOwnPhoto(@RequestPart("file") MultipartFile file) {
		return photoResponse(photoService.uploadOwn(file));
	}

	@DeleteMapping("/me/photo")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<Void> removeOwnPhoto() {
		photoService.removeOwn();
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me/photo")
	@PreAuthorize("hasRole('STUDENT')")
	public ResponseEntity<ApiResponse<PhotoUrlResponse>> ownPhoto() {
		return photoResponse(photoService.urlOwn());
	}

	// ---- Remarks ----------------------------------------------------------

	@GetMapping("/{id}/remarks")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	@RequiresCapability(Capability.STUDENTS_VIEW_PROFILE)
	public ResponseEntity<ApiResponse<PageResponse<RemarkResponse>>> remarks(@PathVariable UUID id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ResponseEntity.ok(ApiResponse.success(remarkService.list(id, from, to, page, size)));
	}

	@PostMapping("/{id}/remarks")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	@RequiresCapability(Capability.STUDENTS_EDIT)
	public ResponseEntity<ApiResponse<RemarkResponse>> addRemark(@PathVariable UUID id,
			@Valid @RequestBody RemarkCreateRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(remarkService.add(id, request.body())));
	}

	// ---- QR -----------------------------------------------------------------

	@GetMapping("/{id}/qr")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	public ResponseEntity<ApiResponse<QrResponse>> qr(@PathVariable UUID id) {
		QrView view = managementService.qr(id);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(new QrResponse(view.payload(), view.rotatedAt())));
	}

	@GetMapping(path = "/{id}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	public ResponseEntity<byte[]> qrPng(@PathVariable UUID id) {
		QrView view = managementService.qr(id);
		return ResponseEntity.ok()
			.contentType(MediaType.IMAGE_PNG)
			.cacheControl(CacheControl.noStore())
			.body(QrCodes.png(view.payload(), QR_PIXELS_PER_MODULE));
	}

	@PostMapping("/{id}/qr/rotate")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<QrRotateResponse>> rotateQr(@PathVariable UUID id) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(new QrRotateResponse(managementService.rotateQr(id).payload())));
	}

	@GetMapping("/qr/resolve")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', 'VIEW')")
	public ResponseEntity<ApiResponse<QrResolveResponse>> resolveQr(@RequestParam String payload) {
		ResolvedStudent student = managementService.resolveQr(payload);
		return ResponseEntity.ok(ApiResponse
			.success(new QrResolveResponse(student.id(), student.indexNumber(), student.fullName())));
	}

	// ---- Log as Student (ADR-033) -----------------------------------------

	@PostMapping("/{id}/impersonation")
	@PreAuthorize("@permissionCheckService.hasPermission('STUDENT_IMPERSONATION', 'CREATE_EDIT')")
	public ResponseEntity<ApiResponse<ImpersonationStartResponse>> startImpersonation(@PathVariable UUID id,
			@Valid @RequestBody ImpersonationStartRequest request) {
		StudentImpersonationService.Started started = impersonationService.start(id, request.reason());
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.header(HttpHeaders.SET_COOKIE, started.session().setCookieHeader())
			.body(ApiResponse.success(new ImpersonationStartResponse(started.session().sessionId(),
					started.session().expiresAt(),
					new ImpersonatedStudent(started.studentId(), started.indexNumber(), started.fullName()))));
	}

	// ---- Helpers ------------------------------------------------------------

	private static ResponseEntity<ApiResponse<PhotoUrlResponse>> photoResponse(SignedImage image) {
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(ApiResponse.success(new PhotoUrlResponse(image.url(), image.expiresAt())));
	}

	private static Tab tab(String raw) {
		try {
			return Tab.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new com.lms.common.error.FieldValidationException("Unknown tab",
					java.util.List.of(new com.lms.common.api.FieldError("tab", "Use ACTIVE or PENDING")));
		}
	}

	private static Field field(String raw) {
		try {
			return Field.valueOf(raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new com.lms.common.error.FieldValidationException("Unknown search field",
					java.util.List.of(new com.lms.common.api.FieldError("field",
							"Use ALL, INDEX, FIRST_NAME, LAST_NAME, EMAIL or MOBILE")));
		}
	}

}
