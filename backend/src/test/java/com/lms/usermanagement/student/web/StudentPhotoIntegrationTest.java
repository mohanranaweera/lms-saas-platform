package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi;
import com.lms.usermanagement.student.StudentManagementTestSupport;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STU-MGMT-02 profile photos (spec section 6): staff and self-service upload / replace /
 * remove, server-side validation (content sniffing, full decode, size and dimension
 * limits) with no partial write, normalisation to a 512x512 metadata-free JPEG with the
 * EXIF orientation applied, signed-URL reads, the photo never listed in the Media
 * Library, and cross-tenant / cross-student denial.
 */
class StudentPhotoIntegrationTest extends StudentManagementTestSupport {

	@Autowired
	private InMemoryObjectStorageApi storage;

	/** Left half red, right half blue. */
	private static BufferedImage halves(int width, int height) {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = image.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, width / 2, height);
		g.setColor(Color.BLUE);
		g.fillRect(width / 2, 0, width - width / 2, height);
		g.dispose();
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, format, out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Inserts an EXIF APP1 segment (big-endian TIFF, IFD0 with Orientation = value) after SOI. */
	private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
		byte[] app1 = { (byte) 0xFF, (byte) 0xE1, 0, 34, 'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1,
				0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, 0, 0, 0, 0 };
		byte[] out = new byte[jpeg.length + app1.length];
		System.arraycopy(jpeg, 0, out, 0, 2);
		System.arraycopy(app1, 0, out, 2, app1.length);
		System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
		return out;
	}

	private static MockMultipartFile file(String name, String type, byte[] bytes) {
		return new MockMultipartFile("file", name, type, bytes);
	}

	private BufferedImage storedImage(UUID mediaFileId) {
		String key = jdbcTemplate.queryForObject("SELECT object_key FROM media_file WHERE id = ?", String.class,
				mediaFileId);
		try {
			return ImageIO.read(new ByteArrayInputStream(storage.bytes(key)));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private UUID photoIdOf(UUID studentId) {
		return jdbcTemplate.queryForObject("SELECT profile_media_file_id FROM student_profile WHERE id = ?", UUID.class,
				studentId);
	}

	private static boolean reddish(int rgb) {
		Color c = new Color(rgb);
		return c.getRed() > 180 && c.getBlue() < 80;
	}

	private static boolean bluish(int rgb) {
		Color c = new Color(rgb);
		return c.getBlue() > 180 && c.getRed() < 80;
	}

	@Test
	void staffUploadNormalisesReplacesAndRemovesThePhoto() {
		Fx fx = fixture("stu-photo");
		UUID id = createStudentId(fx, "Photo", "Student");

		MvcResult uploaded = upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo",
				file("me.png", "image/png", encode(halves(300, 200), "png")), id);
		assertThat(status(uploaded)).as(rawContent(uploaded)).isEqualTo(200);
		assertThat(data(uploaded).path("photoUrl").asString()).startsWith("https://test-object-storage.invalid/");
		UUID first = photoIdOf(id);
		assertThat(first).isNotNull();
		assertThat(jdbcTemplate.queryForObject("SELECT usage_area || '|' || content_type FROM media_file WHERE id = ?",
				String.class, first)).isEqualTo("STUDENT_PROFILE|image/jpeg");
		BufferedImage stored = storedImage(first);
		assertThat(stored.getWidth()).isEqualTo(512);
		assertThat(stored.getHeight()).isEqualTo(512);
		// Centre crop of a 300x200 image keeps x 50..250: red left, blue right.
		assertThat(reddish(stored.getRGB(100, 256))).isTrue();
		assertThat(bluish(stored.getRGB(400, 256))).isTrue();

		// Signed URL read + directory flag; never listed in the Media Library.
		MvcResult url = getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo", id);
		assertThat(status(url)).isEqualTo(200);
		assertThat(data(url).path("url").asString()).contains(jdbcTemplate
			.queryForObject("SELECT object_key FROM media_file WHERE id = ?", String.class, first));
		assertThat(data(url).path("expiresAt").isNull()).isFalse();
		assertThat(data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory")).path("content").get(0)
			.path("hasPhoto").asBoolean()).isTrue();
		MvcResult library = getAs(fx.host(), fx.adminToken(), "/api/v1/media-files");
		assertThat(status(library)).isEqualTo(200);
		assertThat(rawContent(library)).doesNotContain(first.toString());

		// Replace with a JPEG carrying EXIF orientation 6 (rotate 90 degrees clockwise).
		byte[] rotated = withExifOrientation(encode(halves(200, 200), "jpg"), 6);
		MvcResult replaced = upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo",
				file("rot.jpg", "image/jpeg", rotated), id);
		assertThat(status(replaced)).as(rawContent(replaced)).isEqualTo(200);
		UUID second = photoIdOf(id);
		assertThat(second).isNotEqualTo(first);
		BufferedImage upright = storedImage(second);
		// Left (red) half moved to the top.
		assertThat(reddish(upright.getRGB(256, 100))).isTrue();
		assertThat(bluish(upright.getRGB(256, 400))).isTrue();
		// The previous, now unreferenced file is discarded (soft delete, object removed after commit).
		assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM media_file WHERE id = ?",
				Boolean.class, first)).isTrue();

		// Remove.
		MvcResult removed = send(fx.host(), fx.adminToken(), delete("/api/v1/students/{id}/photo", id), null);
		assertThat(status(removed)).isEqualTo(204);
		assertThat(photoIdOf(id)).isNull();
		assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM media_file WHERE id = ?",
				Boolean.class, second)).isTrue();
		assertThat(status(getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo", id))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND target_id = ? "
				+ "AND action IN ('student.photo_updated', 'student.photo_removed')", fx.tenant().getId(), id))
			.isEqualTo(3);
	}

	@Test
	void invalidUploadsAreRejectedWithNoPartialWrite() {
		Fx fx = fixture("stu-photo-bad");
		UUID id = createStudentId(fx, "Bad", "Upload");
		Set<String> keysBefore = new HashSet<>(storage.keySet());
		long rowsBefore = count("SELECT count(*) FROM media_file WHERE tenant_id = ?", fx.tenant().getId());

		// A PDF named and typed as PNG.
		assertThat(status(upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo",
				file("x.png", "image/png", "%PDF-1.7 not an image".getBytes()), id))).isEqualTo(415);
		// PNG magic bytes but not a decodable image.
		byte[] fakePng = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 1, 2, 3 };
		assertThat(status(upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo",
				file("y.png", "image/png", fakePng), id))).isEqualTo(415);
		// GIF is not accepted for photos.
		assertThat(status(upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo",
				file("z.gif", "image/gif", encode(halves(100, 100), "gif")), id))).isEqualTo(415);
		// Too small.
		assertThat(status(upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo",
				file("tiny.png", "image/png", encode(halves(32, 32), "png")), id))).isEqualTo(400);
		// Over 2 MB.
		byte[] big = new byte[2 * 1024 * 1024 + 10];
		byte[] png = encode(halves(100, 100), "png");
		System.arraycopy(png, 0, big, 0, png.length);
		assertThat(status(upload(fx.host(), fx.adminToken(), "/api/v1/students/{id}/photo",
				file("big.png", "image/png", big), id))).isEqualTo(413);

		assertThat(storage.keySet()).isEqualTo(keysBefore);
		assertThat(count("SELECT count(*) FROM media_file WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(rowsBefore);
		assertThat(photoIdOf(id)).isNull();
	}

	@Test
	void ownPhotoIsSelfServiceOnlyAndOtherTenantsAndStudentsAreDenied() {
		Fx fx = fixture("stu-photo-me");
		UUID me = createStudentId(fx, "Self", "Service");
		UUID other = createStudentId(fx, "Other", "Student");
		String myToken = studentToken(fx, me);
		byte[] png = encode(halves(120, 120), "png");

		MvcResult own = upload(fx.host(), myToken, "/api/v1/students/me/photo", file("me.png", "image/png", png));
		assertThat(status(own)).as(rawContent(own)).isEqualTo(200);
		assertThat(data(own).path("url").asString()).isNotBlank();
		assertThat(photoIdOf(me)).isNotNull();
		assertThat(photoIdOf(other)).isNull();
		assertThat(status(getAs(fx.host(), myToken, "/api/v1/students/me/photo"))).isEqualTo(200);

		// A student cannot use the staff paths for anyone (including themself).
		assertThat(status(upload(fx.host(), myToken, "/api/v1/students/{id}/photo", file("x.png", "image/png", png),
				other))).isEqualTo(403);
		assertThat(status(getAs(fx.host(), myToken, "/api/v1/students/{id}/photo", me))).isEqualTo(403);
		assertThat(status(send(fx.host(), myToken, delete("/api/v1/students/{id}/photo", other), null)))
			.isEqualTo(403);
		// Staff cannot use /me.
		assertThat(status(getAs(fx.host(), fx.adminToken(), "/api/v1/students/me/photo"))).isEqualTo(403);

		// Another tenant's admin cannot read or write this tenant's photos.
		Fx foreign = fixture("stu-photo-me-other");
		assertThat(status(getAs(foreign.host(), foreign.adminToken(), "/api/v1/students/{id}/photo", me)))
			.isEqualTo(404);
		assertThat(status(upload(foreign.host(), foreign.adminToken(), "/api/v1/students/{id}/photo",
				file("x.png", "image/png", png), me))).isEqualTo(404);
		assertThat(status(send(foreign.host(), foreign.adminToken(), delete("/api/v1/students/{id}/photo", me), null)))
			.isEqualTo(404);
		assertThat(status(getAs(fx.host(), null, "/api/v1/students/{id}/photo", me))).isEqualTo(401);

		JsonNode afterForeign = data(getAs(fx.host(), fx.adminToken(), "/api/v1/students/{id}/detail", me));
		assertThat(afterForeign.path("photoUrl").asString()).isNotBlank();

		MvcResult removed = send(fx.host(), myToken, delete("/api/v1/students/me/photo"), null);
		assertThat(status(removed)).isEqualTo(204);
		assertThat(photoIdOf(me)).isNull();
		assertThat(status(getAs(fx.host(), myToken, "/api/v1/students/me/photo"))).isEqualTo(404);
	}

}
