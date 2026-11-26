package com.neurallite.app.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    @Test
    fun `catalog entries are valid and unique`() {
        val models = ModelCatalog.models
        assertTrue(models.size >= 5)

        val ids = models.map { it.id }
        assertEquals(ids.size, ids.toSet().size)

        val fileNames = models.map { ModelCatalog.getFileName(it) }
        assertEquals(fileNames.size, fileNames.toSet().size)

        models.forEach { model ->
            assertTrue("URL must be HTTPS: ${model.huggingFaceDirectUrl}",
                model.huggingFaceDirectUrl.startsWith("https://"))
            assertTrue("Filename must end with .gguf",
                ModelCatalog.getFileName(model).endsWith(".gguf"))
            assertTrue(model.fileSizeGB > 0.0)
            assertTrue(model.ramRequiredGB > 0.0)
            assertTrue(model.contextLength >= 512)
            assertTrue(model.recommendedForTiers.isNotEmpty())
        }
    }

    @Test
    fun `gemma url points to accessible repository`() {
        val gemma = ModelCatalog.getModelById("gemma2b_q4ks")
        assertNotNull(gemma)
        assertFalse(gemma!!.huggingFaceDirectUrl.contains("TheBloke/gemma-2b-it-GGUF"))
        assertTrue(gemma.huggingFaceDirectUrl.contains("MaziyarPanahi/gemma-2b-it-GGUF"))
    }

    @Test
    fun `lookup by id handles known and unknown ids`() {
        assertNotNull(ModelCatalog.getModelById("tinyllama_1b_q8"))
        assertNull(ModelCatalog.getModelById("nonexistent_model"))
    }

    @Test
    fun `recommended models are filtered and sorted by speed descending`() {
        val tier3 = ModelCatalog.getRecommendedModels(3)
        assertTrue(tier3.isNotEmpty())
        assertTrue(tier3.all { 3 in it.recommendedForTiers })
        for (i in 0 until tier3.size - 1) {
            assertTrue(tier3[i].estimatedTokensPerSec >= tier3[i + 1].estimatedTokensPerSec)
        }

        val tier4 = ModelCatalog.getRecommendedModels(4)
        assertEquals(listOf("tinyllama_1b_q8"), tier4.map { it.id })
    }

    @Test
    fun `prompt templates format chat turns accurately`() {
        val msgs = listOf(
            ChatMessage("system", "Be brief."),
            ChatMessage("user", "Hi!")
        )

        val zephyr = PromptTemplate.ZEPHYR.formatChat(msgs)
        assertTrue(zephyr.contains("<|system|>\nBe brief.</s>"))
        assertTrue(zephyr.contains("<|user|>\nHi!</s>"))
        assertTrue(zephyr.endsWith("<|assistant|>\n"))

        val phi2 = PromptTemplate.PHI2.formatChat(msgs)
        assertTrue(phi2.contains("System: Be brief."))
        assertTrue(phi2.contains("Instruct: Hi!"))
        assertTrue(phi2.endsWith("Output:"))

        val gemma = PromptTemplate.GEMMA.formatChat(msgs)
        assertFalse(gemma.contains("system"))
        assertTrue(gemma.contains("<start_of_turn>user\nBe brief.\n\nHi!<end_of_turn>"))
        assertTrue(gemma.endsWith("<start_of_turn>model\n"))

        val mistral = PromptTemplate.MISTRAL_V1.formatChat(msgs)
        assertEquals("[INST] Be brief.\n\nHi! [/INST]", mistral)

        val llama3 = PromptTemplate.LLAMA3.formatChat(msgs)
        assertTrue(llama3.contains("<|start_header_id|>system<|end_header_id|>\n\nBe brief.<|eot_id|>"))
        assertTrue(llama3.contains("<|start_header_id|>user<|end_header_id|>\n\nHi!<|eot_id|>"))
        assertTrue(llama3.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    @Test
    fun `prompt templates map developer role to system and support multi-turn history`() {
        val msgs = listOf(
            ChatMessage("developer", "Be concise."),
            ChatMessage("user", "First question"),
            ChatMessage("assistant", "First answer"),
            ChatMessage("user", "Follow up")
        )

        val zephyr = PromptTemplate.ZEPHYR.formatChat(msgs)
        assertTrue(zephyr.contains("<|system|>\nBe concise.</s>"))
        assertTrue(zephyr.contains("<|assistant|>\nFirst answer</s>"))
        assertTrue(zephyr.endsWith("<|user|>\nFollow up</s>\n<|assistant|>\n"))

        val phi2 = PromptTemplate.PHI2.formatChat(msgs)
        assertTrue(phi2.startsWith("System: Be concise.\n"))
        assertTrue(phi2.contains("Output: First answer\n"))
        assertTrue(phi2.endsWith("Instruct: Follow up\nOutput:"))

        val gemma = PromptTemplate.GEMMA.formatChat(msgs)
        assertTrue(gemma.contains("<start_of_turn>user\nBe concise.\n\nFirst question<end_of_turn>"))
        assertTrue(gemma.contains("<start_of_turn>model\nFirst answer<end_of_turn>"))
        assertTrue(gemma.endsWith("<start_of_turn>user\nFollow up<end_of_turn>\n<start_of_turn>model\n"))

        val mistral = PromptTemplate.MISTRAL_V1.formatChat(msgs)
        assertEquals("[INST] Be concise.\n\nFirst question [/INST]First answer</s>[INST] Follow up [/INST]", mistral)

        val llama3 = PromptTemplate.LLAMA3.formatChat(msgs)
        assertTrue(llama3.contains("<|start_header_id|>system<|end_header_id|>\n\nBe concise.<|eot_id|>"))
        assertTrue(llama3.contains("<|start_header_id|>assistant<|end_header_id|>\n\nFirst answer<|eot_id|>"))
        assertTrue(llama3.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }
}
