package ai.humnod.genai.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.icu.text.BreakIterator;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.itextpdf.io.source.GetBufferedRandomAccessSource;
import com.itextpdf.io.source.RandomAccessSourceFactory;
import com.itextpdf.kernel.exceptions.BadPasswordException;
import com.itextpdf.kernel.exceptions.MemoryLimitsAwareException;
import com.itextpdf.kernel.pdf.MemoryLimitsAwareHandler;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.ReaderProperties;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.RandomAccessFile;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class AttachmentReader {
    private static final int CHARACTER_LIMIT = 1800;
    private static final int PAGE_LIMIT = 12;
    private static final int MAX_PDF_BYTES = 32_000_000;
    private static final int MAX_IMAGE_BYTES = 24_000_000;
    private static final int MAX_IMAGE_SIDE = 2048;
    private static final int EXCERPT_BUFFER_LIMIT = CHARACTER_LIMIT + 4;
    private static final String UNKNOWN_NAME = "Attachment";

    static final String[] TYPES = {
            "application/pdf", "text/plain", "text/markdown", "text/x-markdown",
            "application/octet-stream", "image/*"
    };

    final String name;
    final String text;
    final boolean excerpt;

    private AttachmentReader(String name, String text, boolean excerpt) {
        this.name = name;
        this.text = text;
        this.excerpt = excerpt;
    }

    static AttachmentReader read(Context context, Uri uri) throws ReadFailure {
        ContentResolver resolver = context.getContentResolver();
        try {
            Metadata metadata = readMetadata(resolver, uri);
            String type = resolver.getType(uri);
            if (type == null || "application/octet-stream".equals(type)) {
                type = guessType(metadata.name);
            }

            if ("application/pdf".equals(type)) {
                return readPdf(context, resolver, uri, metadata);
            }
            if (type != null && type.startsWith("image/")) {
                return readImage(context, resolver, uri, metadata);
            }
            if ("text/plain".equals(type) || "text/markdown".equals(type)
                    || "text/x-markdown".equals(type) || "application/markdown".equals(type)
                    || isTextFilename(metadata.name)) {
                return readText(resolver, uri, metadata.name);
            }
            throw new ReadFailure("Choose a text file, a PDF, or a photo of readable text.");
        } catch (ReadFailure error) {
            throw error;
        } catch (FileNotFoundException | SecurityException error) {
            throw new ReadFailure("Couldn't open that file. Check that it's available on this device.", error);
        } catch (IOException | RuntimeException error) {
            throw new ReadFailure("Couldn't read that attachment. Try another file.", error);
        }
    }

    private static Metadata readMetadata(ContentResolver resolver, Uri uri) {
        String name = uri.getLastPathSegment();
        long size = -1;
        String[] columns = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        try (Cursor cursor = resolver.query(uri, columns, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex);
            }
        } catch (IllegalArgumentException ignored) {
            // Some providers can open a stream without exposing document metadata.
        }
        return new Metadata(name == null || name.trim().isEmpty() ? UNKNOWN_NAME : name, size);
    }

    private static String guessType(String name) {
        if (isTextFilename(name)) return "text/plain";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static boolean isTextFilename(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown");
    }

    private static AttachmentReader readText(ContentResolver resolver, Uri uri, String name)
            throws IOException {
        ExcerptText result = new ExcerptText();
        try (PushbackInputStream input = new PushbackInputStream(openStream(resolver, uri), 3);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input,
                     detectEncoding(input).newDecoder()), 1024)) {
            char[] chunk = new char[512];
            int count;
            while (!result.isFull() && (count = reader.read(chunk)) != -1) {
                for (int i = 0; i < count; i++) {
                    if (chunk[i] == '\0') {
                        throw new ReadFailure("That file doesn't contain readable text.");
                    }
                }
                result.append(CharBuffer.wrap(chunk, 0, count));
            }
        } catch (CharacterCodingException error) {
            throw new ReadFailure("Couldn't decode that text file. Save it as UTF-8 and try again.", error);
        }
        return result.finish(name, false, "That file doesn't contain readable text.");
    }

    private static Charset detectEncoding(PushbackInputStream input) throws IOException {
        byte[] prefix = new byte[3];
        int length = 0;
        while (length < prefix.length) {
            int next = input.read();
            if (next == -1) break;
            prefix[length++] = (byte) next;
        }

        Charset encoding = StandardCharsets.UTF_8;
        int skip = 0;
        if (length >= 2 && prefix[0] == (byte) 0xff && prefix[1] == (byte) 0xfe) {
            encoding = StandardCharsets.UTF_16LE;
            skip = 2;
        } else if (length >= 2 && prefix[0] == (byte) 0xfe && prefix[1] == (byte) 0xff) {
            encoding = StandardCharsets.UTF_16BE;
            skip = 2;
        } else if (length == 3 && prefix[0] == (byte) 0xef && prefix[1] == (byte) 0xbb
                && prefix[2] == (byte) 0xbf) {
            skip = 3;
        }
        input.unread(prefix, skip, length - skip);
        return encoding;
    }

    private static AttachmentReader readPdf(Context context, ContentResolver resolver, Uri uri,
                                            Metadata metadata) throws IOException {
        File staged = stageFile(context, resolver, uri, metadata.size, MAX_PDF_BYTES,
                ".pdf", "Choose a PDF no larger than 32 MB.");
        try {
            MemoryLimitsAwareHandler limits = new MemoryLimitsAwareHandler() {
                @Override
                public boolean isMemoryLimitsAwarenessRequiredOnDecompression(PdfArray filters) {
                    // Apply these limits to ordinary compressed streams as well as suspicious ones.
                    return filters != null && !filters.isEmpty();
                }
            };
            limits.setMaxSizeOfSingleDecompressedPdfStream(8 * 1024 * 1024)
                    .setMaxSizeOfDecompressedPdfStreamsSum(32L * 1024 * 1024);
            limits.setMaxNumberOfElementsInXrefStructure(100_000);
            ReaderProperties properties = new ReaderProperties().setMemoryLimitsAwareHandler(limits);
            try (RandomAccessFile file = new RandomAccessFile(staged, "r");
                 PdfReader reader = new PdfReader(new GetBufferedRandomAccessSource(
                         new RandomAccessSourceFactory().createSource(file)), properties)
                         .setMemorySavingMode(true);
                 PdfDocument document = new PdfDocument(reader)) {
                ExcerptText result = new ExcerptText();
                int pages = document.getNumberOfPages();
                int page = 1;
                for (; page <= Math.min(pages, PAGE_LIMIT) && !result.isFull(); page++) {
                    String text = PdfTextExtractor.getTextFromPage(document.getPage(page));
                    if (!containsReadableText(text)) continue;
                    if (result.hasText()) result.append("\n\n");
                    result.append(text);
                }
                return result.finish(metadata.name, page <= pages,
                        "This PDF has no readable text in its first pages. Try a text PDF or a photo.");
            } catch (BadPasswordException error) {
                throw new ReadFailure("This PDF is password protected. Choose an unlocked copy.", error);
            } catch (MemoryLimitsAwareException error) {
                throw new ReadFailure("This PDF is too complex to open on this device. Try a smaller copy.", error);
            } catch (ReadFailure error) {
                throw error;
            } catch (IOException | RuntimeException error) {
                throw new ReadFailure("Couldn't read this PDF. Try a different copy.", error);
            }
        } finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    private static AttachmentReader readImage(Context context, ContentResolver resolver, Uri uri,
                                              Metadata metadata) throws IOException {
        File staged = stageFile(context, resolver, uri, metadata.size, MAX_IMAGE_BYTES,
                ".image", "Choose a photo no larger than 24 MB.");
        try {
            Bitmap bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(staged),
                    (decoder, info, source) -> {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int width = info.getSize().getWidth();
                        int height = info.getSize().getHeight();
                        int longestSide = Math.max(width, height);
                        if (longestSide > MAX_IMAGE_SIDE) {
                            decoder.setTargetSize(Math.max(1, (int) (width * (long) MAX_IMAGE_SIDE / longestSide)),
                                    Math.max(1, (int) (height * (long) MAX_IMAGE_SIDE / longestSide)));
                        }
                    });
            Task<Text> task = null;
            try (TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)) {
                task = recognizer.process(InputImage.fromBitmap(bitmap, 0));
                ExcerptText result = new ExcerptText();
                result.append(Tasks.await(task, 15, TimeUnit.SECONDS).getText());
                return result.finish(metadata.name, false,
                        "No readable text found in that photo. Try a closer or sharper image.");
            } catch (TimeoutException error) {
                throw new ReadFailure("Text recognition took too long. Try a cropped photo.", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ReadFailure("Reading the photo was interrupted.", error);
            } catch (ExecutionException error) {
                throw new ReadFailure("Couldn't recognize text in that photo.", error);
            } finally {
                if (task == null || task.isComplete()) bitmap.recycle();
                else task.addOnCompleteListener(Runnable::run, completed -> bitmap.recycle());
            }
        } catch (ImageDecoder.DecodeException error) {
            throw new ReadFailure("Couldn't open that photo. Try a different image.", error);
        } finally {
            Files.deleteIfExists(staged.toPath());
        }
    }

    private static File stageFile(Context context, ContentResolver resolver, Uri uri, long knownSize,
                                  long sizeLimit, String extension, String limitMessage) throws IOException {
        if (knownSize > sizeLimit) throw new ReadFailure(limitMessage);

        File staged = File.createTempFile("humnod-attachment-", extension, context.getCacheDir());
        boolean complete = false;
        try {
            try (InputStream input = openStream(resolver, uri);
                 FileOutputStream output = new FileOutputStream(staged)) {
                byte[] buffer = new byte[16 * 1024];
                long copied = 0;
                int count;
                while ((count = input.read(buffer, 0, (int) Math.min(buffer.length, sizeLimit - copied + 1))) != -1) {
                    if (count == 0) throw new IOException("The provider did not return any bytes.");
                    copied += count;
                    if (copied > sizeLimit) throw new ReadFailure(limitMessage);
                    output.write(buffer, 0, count);
                }
            }
            complete = true;
            return staged;
        } finally {
            if (!complete) Files.deleteIfExists(staged.toPath());
        }
    }

    private static InputStream openStream(ContentResolver resolver, Uri uri) throws IOException {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) throw new FileNotFoundException();
        return input;
    }

    private static boolean containsReadableText(CharSequence input) {
        for (int i = 0; i < input.length(); ) {
            int codePoint = Character.codePointAt(input, i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
                    && !Character.isISOControl(codePoint) && codePoint != 0xfeff) return true;
            i += Character.charCount(codePoint);
        }
        return false;
    }

    static final class ReadFailure extends IOException {
        ReadFailure(String message) {
            super(message);
        }

        private ReadFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Metadata {
        final String name;
        final long size;

        Metadata(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static final class ExcerptText {
        private final StringBuilder text = new StringBuilder(EXCERPT_BUFFER_LIMIT);
        private boolean afterCarriageReturn;

        void append(CharSequence chunk) {
            for (int i = 0; i < chunk.length() && !isFull(); i++) {
                char next = chunk.charAt(i);
                if (!(next == '\n' && afterCarriageReturn)) text.append(next == '\r' ? '\n' : next);
                afterCarriageReturn = next == '\r';
            }
        }

        boolean isFull() {
            return text.length() >= EXCERPT_BUFFER_LIMIT;
        }

        boolean hasText() {
            return text.length() > 0;
        }

        AttachmentReader finish(String name, boolean morePages, String emptyMessage) throws ReadFailure {
            boolean truncated = text.length() > CHARACTER_LIMIT;
            int end = text.length();
            if (truncated) {
                BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
                characters.setText(text.toString());
                end = characters.isBoundary(CHARACTER_LIMIT)
                        ? CHARACTER_LIMIT : characters.preceding(CHARACTER_LIMIT);
            }
            String value = text.substring(0, end);
            if (!containsReadableText(value)) throw new ReadFailure(emptyMessage);
            return new AttachmentReader(name, value, morePages || truncated);
        }
    }
}
