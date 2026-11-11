package ai.humnod.genai.app;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

final class ModelDownloader {
    private static final int BUFFER_SIZE = 256 * 1024;
    private static final long DISK_RESERVE = 128L * 1024 * 1024;

    private ModelDownloader() {}

    static File download(File directory, LocalModel model, AtomicBoolean cancelled,
                         IntConsumer progress, Runnable verifying) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not open the model folder.");
        }
        File target = model.fileIn(directory);
        if (model.isInstalled(directory)) return target;
        File partial = new File(directory, target.getName() + ".part");
        long offset = partial.length();
        if (offset > model.bytes) {
            remove(partial);
            offset = 0;
        }
        requireSpace(directory, model.bytes - offset);
        checkCancelled(cancelled);
        if (offset < model.bytes) {
            HttpURLConnection connection = (HttpURLConnection) new URL(model.url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) {
                    // A server can ignore a Range request. Replace the partial file in that case.
                    offset = 0;
                } else if (status != HttpURLConnection.HTTP_PARTIAL || offset == 0 ||
                        !("bytes " + offset + "-" + (model.bytes - 1) + "/" + model.bytes)
                                .equals(connection.getHeaderField("Content-Range"))) {
                    throw new IOException("Model server returned an unexpected response (" + status + ").");
                }
                long expected = model.bytes - offset;
                long length = connection.getContentLengthLong();
                if (length >= 0 && length != expected) {
                    throw new IOException("The download size does not match the model.");
                }
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(partial, offset > 0)) {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long received = offset;
                    int previous = -1;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        if (read > model.bytes - received) {
                            throw new IOException("The download exceeded the expected size.");
                        }
                        output.write(buffer, 0, read);
                        received += read;
                        int percent = (int) (100 * received / model.bytes);
                        if (percent != previous) progress.accept(percent);
                        previous = percent;
                    }
                    if (received != model.bytes) {
                        throw new IOException("The download was interrupted. Retry to resume.");
                    }
                    output.getFD().sync();
                }
            } finally {
                connection.disconnect();
            }
        }
        checkCancelled(cancelled);
        verifying.run();
        if (!model.sha256.equals(sha256(partial, cancelled))) {
            remove(partial);
            throw new IOException("The model failed verification. Retry to download a fresh copy.");
        }
        checkCancelled(cancelled);
        if (!partial.renameTo(target)) {
            throw new IOException("Could not save the verified model.");
        }
        return target;
    }

    static void delete(File directory, LocalModel model) throws IOException {
        File target = model.fileIn(directory);
        remove(new File(directory, target.getName() + ".part"));
        remove(target);
    }

    private static void remove(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Could not remove the model file.");
    }

    private static void requireSpace(File directory, long remaining) throws IOException {
        if (directory.getUsableSpace() < remaining + DISK_RESERVE) {
            throw new IOException("Free up storage and try again.");
        }
    }

    private static void checkCancelled(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get()) throw new InterruptedIOException("Download paused.");
    }

    private static String sha256(File file, AtomicBoolean cancelled) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled(cancelled);
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value & 0xf0) >> 4, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }
}
