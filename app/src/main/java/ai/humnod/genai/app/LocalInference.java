package ai.humnod.genai.app;

import android.os.SystemClock;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.LogSeverity;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.ThinkingConfig;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** All engine operations are serialized on the model worker. */
final class LocalInference implements AutoCloseable {
    private static final long CALLBACK_POLL_MS = 75;
    private static final long INACTIVITY_LIMIT_MS = TimeUnit.MINUTES.toMillis(2);
    private static final long CANCEL_GRACE_MS = TimeUnit.SECONDS.toMillis(5);
    private static final long CANCEL_RETRY_MS = 500;

    private Engine engine;

    void load(File model, File cache) {
        close();
        Engine.Companion.setNativeMinLogSeverity(LogSeverity.ERROR);
        Engine next = new Engine(new EngineConfig(model.getAbsolutePath(), new Backend.CPU(),
                null, null, 2048, null, cache.getAbsolutePath()));
        next.initialize();
        engine = next;
    }

    void reply(String instruction, String question, int maxOutput, AtomicBoolean stopped,
               Consumer<String> onText) throws Exception {
        if (stopped.get()) return;
        if (engine == null) throw new IllegalStateException("Model is not loaded.");
        ConversationConfig config = new ConversationConfig(Contents.Companion.of(instruction),
                Collections.emptyList(), Collections.emptyList(), null, false, null,
                Collections.emptyMap(), null, false, maxOutput, new ThinkingConfig(false));

        // Closing a conversation waits for native tasks. Keep it on the model worker so
        // a callback never waits for the native thread currently calling it.
        try (Conversation conversation = engine.createConversation(config)) {
            if (stopped.get()) return;
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean acceptingText = new AtomicBoolean(true);
            AtomicLong lastActivity = new AtomicLong(SystemClock.uptimeMillis());
            conversation.sendMessageAsync(question, new MessageCallback() {
                @Override
                public void onMessage(Message message) {
                    lastActivity.set(SystemClock.uptimeMillis());
                    for (Content content : message.getContents().getContents()) {
                        if (stopped.get() || !acceptingText.get()) return;
                        if (content instanceof Content.Text) {
                            onText.accept(((Content.Text) content).getText());
                        }
                    }
                }

                @Override
                public void onDone() {
                    acceptingText.set(false);
                    finished.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    acceptingText.set(false);
                    failure.set(throwable);
                    finished.countDown();
                }
            });

            try {
                while (!finished.await(CALLBACK_POLL_MS, TimeUnit.MILLISECONDS)) {
                    boolean timedOut = SystemClock.uptimeMillis() - lastActivity.get()
                            >= INACTIVITY_LIMIT_MS;
                    if (!stopped.get() && !timedOut) continue;

                    acceptingText.set(false);
                    cancelAndWait(conversation, finished);
                    if (stopped.get()) return;
                    throw new TimeoutException("The model stopped responding.");
                }
            } catch (InterruptedException interrupted) {
                acceptingText.set(false);
                try {
                    if (finished.getCount() != 0) conversation.cancelProcess();
                } finally {
                    Thread.currentThread().interrupt();
                }
                throw interrupted;
            } finally {
                acceptingText.set(false);
            }

            Throwable generationFailure = failure.get();
            if (!stopped.get() && generationFailure != null) {
                throw new IllegalStateException("Generation failed.", generationFailure);
            }
        }
    }

    private static void cancelAndWait(Conversation conversation, CountDownLatch finished)
            throws InterruptedException {
        long deadline = SystemClock.uptimeMillis() + CANCEL_GRACE_MS;
        // A stop request can race the transition from prefill to decode.
        while (finished.getCount() != 0) {
            conversation.cancelProcess();
            long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0
                    || finished.await(Math.min(remaining, CANCEL_RETRY_MS), TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    @Override
    public void close() {
        if (engine != null) {
            Engine old = engine;
            engine = null;
            old.close();
        }
    }
}
