package com.lms.common.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Black-on-white QR code rendering (ZXing core): error correction level M and the
 * standard 4-module quiet zone, drawn at an exact integer number of pixels per module so
 * every module stays sharp. Payload-agnostic; callers decide what is encoded.
 */
public final class QrCodes {

	public static final int QUIET_ZONE_MODULES = 4;

	private static final int BLACK = 0xFF000000;

	private static final int WHITE = 0xFFFFFFFF;

	private QrCodes() {
	}

	/** The code as an image, quiet zone included, {@code pixelsPerModule} px per module. */
	public static BufferedImage image(String payload, int pixelsPerModule) {
		BitMatrix matrix = encode(payload);
		int scale = Math.max(1, pixelsPerModule);
		int width = matrix.getWidth() * scale;
		int height = matrix.getHeight() * scale;
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < matrix.getHeight(); y++) {
			for (int x = 0; x < matrix.getWidth(); x++) {
				int color = matrix.get(x, y) ? BLACK : WHITE;
				for (int dy = 0; dy < scale; dy++) {
					for (int dx = 0; dx < scale; dx++) {
						image.setRGB(x * scale + dx, y * scale + dy, color);
					}
				}
			}
		}
		return image;
	}

	/** The code as PNG bytes. */
	public static byte[] png(String payload, int pixelsPerModule) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image(payload, pixelsPerModule), "png", out);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("QR code encoding failed", ex);
		}
		return out.toByteArray();
	}

	private static BitMatrix encode(String payload) {
		Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
		hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
		hints.put(EncodeHintType.MARGIN, QUIET_ZONE_MODULES);
		hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
		try {
			// width/height 0: ZXing returns the natural size (1 px per module + margin).
			return new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, hints);
		}
		catch (WriterException ex) {
			throw new IllegalStateException("QR code encoding failed", ex);
		}
	}

}
