package com.neurallite.app.models

/**
 * A single OpenAI-style chat message used for prompt formatting.
 */
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * Chat formatting templates matching the models in [ModelCatalog].
 */
enum class PromptTemplate(
    val stopStrings: List<String>,
    val parseSpecial: Boolean
) {
    ZEPHYR(
        stopStrings = listOf("</s>", "<|user|>", "<|system|>"),
        parseSpecial = true
    ) {
        override fun formatChat(messages: List<ChatMessage>): String {
            val sb = StringBuilder()
            for (msg in messages) {
                val role = when (val raw = msg.role.lowercase().ifBlank { "user" }) {
                    "developer" -> "system"
                    else -> raw
                }
                sb.append("<|").append(role).append("|>\n")
                    .append(msg.content).append("</s>\n")
            }
            sb.append("<|assistant|>\n")
            return sb.toString()
        }
    },

    PHI2(
        stopStrings = listOf("\nInstruct:", "Instruct:", "<|endoftext|>"),
        parseSpecial = false
    ) {
        override fun formatChat(messages: List<ChatMessage>): String {
            val sb = StringBuilder()
            for (msg in messages) {
                when (msg.role.lowercase()) {
                    "system", "developer" -> sb.append("System: ").append(msg.content).append("\n")
                    "assistant" -> sb.append("Output: ").append(msg.content).append("\n")
                    else -> sb.append("Instruct: ").append(msg.content).append("\n")
                }
            }
            sb.append("Output:")
            return sb.toString()
        }
    },

    GEMMA(
        stopStrings = listOf("<end_of_turn>", "<start_of_turn>"),
        parseSpecial = true
    ) {
        override fun formatChat(messages: List<ChatMessage>): String {
            val pendingSystem = StringBuilder()
            val sb = StringBuilder()
            for (msg in messages) {
                when (msg.role.lowercase()) {
                    "system", "developer" -> {
                        if (pendingSystem.isNotEmpty()) pendingSystem.append("\n")
                        pendingSystem.append(msg.content)
                    }
                    "assistant", "model" -> {
                        if (pendingSystem.isNotEmpty()) {
                            sb.append("<start_of_turn>user\n")
                                .append(pendingSystem)
                                .append("<end_of_turn>\n")
                            pendingSystem.clear()
                        }
                        sb.append("<start_of_turn>model\n")
                            .append(msg.content)
                            .append("<end_of_turn>\n")
                    }
                    else -> {
                        val content = if (pendingSystem.isNotEmpty()) {
                            val merged = "$pendingSystem\n\n${msg.content}"
                            pendingSystem.clear()
                            merged
                        } else {
                            msg.content
                        }
                        sb.append("<start_of_turn>user\n")
                            .append(content)
                            .append("<end_of_turn>\n")
                    }
                }
            }
            if (pendingSystem.isNotEmpty()) {
                sb.append("<start_of_turn>user\n")
                    .append(pendingSystem)
                    .append("<end_of_turn>\n")
            }
            sb.append("<start_of_turn>model\n")
            return sb.toString()
        }
    },

    MISTRAL_V1(
        stopStrings = listOf("</s>", "[INST]"),
        parseSpecial = true
    ) {
        override fun formatChat(messages: List<ChatMessage>): String {
            val pendingSystem = StringBuilder()
            val sb = StringBuilder()
            for (msg in messages) {
                when (msg.role.lowercase()) {
                    "system", "developer" -> {
                        if (pendingSystem.isNotEmpty()) pendingSystem.append("\n")
                        pendingSystem.append(msg.content)
                    }
                    "assistant" -> {
                        if (pendingSystem.isNotEmpty()) {
                            sb.append("[INST] ").append(pendingSystem).append(" [/INST]")
                            pendingSystem.clear()
                        }
                        sb.append(msg.content).append("</s>")
                    }
                    else -> {
                        val content = if (pendingSystem.isNotEmpty()) {
                            val merged = "$pendingSystem\n\n${msg.content}"
                            pendingSystem.clear()
                            merged
                        } else {
                            msg.content
                        }
                        sb.append("[INST] ").append(content).append(" [/INST]")
                    }
                }
            }
            if (pendingSystem.isNotEmpty()) {
                sb.append("[INST] ").append(pendingSystem).append(" [/INST]")
            }
            return sb.toString()
        }
    },

    LLAMA3(
        stopStrings = listOf("<|eot_id|>", "<|end_of_text|>"),
        parseSpecial = true
    ) {
        override fun formatChat(messages: List<ChatMessage>): String {
            val sb = StringBuilder()
            for (msg in messages) {
                val role = when (val raw = msg.role.lowercase().ifBlank { "user" }) {
                    "developer" -> "system"
                    else -> raw
                }
                sb.append("<|start_header_id|>").append(role).append("<|end_header_id|>\n\n")
                    .append(msg.content).append("<|eot_id|>")
            }
            sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            return sb.toString()
        }
    };

    abstract fun formatChat(messages: List<ChatMessage>): String
}

/**
 * Metadata for a single on-device LLM model.
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
    val contextLength: Int,
    val promptTemplate: PromptTemplate = PromptTemplate.ZEPHYR
)

/**
 * Hard-coded catalog of supported on-device models.
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
            contextLength = 2048,
            promptTemplate = PromptTemplate.ZEPHYR
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
            contextLength = 2048,
            promptTemplate = PromptTemplate.PHI2
        ),
        ModelInfo(
            id = "gemma2b_q4ks",
            displayName = "Gemma 2B IT",
            parameterSize = "2B",
            quantization = "Q4_K_S",
            fileSizeGB = 1.3,
            ramRequiredGB = 2.5,
            estimatedTokensPerSec = 15,
            huggingFaceDirectUrl = "https://huggingface.co/MaziyarPanahi/gemma-2b-it-GGUF/resolve/main/gemma-2b-it.Q4_K_S.gguf",
            recommendedForTiers = listOf(3, 2),
            tags = listOf("instruct", "chat"),
            contextLength = 4096,
            promptTemplate = PromptTemplate.GEMMA
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
            contextLength = 8192,
            promptTemplate = PromptTemplate.MISTRAL_V1
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
            contextLength = 8192,
            promptTemplate = PromptTemplate.LLAMA3
        )
    )

    // ── Helper functions ────────────────────────────────────────────────────

    fun getRecommendedModels(tier: Int): List<ModelInfo> =
        models.filter { tier in it.recommendedForTiers }
            .sortedByDescending { it.estimatedTokensPerSec }

    fun getModelById(id: String): ModelInfo? =
        models.firstOrNull { it.id == id }

    fun getFileName(model: ModelInfo): String =
        model.huggingFaceDirectUrl.substringAfterLast('/')
}
