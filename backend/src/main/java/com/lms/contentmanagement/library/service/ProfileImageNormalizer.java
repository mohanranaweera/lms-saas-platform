package com.lms.contentmanagement.library.service;

import com.lms.common.api.FieldError;
import com.lms.common.error.FieldValidationException;
import com.lms.common.error.UnsupportedMediaTypeException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/**
 * STU-MGMT-02 (spec section 6): turns an uploaded PNG/JPEG into the stored profile photo -
 * a 512x512 centre-cropped JPEG with no metadata (EXIF, GPS, comments, ICC are all
 * dropped because the image is re-encoded from pixels). The dimensions are read from the
 * header before the pixels are decoded, so an oversized image is refused without
 * allocating it. A JPEG's EXIF orientation (tag 0x0112) is applied so the stored photo is
 * upright. Centre-crop and the eight EXIF orientations commute (all are symmetries of the
 * centre square), so the orientation is applied to the small 512x512 result.
 */
final class ProfileImageNormalizer {

	static final int MIN_SIDE = 64;

	static final int MAX_SIDE = 4096;

	static final int OUTPUT_SIDE = 512;

	private static final float JPEG_QUALITY = 0.85f;

	private ProfileImageNormalizer() {
	}

	static byte[] normalize(byte[] bytes, String sniffedContentType) {
		BufferedImage decoded = decode(bytes);
		BufferedImage square = cropAndScale(decoded);
		int orientation = "image/jpeg".equals(sniffedContentType) ? exifOrientation(bytes) : 1;
		return encodeJpeg(orient(square, orientation));
	}

	private static BufferedImage decode(byte[] bytes) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
			if (readers == null || !readers.hasNext()) {
				throw notAnImage();
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int width = reader.getWidth(0);
				int height = reader.getHeight(0);
				if (width < MIN_SIDE || height < MIN_SIDE || width > MAX_SIDE || height > MAX_SIDE) {
					throw new FieldValidationException("The photo dimensions are not accepted",
							List.of(new FieldError("file", "The photo must be between " + MIN_SIDE + " and " + MAX_SIDE
									+ " pixels on each side")));
				}
				BufferedImage image = reader.read(0);
				if (image == null) {
					throw notAnImage();
				}
				return image;
			}
			finally {
				reader.dispose();
			}
		}
		catch (IOException | RuntimeException ex) {
			if (ex instanceof FieldValidationException || ex instanceof UnsupportedMediaTypeException) {
				throw (RuntimeException) ex;
			}
			throw notAnImage();
		}
	}

	private static UnsupportedMediaTypeException notAnImage() {
		return new UnsupportedMediaTypeException("The photo must be a valid PNG or JPEG image");
	}

	private static BufferedImage cropAndScale(BufferedImage source) {
		int side = Math.min(source.getWidth(), source.getHeight());
		int x = (source.getWidth() - side) / 2;
		int y = (source.getHeight() - side) / 2;
		BufferedImage target = new BufferedImage(OUTPUT_SIDE, OUTPUT_SIDE, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = target.createGraphics();
		try {
			graphics.setColor(Color.WHITE);
			graphics.fillRect(0, 0, OUTPUT_SIDE, OUTPUT_SIDE);
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			graphics.drawImage(source, 0, 0, OUTPUT_SIDE, OUTPUT_SIDE, x, y, x + side, y + side, null);
		}
		finally {
			graphics.dispose();
		}
		return target;
	}

	/** Applies an EXIF orientation (1-8) to a square image. */
	static BufferedImage orient(BufferedImage square, int orientation) {
		if (orientation <= 1 || orientation > 8) {
			return square;
		}
		int n = square.getWidth();
		BufferedImage out = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < n; y++) {
			for (int x = 0; x < n; x++) {
				int sx;
				int sy;
				switch (orientation) {
					case 2 -> {
						sx = n - 1 - x;
						sy = y;
					}
					case 3 -> {
						sx = n - 1 - x;
						sy = n - 1 - y;
					}
					case 4 -> {
						sx = x;
						sy = n - 1 - y;
					}
					case 5 -> {
						sx = y;
						sy = x;
					}
					case 6 -> {
						sx = y;
						sy = n - 1 - x;
					}
					case 7 -> {
						sx = n - 1 - y;
						sy = n - 1 - x;
					}
					default -> {
						sx = n - 1 - y;
						sy = x;
					}
				}
				out.setRGB(x, y, square.getRGB(sx, sy));
			}
		}
		return out;
	}

	private static byte[] encodeJpeg(BufferedImage image) {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IllegalStateException("No JPEG encoder available");
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (ImageOutputStream output = ImageIO.createImageOutputStream(buffer)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(JPEG_QUALITY);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Failed to encode the profile photo", ex);
		}
		finally {
			writer.dispose();
		}
		return buffer.toByteArray();
	}

	/** The EXIF orientation (1-8) of a JPEG, or 1 when absent/unreadable. */
	static int exifOrientation(byte[] b) {
		if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
			return 1;
		}
		int pos = 2;
		while (pos + 4 <= b.length) {
			if ((b[pos] & 0xFF) != 0xFF) {
				return 1;
			}
			int marker = b[pos + 1] & 0xFF;
			if (marker == 0xFF) {
				pos++;
				continue;
			}
			if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				pos += 2;
				continue;
			}
			if (marker == 0xDA || marker == 0xD9) {
				return 1;
			}
			int length = ((b[pos + 2] & 0xFF) << 8) | (b[pos + 3] & 0xFF);
			int end = pos + 2 + length;
			if (length < 2 || end > b.length) {
				return 1;
			}
			if (marker == 0xE1 && length >= 16 && b[pos + 4] == 'E' && b[pos + 5] == 'x' && b[pos + 6] == 'i'
					&& b[pos + 7] == 'f' && b[pos + 8] == 0 && b[pos + 9] == 0) {
				return tiffOrientation(b, pos + 10, end);
			}
			pos = end;
		}
		return 1;
	}

	private static int tiffOrientation(byte[] b, int start, int end) {
		if (start + 8 > end) {
			return 1;
		}
		boolean little = b[start] == 'I' && b[start + 1] == 'I';
		boolean big = b[start] == 'M' && b[start + 1] == 'M';
		if (!little && !big) {
			return 1;
		}
		long ifdOffset = u32(b, start + 4, little);
		if (ifdOffset < 8 || start + ifdOffset + 2 > end) {
			return 1;
		}
		int ifd = (int) (start + ifdOffset);
		int count = u16(b, ifd, little);
		for (int i = 0; i < count; i++) {
			int entry = ifd + 2 + i * 12;
			if (entry + 12 > end) {
				return 1;
			}
			if (u16(b, entry, little) == 0x0112) {
				int value = u16(b, entry + 8, little);
				return (value >= 1 && value <= 8) ? value : 1;
			}
		}
		return 1;
	}

	private static int u16(byte[] b, int at, boolean little) {
		int b0 = b[at] & 0xFF;
		int b1 = b[at + 1] & 0xFF;
		return little ? (b1 << 8) | b0 : (b0 << 8) | b1;
	}

	private static long u32(byte[] b, int at, boolean little) {
		long b0 = b[at] & 0xFF;
		long b1 = b[at + 1] & 0xFF;
		long b2 = b[at + 2] & 0xFF;
		long b3 = b[at + 3] & 0xFF;
		return little ? (b3 << 24) | (b2 << 16) | (b1 << 8) | b0 : (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
	}

}
