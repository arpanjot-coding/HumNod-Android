package ai.humnod.genai.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

final class AttachmentReader {
    private static final int CHARACTER_LIMIT = 1800;
    private static final int PAGE_LIMIT = 12;
    static final String[] TYPES = {"application/pdf", "text/plain", "text/markdown", "image/*"};

    final String name;
    final String text;
    final boolean excerpt;

    private AttachmentReader(String name, String text, boolean excerpt) {
        this.name = name;
        this.text = text;
        this.excerpt = excerpt;
    }

    static AttachmentReader read(ContentResolver resolver, Uri uri) throws Exception {
        String name = "Attachment";
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        }
        String mime = resolver.getType(uri);
        String text;
        boolean morePages = false;
        if (mime != null && mime.startsWith("image/")) {
            Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri),
                    (decoder, info, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int maxSide = Math.max(info.getSize().getWidth(), info.getSize().getHeight());
                        decoder.setTargetSampleSize(Math.max(1, (maxSide + 2399) / 2400));
                    });
            try (TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) {
                text = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
            } finally {
                bitmap.recycle();
            }
        } else {
            try (InputStream stream = resolver.openInputStream(uri)) {
                if (stream == null) throw new FileNotFoundException();
                if ("application/pdf".equals(mime)) {
                    try (PdfDocument pdf = new PdfDocument(new PdfReader(stream))) {
                        StringBuilder extracted = new StringBuilder();
                        int count = pdf.getNumberOfPages();
                        for (int i = 1; i <= Math.min(count, PAGE_LIMIT); i++) {
                            extracted.append(PdfTextExtractor.getTextFromPage(pdf.getPage(i))).append('\n');
                            if (extracted.length() > CHARACTER_LIMIT) break;
                        }
                        text = extracted.toString();
                        morePages = count > PAGE_LIMIT;
                    }
                } else if ("text/plain".equals(mime) || "text/markdown".equals(mime)) {
                    try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                        char[] buffer = new char[CHARACTER_LIMIT + 1];
                        int total = 0;
                        while (total < buffer.length) {
                            int read = reader.read(buffer, total, buffer.length - total);
                            if (read == -1) break;
                            total += read;
                        }
                        text = new String(buffer, 0, total);
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported attachment.");
                }
            }
        }
        boolean excerpt = morePages || text.length() > CHARACTER_LIMIT;
        int end = Math.min(text.length(), CHARACTER_LIMIT);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return new AttachmentReader(name, text.substring(0, end).trim(), excerpt);
    }
}
