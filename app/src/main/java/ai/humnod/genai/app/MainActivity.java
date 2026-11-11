package ai.humnod.genai.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import ai.humnod.genai.app.databinding.ActivityMainBinding;
import ai.humnod.genai.app.databinding.ModelChoiceBinding;
import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.core.MarkwonTheme;

public class MainActivity extends AppCompatActivity {
    private static final String SUMMARY_SHORTCUT = "summary_shortcut";
    // A process-wide queue keeps a resumed download from racing the previous activity's teardown.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final LocalInference inference = new LocalInference();
    private final StringBuilder reply = new StringBuilder();
    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::readAttachment);
    private ActivityMainBinding ui;
    private SharedPreferences preferences;
    private File models;
    private File cache;
    private Markwon markdown;
    private LocalModel activeModel;
    private AttachmentReader attachment;
    private AtomicBoolean operation = new AtomicBoolean();
    private boolean modelBusy;
    private boolean generating;
    private boolean reading;
    private boolean suggestSummary;
    private String attachmentNotice;
    private boolean academic;
    private boolean expandedReply;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        ui = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(ui.getRoot());
        int horizontalPadding = ui.screen.getPaddingStart();
        ViewCompat.setOnApplyWindowInsetsListener(ui.screen, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            view.setPadding(horizontalPadding + bars.left, bars.top,
                    horizontalPadding + bars.right, bars.bottom);
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        preferences = getSharedPreferences("humnod", MODE_PRIVATE);
        models = new File(getNoBackupFilesDir(), "models");
        cache = getCacheDir();
        suggestSummary = savedInstanceState != null && savedInstanceState.getBoolean(SUMMARY_SHORTCUT);
        academic = preferences.getBoolean("academic", false);
        expandedReply = preferences.getBoolean("expanded", false);
        markdown = Markwon.builder(this).usePlugin(new AbstractMarkwonPlugin() {
            @Override
            public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                builder.codeTextColor(0xffe8f3f1).codeBackgroundColor(0xff263b40)
                        .codeBlockTextColor(0xffe8f3f1).codeBlockBackgroundColor(0xff263b40)
                        .linkColor(0xff8fe6d3);
            }
        }).build();

        ui.modelsButton.setOnClickListener(v -> chooseModel());
        ui.attachButton.setOnClickListener(v -> chooseAttachment(false));
        ui.fileShortcut.setOnClickListener(v -> chooseAttachment(true));
        ui.modeButton.setOnClickListener(v -> replySettings());
        ui.attachment.setOnClickListener(v -> previewAttachment());
        bindChoice(ui.qwenChoice, LocalModel.QWEN);
        bindChoice(ui.smolChoice, LocalModel.SMOL);
        bindChoice(ui.gemmaChoice, LocalModel.GEMMA);
        ui.sendButton.setOnClickListener(v -> {
            if (generating) {
                operation.set(true);
                updateControls();
            } else {
                send();
            }
        });
        ui.copyButton.setOnClickListener(v -> {
            ClipData clip = ClipData.newPlainText("HumNod reply", reply.toString().trim());
            PersistableBundle flags = new PersistableBundle();
            flags.putBoolean("android.content.extra.IS_SENSITIVE", true);
            clip.getDescription().setExtras(flags);
            getSystemService(ClipboardManager.class).setPrimaryClip(clip);
            toast("Reply copied.");
        });
        ui.prompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateControls();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        updateControls();
        String preferred = preferences.getString("model", "");
        for (LocalModel model : LocalModel.values()) {
            if (model.name().equals(preferred) && model.isInstalled(models)) {
                prepare(model);
                break;
            }
        }
    }

    private void bindChoice(ModelChoiceBinding tile, LocalModel model) {
        tile.choiceName.setText(model.title);
        tile.choiceSize.setText(model.size);
        tile.choiceDetail.setText(model.description);
        tile.getRoot().setContentDescription(model.title + ", " + model.size + ", " + model.description);
        tile.getRoot().setOnClickListener(v -> modelDetails(model, false));
    }

    private void chooseModel() {
        if (modelBusy || generating || reading) return;
        LocalModel[] options = LocalModel.values();
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            LocalModel option = options[i];
            labels[i] = option.title + "  ·  " + option.size + "\n"
                    + (activeModel == option ? "In use" :
                    option.isInstalled(models) ? "Downloaded" : option.description);
        }
        new MaterialAlertDialogBuilder(this).setTitle("Choose a model")
                .setItems(labels, (dialog, which) -> modelDetails(options[which], true))
                .setNegativeButton("Close", null)
                .show();
    }

    private void modelDetails(LocalModel model, boolean fromPicker) {
        boolean installed = model.isInstalled(models);
        boolean partial = new File(models, model.fileIn(models).getName() + ".part").isFile();
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this).setTitle(model.title)
                .setMessage(model.description + "\n\n" + (installed
                        ? "Stored on this device. You can use it offline."
                        : model.size + " download from Hugging Face. Wi-Fi is recommended."))
                .setNegativeButton(fromPicker ? "Back" : "Cancel",
                        fromPicker ? (d, w) -> chooseModel() : null);
        if (activeModel != model) {
            dialog.setPositiveButton(installed ? "Use model" : partial ? "Resume" : "Download",
                    (d, w) -> prepare(model));
        }
        if (installed || partial) {
            dialog.setNeutralButton("Remove", (d, w) -> confirmRemoval(model));
        }
        dialog.show();
    }

    private void confirmRemoval(LocalModel model) {
        new MaterialAlertDialogBuilder(this).setTitle("Remove " + model.title + "?")
                .setMessage("You can download it again later.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (d, w) -> {
                    boolean wasActive = activeModel == model;
                    if (wasActive) {
                        activeModel = null;
                        preferences.edit().remove("model").apply();
                        ui.modelPanel.setVisibility(View.GONE);
                    }
                    modelBusy = true;
                    updateControls();
                    WORKER.execute(() -> {
                        String error = null;
                        try {
                            if (wasActive) inference.close();
                            ModelDownloader.delete(models, model);
                        } catch (IOException | RuntimeException e) {
                            error = "Could not remove the model.";
                        }
                        String result = error;
                        post(() -> {
                            modelBusy = false;
                            updateControls();
                            toast(result == null ? "Model removed." : result);
                        });
                    });
                }).show();
    }

    private void prepare(LocalModel model) {
        if (modelBusy || generating || reading) return;
        boolean installed = model.isInstalled(models);
        AtomicBoolean cancelled = new AtomicBoolean();
        operation = cancelled;
        modelBusy = true;
        updateControls();
        if (installed) {
            panel(model.title, "Loading on this device…", null, null);
        } else {
            panel(model.title, "Connecting to Hugging Face…", "Pause", () -> {
                cancelled.set(true);
                panel(model.title, "Pausing download…", null, null);
            });
            ui.downloadProgress.setProgress(0);
            ui.downloadProgress.setVisibility(View.VISIBLE);
        }
        WORKER.execute(() -> {
            boolean loading = false;
            try {
                File file = installed ? model.fileIn(models) :
                        ModelDownloader.download(models, model, cancelled, percent -> post(() -> {
                            if (cancelled.get()) return;
                            ui.downloadProgress.setProgressCompat(percent, true);
                            ui.modelDetail.setText(getString(R.string.downloading_progress, percent));
                        }), () -> post(() -> {
                            if (!cancelled.get()) ui.modelDetail.setText(R.string.verifying_download);
                        }));
                if (cancelled.get()) {
                    post(() -> preparationFailed(model, "Download paused. Resume when you’re ready."));
                    return;
                }
                loading = true;
                post(() -> panel(model.title, "Loading on this device…", null, null));
                inference.load(file, cache);
                post(() -> {
                    activeModel = model;
                    preferences.edit().putString("model", model.name()).apply();
                    modelBusy = false;
                    ui.modelPanel.setVisibility(View.GONE);
                    updateControls();
                });
            } catch (IOException | RuntimeException | LinkageError e) {
                boolean attemptedLoad = loading;
                String error = cancelled.get() ? "Download paused. Resume when you’re ready."
                        : loading ? "This model could not load. Try the compact model or restart the app."
                        : e instanceof java.net.SocketTimeoutException ? "The download timed out. Retry to resume."
                        : e.getMessage() == null ? "Download failed. Check your connection and retry."
                        : e.getMessage();
                post(() -> {
                    if (attemptedLoad) activeModel = null;
                    preparationFailed(model, error);
                });
            }
        });
    }

    private void preparationFailed(LocalModel model, String error) {
        modelBusy = false;
        panel(model.title, error, "Try again", () -> prepare(model));
        updateControls();
    }

    private void panel(String title, String detail, String action, Runnable listener) {
        ui.modelPanel.setVisibility(View.VISIBLE);
        ui.modelTitle.setText(title);
        ui.modelDetail.setText(detail);
        ui.downloadProgress.setVisibility(View.GONE);
        ui.modelAction.setVisibility(action == null ? View.GONE : View.VISIBLE);
        ui.modelAction.setText(action);
        ui.modelAction.setOnClickListener(listener == null ? null : v -> listener.run());
    }

    private void replySettings() {
        String[] options = {"Assistant · concise", "Assistant · detailed",
                "Academic · concise", "Academic · detailed"};
        int current = (academic ? 2 : 0) + (expandedReply ? 1 : 0);
        new MaterialAlertDialogBuilder(this).setTitle("Response style")
                .setSingleChoiceItems(options, current, (dialog, which) -> {
                    academic = which >= 2;
                    expandedReply = which % 2 == 1;
                    preferences.edit().putBoolean("academic", academic)
                            .putBoolean("expanded", expandedReply).apply();
                    updateControls();
                    dialog.dismiss();
                }).setNegativeButton("Cancel", null).show();
    }

    private void chooseAttachment(boolean summarize) {
        suggestSummary = summarize;
        picker.launch(AttachmentReader.TYPES);
    }

    private void previewAttachment() {
        AttachmentReader source = attachment;
        if (source == null) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle(source.name)
                .setMessage(source.excerpt ? "Excerpt sent with your prompt\n\n" + source.text : source.text)
                .setNegativeButton("Remove file", (dialog, which) -> {
                    attachment = null;
                    attachmentNotice = null;
                    updateControls();
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private void readAttachment(Uri uri) {
        boolean fillSummary = suggestSummary;
        suggestSummary = false;
        if (uri == null || generating || reading) return;
        reading = true;
        attachmentNotice = getString(R.string.reading_attachment);
        updateControls();
        fileWorker.execute(() -> {
            try {
                AttachmentReader result = AttachmentReader.read(this, uri);
                post(() -> {
                    reading = false;
                    attachmentNotice = null;
                    attachment = result;
                    if (fillSummary && ui.prompt.getText().toString().trim().isEmpty()) {
                        ui.prompt.setText(R.string.summarize_prompt);
                        ui.prompt.setSelection(ui.prompt.length());
                    }
                    updateControls();
                });
            } catch (AttachmentReader.ReadFailure e) {
                post(() -> {
                    reading = false;
                    attachmentNotice = attachment == null ? e.getMessage()
                            : getString(R.string.kept_attachment, e.getMessage());
                    updateControls();
                });
            }
        });
    }

    private void send() {
        String question = ui.prompt.getText().toString().trim();
        if (activeModel == null || question.isEmpty() || modelBusy || reading || generating) return;
        AttachmentReader source = attachment;
        String input = source == null ? question : "Attachment excerpt:\n---\n" + source.text
                + "\n---\n\nRequest: " + question;
        String instruction = academic
                ? "You are an academic assistant. Explain clearly and use only citations you know."
                : "You are a helpful assistant. Answer clearly and directly.";
        if (source != null) instruction += " Use the attachment as source material.";
        String systemInstruction = instruction;
        int maxOutput = expandedReply ? 512 : 256;
        AtomicBoolean stopped = new AtomicBoolean();
        operation = stopped;
        generating = true;
        reply.setLength(0);
        ui.intro.setVisibility(View.GONE);
        ui.exchange.setVisibility(View.VISIBLE);
        ui.copyButton.setVisibility(View.GONE);
        ui.question.setText(source == null ? question : question + "\n\n" + source.name);
        ui.answer.setText(R.string.thinking);
        ui.prompt.setText("");
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(ui.prompt.getWindowToken(), 0);
        ui.prompt.clearFocus();
        updateControls();
        ui.chatScroll.scrollTo(0, 0);
        ReplyStream stream = new ReplyStream(stopped);
        WORKER.execute(() -> {
            String error = null;
            try {
                inference.reply(systemInstruction, input, maxOutput, stopped, stream);
            } catch (TimeoutException e) {
                error = "The model stopped responding. Try sending the prompt again.";
            } catch (Exception | LinkageError e) {
                error = "Could not generate a reply. Try a shorter question or remove the attachment.";
            }
            String result = error;
            post(() -> {
                stream.finish();
                finishReply(stopped.get(), result, question);
            });
        });
    }

    private void finishReply(boolean stopped, String error, String question) {
        generating = false;
        String text = reply.toString().trim();
        if (!text.isEmpty()) {
            markdown.setMarkdown(ui.answer, text);
            ui.copyButton.setVisibility(View.VISIBLE);
            if (error != null) toast("The reply ended early.");
        } else {
            ui.answer.setText(stopped ? "Reply stopped." : error == null ? "The model returned no text." : error);
            ui.prompt.setText(question);
        }
        updateControls();
    }

    private void updateControls() {
        boolean ready = activeModel != null && !modelBusy && !reading;
        boolean firstRun = activeModel == null;
        boolean showOptions = firstRun && !modelBusy && ui.modelPanel.getVisibility() != View.VISIBLE;
        ui.setupModels.setVisibility(showOptions ? View.VISIBLE : View.GONE);
        ui.introKicker.setText(firstRun ? R.string.offline_setup : R.string.ready_kicker);
        ui.introTitle.setText(firstRun ? R.string.hero_title : R.string.ready_title);
        ui.introDetail.setText(firstRun ? R.string.hero_detail : R.string.ready_detail);
        ui.fileShortcut.setVisibility(ready && !generating && attachment == null
                && ui.intro.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
        ui.composer.setVisibility(activeModel == null ? View.GONE : View.VISIBLE);
        ui.setupFooter.setVisibility(activeModel == null ? View.VISIBLE : View.GONE);
        ui.modelsButton.setEnabled(!modelBusy && !generating && !reading);
        ui.modelsButton.setText(activeModel == null ? getString(R.string.models) : activeModel.title);
        ui.prompt.setEnabled(activeModel != null && !modelBusy && !generating);
        ui.attachButton.setEnabled(ready && !generating);
        ui.attachButton.setText(R.string.attach);
        ui.modeButton.setEnabled(ready && !generating);
        ui.modeButton.setText(academic ? "Academic" : getString(R.string.assistant_mode));
        ui.sendButton.setText(generating ? operation.get() ? "Stopping…" : "Stop" : getString(R.string.send));
        ui.sendButton.setEnabled(generating ? !operation.get() :
                ready && !ui.prompt.getText().toString().trim().isEmpty());
        ui.attachment.setEnabled(ready && !generating);
        ui.attachment.setVisibility(attachment == null ? View.GONE : View.VISIBLE);
        ui.attachmentNotice.setVisibility(attachmentNotice == null ? View.GONE : View.VISIBLE);
        ui.attachmentNotice.setText(attachmentNotice);
        if (attachment != null) {
            ui.attachment.setText(getString(R.string.attachment_label, attachment.name));
            ui.attachment.setContentDescription(getString(R.string.attachment_preview, attachment.name));
        }
    }

    /** Render the first chunk immediately, then combine chunks that arrive before the next redraw. */
    private final class ReplyStream implements Consumer<String> {
        private static final long RENDER_INTERVAL_MS = 64;
        private final AtomicBoolean request;
        private final StringBuilder pending = new StringBuilder();
        private final Runnable renderPending = this::render;
        private boolean first = true;
        private boolean queued;
        private boolean closed;

        ReplyStream(AtomicBoolean request) {
            this.request = request;
        }

        @Override
        public synchronized void accept(String text) {
            if (closed || text.isEmpty()) return;
            pending.append(text);
            if (!queued) {
                queued = true;
                mainHandler.postDelayed(renderPending, first ? 0 : RENDER_INTERVAL_MS);
                first = false;
            }
        }

        private void render() {
            String chunk;
            synchronized (this) {
                chunk = pending.toString();
                pending.setLength(0);
                queued = false;
            }
            if (!chunk.isEmpty() && operation == request && !isFinishing() && !isDestroyed()) {
                reply.append(chunk);
                ui.answer.setText(reply.toString());
            }
        }

        void finish() {
            synchronized (this) {
                closed = true;
            }
            mainHandler.removeCallbacks(renderPending);
            render();
        }
    }

    private void post(Runnable action) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle state) {
        state.putBoolean(SUMMARY_SHORTCUT, suggestSummary);
        super.onSaveInstanceState(state);
    }

    @Override
    protected void onDestroy() {
        operation.set(true);
        fileWorker.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        WORKER.execute(inference::close);
        super.onDestroy();
    }
}
