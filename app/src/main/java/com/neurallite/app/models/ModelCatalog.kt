package com.neurallite.app.models

/**
 * Metadata for a single on-device LLM model.
 *
 * @property id                  Unique slug used as key everywhere.
 * @property displayName         Human-readable name for the UI.
 * @property parameterSize       Model parameter count label (e.g. "7B").
 * @property quantization        GGUF quantization method (e.g. "Q4_K_M").
 * @property fileSizeGB          Approximate GGUF file size in gigabytes.
 * @property ramRequiredGB       Minimum device RAM to run comfortably.
 * @property estimatedTokensPerSec Ballpark tok/s on the recommended tier.
 * @property huggingFaceDirectUrl Direct download URL for the GGUF blob.
 * @property recommendedForTiers  Device tiers (1–4) this model suits.
 * @property tags                Searchable tags ("chat", "code", …).
 * @property contextLength       Maximum context window in tokens.
 */
data class ModelInfo(
    val id: String,
    val displayName: String,
    val parameterSize: String,
    val quantization: String,
    val fileSizeGB: Double,
    val ramRequiredGB: Double,
    val estimatedTokensPerSec: Int,
    val huggingFaceDirectUrl: String,
    val recommendedForTiers: List<Int>,
    val tags: List<String>,
    val contextLength: Int
)

/**
 * Hard-coded catalog of supported on-device models.
 *
 * Every entry points to a publicly-hosted GGUF file on HuggingFace.
 * The catalog is intentionally immutable — no runtime mutations.
 */
object ModelCatalog {

    val models: List<ModelInfo> = listOf(
        // ── Tier 4 / 3 — Ultra-light ────────────────────────────────────────
        ModelInfo(
            id = "tinyllama_1b_q8",
            displayName = "TinyLlama 1.1B",
            parameterSize = "1.1B",
            quantization = "Q8_0",
            fileSizeGB = 1.1,
            ramRequiredGB = 1.5,
            estimatedTokensPerSec = 25,
            huggingFaceDirectUrl = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q8_0.gguf",
            recommendedForTiers = listOf(4, 3),
            tags = listOf("ultra-light", "chat"),
            contextLength = 2048
        ),

        // ── Tier 3 / 2 — Lightweight ───────────────────────────────────────
        ModelInfo(
            id = "phi2_q4km",
            displayName = "Phi-2 2.7B",
            parameterSize = "2.7B",
            quantization = "Q4_K_M",
            fileSizeGB = 1.6,
            ramRequiredGB = 3.0,
            estimatedTokensPerSec = 12,
            huggingFaceDirectUrl = "https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf",
            recommendedForTiers = listOf(3, 2),
            tags = listOf("chat", "code", "instruct"),
            contextLength = 2048
        ),
        ModelInfo(
            id = "gemma2b_q4ks",
            displayName = "Gemma 2B IT",
            parameterSize = "2B",
            quantization = "Q4_K_S",
            fileSizeGB = 1.3,
            ramRequiredGB = 2.5,
            estimatedTokensPerSec = 15,
            huggingFaceDirectUrl = "https://huggingface.co/TheBloke/gemma-2b-it-GGUF/resolve/main/gemma-2b-it.Q4_K_S.gguf",
            recommendedForTiers = listOf(3, 2),
            tags = listOf("instruct", "chat"),
            contextLength = 4096
        ),

        // ── Tier 1 / 2 — Powerful ──────────────────────────────────────────
        ModelInfo(
            id = "mistral7b_q3km",
            displayName = "Mistral 7B Instruct",
            parameterSize = "7B",
            quantization = "Q3_K_M",
            fileSizeGB = 3.1,
            ramRequiredGB = 5.5,
            estimatedTokensPerSec = 6,
            huggingFaceDirectUrl = "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.1-GGUF/resolve/main/mistral-7b-instruct-v0.1.Q3_K_M.gguf",
            recommendedForTiers = listOf(1, 2),
            tags = listOf("instruct", "chat", "powerful"),
            contextLength = 8192
        ),

        // ── Tier 1 — Flagship only ─────────────────────────────────────────
        ModelInfo(
            id = "llama3_8b_q4km",
            displayName = "Llama 3 8B Instruct",
            parameterSize = "8B",
            quantization = "Q4_K_M",
            fileSizeGB = 4.9,
            ramRequiredGB = 7.0,
            estimatedTokensPerSec = 5,
            huggingFaceDirectUrl = "https://huggingface.co/bartowski/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct-Q4_K_M.gguf",
            recommendedForTiers = listOf(1),
            tags = listOf("instruct", "chat", "powerful"),
            contextLength = 8192
        )
    )

    // ── Helper functions ────────────────────────────────────────────────────

    /**
     * Returns models whose [ModelInfo.recommendedForTiers] list contains
     * the given [tier], ordered by descending estimated tok/s.
     */
    fun getRecommendedModels(tier: Int): List<ModelInfo> =
        models.filter { tier in it.recommendedForTiers }
            .sortedByDescending { it.estimatedTokensPerSec }

    /**
     * Look up a single model by its unique [id], or `null` if not found.
     */
    fun getModelById(id: String): ModelInfo? =
        models.firstOrNull { it.id == id }

    /**
     * Extracts the GGUF filename from the model's HuggingFace URL.
     *
     * For example:
     * ```
     * "https://…/tinyllama-1.1b-chat-v1.0.Q8_0.gguf" → "tinyllama-1.1b-chat-v1.0.Q8_0.gguf"
     * ```
     */
    fun getFileName(model: ModelInfo): String =
        model.huggingFaceDirectUrl.substringAfterLast('/')
}
