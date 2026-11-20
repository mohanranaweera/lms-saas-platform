import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * QA helper for the TUTE-01 live acceptance (frontend/e2e/tute-live*.spec.ts): prints the page
 * count and page sizes of a PDF and renders every page to PNG. Test tooling only - never used by
 * the application. Run with PDFBox 3.x on the classpath (see frontend/e2e/README or the spec):
 *
 * <pre>java -cp "pdfbox.jar;fontbox.jar;pdfbox-io.jar;commons-logging.jar" RenderPdf.java in.pdf outPrefix [dpi]</pre>
 */
public class RenderPdf {

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: RenderPdf <in.pdf> <outPrefix> [dpi]");
			System.exit(2);
		}
		int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 110;
		try (PDDocument doc = Loader.loadPDF(new File(args[0]))) {
			int pages = doc.getNumberOfPages();
			System.out.println("pages=" + pages);
			PDFRenderer renderer = new PDFRenderer(doc);
			for (int i = 0; i < pages; i++) {
				PDRectangle box = doc.getPage(i).getMediaBox();
				System.out.println("page" + (i + 1) + "=" + Math.round(box.getWidth()) + "x" + Math.round(box.getHeight()));
				BufferedImage image = renderer.renderImageWithDPI(i, dpi);
				ImageIO.write(image, "png", new File(args[1] + "-p" + (i + 1) + ".png"));
			}
		}
	}

}
