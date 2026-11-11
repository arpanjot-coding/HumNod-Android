package ai.humnod.genai.app;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** All engine operations are serialized on the model worker. */
final class LocalInference implements AutoCloseable {
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
        if (engine == null) throw new IllegalStateException("Model is not loaded.");
        ConversationConfig config = new ConversationConfig(Contents.Companion.of(instruction),
                Collections.emptyList(), Collections.emptyList(), null, false, null,
                Collections.emptyMap(), null, false, maxOutput, new ThinkingConfig(false));
        try (Conversation conversation = engine.createConversation(config)) {
            if (stopped.get()) return;
            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            conversation.sendMessageAsync(question, new MessageCallback() {
                @Override
                public void onMessage(Message message) {
                    for (Content content : message.getContents().getContents()) {
                        if (content instanceof Content.Text) onText.accept(((Content.Text) content).getText());
                    }
                }

                @Override
                public void onDone() {
                    finished.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                    finished.countDown();
                }
            });
            boolean cancelSent = false;
            while (!finished.await(75, TimeUnit.MILLISECONDS)) {
                if (stopped.get() && !cancelSent) {
                    conversation.cancelProcess();
                    cancelSent = true;
                }
            }
            if (!stopped.get() && failure.get() != null) {
                throw new IllegalStateException("Generation failed.", failure.get());
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
