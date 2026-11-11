package ai.humnod.genai.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class AttachmentReaderTest {
    private Context context;
    private File fixtures;

    @Before
    public void createFixtureDirectory() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixtures = Files.createTempDirectory(context.getCacheDir().toPath(), "attachment-test-").toFile();
    }

    @After
    public void removeFixtures() throws Exception {
        File[] files = fixtures.listFiles();
        if (files != null) {
            for (File file : files) Files.deleteIfExists(file.toPath());
        }
        Files.deleteIfExists(fixtures.toPath());
    }

    @Test
    public void markdownFallbackPreservesIndentationAndHardLineBreaks() throws Exception {
        File fixture = new File(fixtures, "notes.md");
        String original = "\ufeff    indented code\r\nA hard break  \r\nRésumé ✅\r";
        Files.write(fixture.toPath(), original.getBytes(StandardCharsets.UTF_8));

        AttachmentReader result = AttachmentReader.read(context, Uri.fromFile(fixture));

        assertEquals("notes.md", result.name);
        assertEquals("    indented code\nA hard break  \nRésumé ✅\n", result.text);
        assertFalse(result.excerpt);
    }

    @Test
    public void readsBomMarkedUtf16() throws Exception {
        File fixture = new File(fixtures, "notes.txt");
        Files.write(fixture.toPath(), "\ufeffCafé 🥐\r\n".getBytes(StandardCharsets.UTF_16LE));

        AttachmentReader result = AttachmentReader.read(context, Uri.fromFile(fixture));

        assertEquals("Café 🥐\n", result.text);
        assertFalse(result.excerpt);
    }

    @Test
    public void excerptDoesNotSplitCombiningCharacters() throws Exception {
        File fixture = new File(fixtures, "long.txt");
        char[] prefix = new char[1799];
        Arrays.fill(prefix, 'a');
        String expected = new String(prefix);
        Files.write(fixture.toPath(), (expected + "e\u0301 remaining").getBytes(StandardCharsets.UTF_8));

        AttachmentReader result = AttachmentReader.read(context, Uri.fromFile(fixture));

        assertEquals(expected, result.text);
        assertTrue(result.excerpt);
    }

    @Test
    public void pdfExtractsTextFromBoundedPageRange() throws Exception {
        File fixture = new File(fixtures, "agenda.pdf");
        try (PdfDocument document = new PdfDocument(new PdfWriter(fixture))) {
            PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            for (int page = 1; page <= 13; page++) {
                new PdfCanvas(document.addNewPage()).beginText().setFontAndSize(font, 20)
                        .moveText(40, 700).showText("Agenda page " + page).endText();
            }
        }

        AttachmentReader result = AttachmentReader.read(context, Uri.fromFile(fixture));

        assertTrue(result.text.startsWith("Agenda page 1\n\nAgenda page 2"));
        assertTrue(result.text.endsWith("Agenda page 12"));
        assertFalse(result.text.contains("Agenda page 13"));
        assertTrue(result.excerpt);
    }

    @Test
    public void oversizedPdfIsRejectedBeforeParsing() throws Exception {
        File fixture = new File(fixtures, "oversized.pdf");
        try (RandomAccessFile file = new RandomAccessFile(fixture, "rw")) {
            file.setLength(32_000_001);
        }

        AttachmentReader.ReadFailure failure = assertThrows(AttachmentReader.ReadFailure.class,
                () -> AttachmentReader.read(context, Uri.fromFile(fixture)));

        assertEquals("Choose a PDF no larger than 32 MB.", failure.getMessage());
    }

    @Test
    public void bundledOcrReadsPhotoText() throws Exception {
        File fixture = new File(fixtures, "label.png");
        Bitmap image = Bitmap.createBitmap(900, 320, Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(fixture)) {
            Canvas canvas = new Canvas(image);
            canvas.drawColor(Color.WHITE);
            Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
            ink.setColor(Color.BLACK);
            ink.setTextSize(80);
            canvas.drawText("HUMNOD OFFLINE", 36, 160, ink);
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }

        AttachmentReader result = AttachmentReader.read(context, Uri.fromFile(fixture));

        assertTrue(result.text.contains("HUMNOD OFFLINE"));
        assertFalse(result.excerpt);
    }
}
