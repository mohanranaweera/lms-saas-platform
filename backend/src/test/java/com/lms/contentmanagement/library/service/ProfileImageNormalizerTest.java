package com.lms.contentmanagement.library.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.common.error.FieldValidationException;
import com.lms.common.error.UnsupportedMediaTypeException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** STU-MGMT-02 photo normalisation: EXIF orientation parsing and mapping, limits, output format. */
class ProfileImageNormalizerTest {

	private static byte[] app1(boolean little, int orientation) {
		byte[] tiff = little
				? new byte[] { 'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, (byte) orientation, 0, 0, 0,
						0, 0, 0, 0 }
				: new byte[] { 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0,
						0, 0, 0, 0, 0 };
		int length = 2 + 6 + tiff.length;
		byte[] out = new byte[4 + 6 + tiff.length + 2];
		out[0] = (byte) 0xFF;
		out[1] = (byte) 0xD8;
		out[2] = (byte) 0xFF;
		out[3] = (byte) 0xE1;
		out[4] = (byte) (length >> 8);
		out[5] = (byte) length;
		System.arraycopy(new byte[] { 'E', 'x', 'i', 'f', 0, 0 }, 0, out, 6, 6);
		System.arraycopy(tiff, 0, out, 12, tiff.length);
		return out;
	}

	@Test
	void readsTheOrientationInBothByteOrdersAndDefaultsToOne() {
		assertThat(ProfileImageNormalizer.exifOrientation(app1(false, 6))).isEqualTo(6);
		assertThat(ProfileImageNormalizer.exifOrientation(app1(true, 8))).isEqualTo(8);
		assertThat(ProfileImageNormalizer.exifOrientation(app1(true, 42))).isEqualTo(1);
		assertThat(ProfileImageNormalizer.exifOrientation(new byte[] { (byte) 0xFF, (byte) 0xD8 })).isEqualTo(1);
		assertThat(ProfileImageNormalizer.exifOrientation("not a jpeg".getBytes())).isEqualTo(1);
		// Truncated segment length never reads out of bounds.
		byte[] truncated = app1(false, 6);
		truncated[5] = (byte) 0xFF;
		assertThat(ProfileImageNormalizer.exifOrientation(truncated)).isEqualTo(1);
	}

	@Test
	void orientationMappingMovesTheTopLeftPixelWhereExifSays() {
		BufferedImage square = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
		square.setRGB(0, 0, 0xFF0000);
		// 6 = rotate 90 clockwise: top-left goes to top-right.
		assertThat(ProfileImageNormalizer.orient(square, 6).getRGB(3, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
		// 8 = rotate 90 counter-clockwise: top-left goes to bottom-left.
		assertThat(ProfileImageNormalizer.orient(square, 8).getRGB(0, 3) & 0xFFFFFF).isEqualTo(0xFF0000);
		// 3 = rotate 180.
		assertThat(ProfileImageNormalizer.orient(square, 3).getRGB(3, 3) & 0xFFFFFF).isEqualTo(0xFF0000);
		// 2 = mirror horizontally.
		assertThat(ProfileImageNormalizer.orient(square, 2).getRGB(3, 0) & 0xFFFFFF).isEqualTo(0xFF0000);
		assertThat(ProfileImageNormalizer.orient(square, 1)).isSameAs(square);
	}

	@Test
	void producesA512SquareJpegAndEnforcesDimensions() throws Exception {
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB), "png", png);
		byte[] jpeg = ProfileImageNormalizer.normalize(png.toByteArray(), "image/png");
		assertThat(jpeg[0] & 0xFF).isEqualTo(0xFF);
		assertThat(jpeg[1] & 0xFF).isEqualTo(0xD8);
		BufferedImage out = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertThat(out.getWidth()).isEqualTo(512);
		assertThat(out.getHeight()).isEqualTo(512);
		// No EXIF segment in the output.
		assertThat(ProfileImageNormalizer.exifOrientation(jpeg)).isEqualTo(1);
		assertThat(new String(jpeg, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("Exif");

		ByteArrayOutputStream tiny = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(63, 200, BufferedImage.TYPE_INT_RGB), "png", tiny);
		assertThatThrownBy(() -> ProfileImageNormalizer.normalize(tiny.toByteArray(), "image/png"))
			.isInstanceOf(FieldValidationException.class);
		ByteArrayOutputStream huge = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(4097, 64, BufferedImage.TYPE_BYTE_BINARY), "png", huge);
		assertThatThrownBy(() -> ProfileImageNormalizer.normalize(huge.toByteArray(), "image/png"))
			.isInstanceOf(FieldValidationException.class);
		assertThatThrownBy(() -> ProfileImageNormalizer.normalize(new byte[] { 1, 2, 3 }, "image/png"))
			.isInstanceOf(UnsupportedMediaTypeException.class);
	}

}
