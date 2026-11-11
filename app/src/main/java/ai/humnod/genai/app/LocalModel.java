package ai.humnod.genai.app;

import java.io.File;

enum LocalModel {
    SMOL("SmolLM2 135M", "143 MB", "Smallest · simple prompts",
            "litert-community/SmolLM2-135M-Instruct", "f7e76881373410af03873a1d92e939495ba98f7b",
            "SmolLM2_135M_Instruct.litertlm", 142_819_328L,
            "ccdc5c85735743f081b7d44ca309cab569f76c0f2f0e8e163449a63721969c37"),
    QWEN("Qwen3 0.6B", "343 MB", "Recommended · everyday questions",
            "litert-community/Qwen3-0.6B", "8768ea43e6d801ee0d353e5036007e6790a2ba05",
            "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm", 343_490_560L,
            "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0"),
    GEMMA("Gemma 4 E2B", "2.59 GB", "More capable · longer explanations",
            "litert-community/gemma-4-E2B-it-litert-lm", "2d74ac41a33d7da82f39f7d5720a04c68514a103",
            "gemma-4-E2B-it.litertlm", 2_588_147_712L,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c");

    final String title;
    final String size;
    final String description;
    final String url;
    final String filename;
    final long bytes;
    final String sha256;

    LocalModel(String title, String size, String description, String repo, String revision,
               String filename, long bytes, String sha256) {
        this.title = title;
        this.size = size;
        this.description = description;
        this.url = "https://huggingface.co/" + repo + "/resolve/" + revision + "/" + filename;
        this.filename = filename;
        this.bytes = bytes;
        this.sha256 = sha256;
    }

    File fileIn(File directory) {
        return new File(directory, sha256.substring(0, 12) + "-" + filename);
    }

    boolean isInstalled(File directory) {
        File file = fileIn(directory);
        return file.isFile() && file.length() == bytes;
    }
}
