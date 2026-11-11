# HumNod 2.1 performance notes

## Model and package choices

SmolLM2 135M adds a 143 MB option alongside the 343 MB Qwen3 0.6B and 2.59 GB Gemma 4 E2B downloads. SmolLM2 uses 58% less download space than Qwen3. Qwen3 remains the default recommendation for everyday prompts. [SmolLM2 model card](https://huggingface.co/litert-community/SmolLM2-135M-Instruct) · [Qwen3 model card](https://huggingface.co/litert-community/Qwen3-0.6B) · [Gemma 4 model card](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm)

The 2.1 ARM64 debug APK is about 62 MB, compared with about 101 MB for the 2.0 APK containing both ARM64 and x86-64 libraries. The x86-64 debug APK is about 67 MB. Models download separately.

The Android app uses LiteRT-LM 0.19.1 on the CPU. Google documents optional GPU native library requirements and device-specific NPU configuration. Phone benchmarks are needed before choosing those paths as defaults. [LiteRT-LM releases](https://github.com/google-ai-edge/LiteRT-LM/releases/tag/v0.19.1) · [Android runtime guidance](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.19.1/docs/api/kotlin/getting_started.md)

## Offline model timing

On the same Android 16 x86-64 emulator in airplane mode, a temporary instrumentation run timed from the call to generate until the first non-empty text chunk. Each model used the CPU, one warm-up reply, and three identical short rewrite prompts with a 64-token output cap.

- **SmolLM2 135M.** Median first text in **343 ms**. Median full reply in **419 ms**. Engine initialization took 698 ms.
- **Qwen3 0.6B.** Median first text in **705 ms**. Median full reply in **1,303 ms**. Engine initialization took 2,784 ms.

These are emulator measurements. Replies varied in length, and SmolLM2 missed a simple instruction in a separate manual check. Qwen3 remains the recommended starting point for instruction following and document prompts.

## Work removed from common paths

- New downloads calculate SHA-256 as bytes arrive. Resuming hashes the existing prefix once, then continues hashing the incoming bytes.
- Replies display the first chunk immediately and group subsequent chunks for redraw. A stop request cancels generation and returns the composer to a usable state once LiteRT finishes cleanup.
- Text files stream a short prefix. UTF-8 and BOM-marked UTF-16 preserve Markdown whitespace and avoid splitting a Unicode character at the excerpt boundary.
- PDFs are staged in private cache and read with buffered random access. This avoids copying the entire file into the Java heap and limits the file size, page count, and decompression. [iText PdfReader](https://api.itextpdf.com/iText/java/7.2.3/com/itextpdf/kernel/pdf/PdfReader.html)
- Photo text recognition runs offline. The app limits input size and decodes a smaller bitmap before recognition. [Bundled ML Kit text recognition](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
