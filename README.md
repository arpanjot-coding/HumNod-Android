# HumNod

**Offline AI for Android.** Ask a question or work with a file using a model that runs on your phone.

[Get started](#get-started) · [Models](#models) · [Build from source](#build-from-source) · [Report an issue](https://github.com/arpanjot-coding/HumNod-Android/issues)

## A look inside

<img src="docs/screenshots/setup.png" alt="HumNod setup screen" width="230"> <img src="docs/screenshots/models.png" alt="Model picker with download sizes for Qwen3 and Gemma 4" width="230"> <img src="docs/screenshots/chat.png" alt="A reply generated on an Android device in airplane mode" width="230">

*Screens from the Android 16 emulator. The chat uses a sample prompt.*

## Get started

1. [Build and install the APK](#build-from-source) on a 64-bit Android phone running Android 9 or later.
2. Open HumNod and tap **Choose a model**. Start with **Qwen3 0.6B** if you’re unsure what your phone can run.
3. Download the model, then ask a question. You can turn off your connection once the model is ready.

Each prompt starts a fresh exchange. Use **Attach** to include an excerpt from a file, or tap the response style to choose a concise or detailed answer.

## Models

- **[Qwen3 0.6B](https://huggingface.co/litert-community/Qwen3-0.6B) · 343 MB.** The compact option for everyday questions and quick edits.
- **[Gemma 4 E2B](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) · 2.59 GB.** Choose this for more demanding questions if your phone has room and memory to spare.

Sizes are downloads, not RAM requirements. Both models run on the CPU via [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM). Speed and memory use vary by phone. The **Models** menu lets you switch between or remove downloaded models. Keep the app open while downloading; interrupted downloads can be resumed.

Either model can get facts wrong. Check answers you plan to rely on, especially when using the compact model.

## Files and privacy

- Prompts, extracted text and replies are processed on the device. HumNod needs no account or API key. Its model downloads connect to Hugging Face and are checked against a pinned SHA-256 before use.
- Attach a plain-text file, Markdown file, text-based PDF, or a photo with readable text. The app passes a short excerpt to the model. A photo is used for text recognition, so HumNod cannot describe the scene in it. Scanned PDFs without a text layer are unsupported.
- HumNod shows the current exchange and does not keep a conversation history. Copy any reply you want to save.

## Build from source

Use Android Studio with JDK 17 or later and Android SDK Platform 36. The included Gradle wrapper downloads the build tools and dependencies.

```sh
git clone https://github.com/arpanjot-coding/HumNod-Android.git
cd HumNod-Android
./gradlew :app:assembleDebug :app:lintDebug
```

On Windows, use `gradlew.bat` in place of `./gradlew`. Open the project in Android Studio and select **Run**, or install `app/build/outputs/apk/debug/app-debug.apk` using `adb install -r`. The debug APK includes ARM64 and x86-64. Use a physical ARM64 phone to judge speed, memory use and battery life.

If you’re updating from an earlier build, download one of the new models. The former Phi-3 files cannot be reused. If you no longer need any of the previous app data, clearing HumNod’s storage in Android Settings reclaims the old files and resets the app.

## Feedback

When reporting an issue, include your phone model, Android version, selected model and steps to reproduce. Remove private prompts, replies and file contents before sharing logs or screenshots. Model weights are licensed separately; see their linked model cards.
