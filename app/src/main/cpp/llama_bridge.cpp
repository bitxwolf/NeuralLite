#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>
#include <cstring>
#include <sstream>
#include <unistd.h>

#include "llama.h"

#define TAG "LlamaEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// ── Helpers (from common.h, inlined to avoid common lib dependency) ──────────

static void llama_batch_clear(struct llama_batch & batch) {
    batch.n_tokens = 0;
}

static void llama_batch_add(
    struct llama_batch & batch,
    llama_token id,
    llama_pos pos,
    const std::vector<llama_seq_id> & seq_ids,
    bool logits
) {
    batch.token   [batch.n_tokens] = id;
    batch.pos     [batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = seq_ids.size();
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits  [batch.n_tokens] = logits;
    batch.n_tokens++;
}

static bool is_valid_utf8(const char * string) {
    if (!string) return true;
    const unsigned char * bytes = (const unsigned char *)string;
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

// ── Handle structure ─────────────────────────────────────────────────────────

struct LlamaHandle {
    llama_model   * model;
    llama_context * ctx;
    int             n_ctx;
    int             n_threads;
    std::atomic<bool> stop_flag;

    LlamaHandle() : model(nullptr), ctx(nullptr), n_ctx(0), n_threads(0), stop_flag(false) {}
};

// ── Redirect llama.cpp logs to Android Logcat ────────────────────────────────

static void log_callback(ggml_log_level level, const char * fmt, void * /*data*/) {
    if (level == GGML_LOG_LEVEL_ERROR)
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_WARN)
        __android_log_print(ANDROID_LOG_WARN,  TAG, "%s", fmt);
    else
        __android_log_print(ANDROID_LOG_INFO,  TAG, "%s", fmt);
}

// ═══════════════════════════════════════════════════════════════════════════════
// JNI EXPORTS
// ═══════════════════════════════════════════════════════════════════════════════

extern "C" {

// ── loadModel ────────────────────────────────────────────────────────────────
JNIEXPORT jlong JNICALL
Java_com_neurallite_app_engine_LlamaEngine_loadModel(
    JNIEnv * env, jobject /* this */,
    jstring modelPath, jint nThreads, jint nCtx
) {
    const char * path = env->GetStringUTFChars(modelPath, nullptr);
    if (!path) {
        LOGE("loadModel: GetStringUTFChars returned null");
        return -1L;
    }

    LOGI("loadModel: path=%s threads=%d ctx=%d", path, nThreads, nCtx);

    // Redirect native logs to Logcat
    llama_log_set(log_callback, nullptr);

    // Initialize backend
    llama_backend_init();

    // Load model
    llama_model_params model_params = llama_model_default_params();
    llama_model * model = llama_load_model_from_file(path, model_params);
    env->ReleaseStringUTFChars(modelPath, path);

    if (!model) {
        LOGE("loadModel: llama_load_model_from_file failed");
        llama_backend_free();
        return -1L;
    }

    // Create context
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx           = nCtx;
    ctx_params.n_threads       = nThreads;
    ctx_params.n_threads_batch = nThreads;
    ctx_params.seed            = 1234;

    llama_context * ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        LOGE("loadModel: llama_new_context_with_model failed");
        llama_free_model(model);
        llama_backend_free();
        return -1L;
    }

    // Pack into handle
    auto * handle    = new LlamaHandle();
    handle->model    = model;
    handle->ctx      = ctx;
    handle->n_ctx    = nCtx;
    handle->n_threads = nThreads;
    handle->stop_flag.store(false);

    LOGI("loadModel: success — handle=%p", handle);
    return reinterpret_cast<jlong>(handle);
}

// ── runInference ─────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_neurallite_app_engine_LlamaEngine_runInference(
    JNIEnv * env, jobject /* this */,
    jlong handlePtr, jstring prompt,
    jint maxTokens, jfloat temperature,
    jobject onToken
) {
    auto * handle = reinterpret_cast<LlamaHandle *>(handlePtr);
    if (!handle || !handle->model || !handle->ctx) {
        LOGE("runInference: invalid handle");
        return env->NewStringUTF("");
    }

    handle->stop_flag.store(false);

    // Get prompt string
    const char * prompt_cstr = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_str(prompt_cstr);
    env->ReleaseStringUTFChars(prompt, prompt_cstr);

    LOGI("runInference: prompt_len=%zu maxTokens=%d temp=%.2f",
         prompt_str.length(), maxTokens, temperature);

    // ── Tokenize ──
    int n_prompt_max = prompt_str.length() + 32;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
        handle->model,
        prompt_str.c_str(),
        prompt_str.length(),
        tokens.data(),
        tokens.size(),
        true,   // add_bos
        false   // special
    );
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            handle->model,
            prompt_str.c_str(),
            prompt_str.length(),
            tokens.data(),
            tokens.size(),
            true,
            false
        );
    }
    if (n_tokens < 0) {
        LOGE("runInference: tokenization failed");
        return env->NewStringUTF("");
    }
    tokens.resize(n_tokens);
    LOGI("runInference: %d prompt tokens", n_tokens);

    // ── Clear KV cache ──
    llama_kv_cache_clear(handle->ctx);

    // ── Evaluate prompt ──
    llama_batch batch = llama_batch_init(handle->n_ctx, 0, 1);

    for (int i = 0; i < n_tokens; i++) {
        llama_batch_add(batch, tokens[i], i, {0}, (i == n_tokens - 1));
    }

    if (llama_decode(handle->ctx, batch) != 0) {
        LOGE("runInference: prompt decode failed");
        llama_batch_free(batch);
        return env->NewStringUTF("");
    }

    // ── Resolve onToken callback ──
    jclass callbackClass   = env->GetObjectClass(onToken);
    jmethodID invokeMethod = env->GetMethodID(
        callbackClass, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");

    // ── Token generation loop ──
    std::string result;
    std::string cached_chars;
    int n_cur = n_tokens;

    for (int i = 0; i < maxTokens; i++) {
        if (handle->stop_flag.load()) {
            LOGI("runInference: stopped by user");
            break;
        }

        // Sample
        float * logits = llama_get_logits_ith(handle->ctx, -1);
        int n_vocab = llama_n_vocab(handle->model);

        std::vector<llama_token_data> candidates;
        candidates.reserve(n_vocab);
        for (llama_token tid = 0; tid < n_vocab; tid++) {
            candidates.push_back({tid, logits[tid], 0.0f});
        }

        llama_token_data_array candidates_p = {
            candidates.data(), candidates.size(), false
        };

        llama_token new_token_id;
        if (temperature <= 0.0f) {
            new_token_id = llama_sample_token_greedy(handle->ctx, &candidates_p);
        } else {
            llama_sample_temp(handle->ctx, &candidates_p, temperature);
            llama_sample_top_p(handle->ctx, &candidates_p, 0.95f, 1);
            new_token_id = llama_sample_token(handle->ctx, &candidates_p);
        }

        // Check EOS
        if (llama_token_is_eog(handle->model, new_token_id)) {
            LOGI("runInference: EOS reached after %d tokens", i);
            break;
        }

        // Decode token to text
        char buf[256];
        int n = llama_token_to_piece(handle->model, new_token_id, buf, sizeof(buf), false);
        if (n > 0) {
            std::string piece(buf, n);
            cached_chars += piece;

            // Only emit valid UTF-8 sequences to Java
            if (is_valid_utf8(cached_chars.c_str())) {
                result += cached_chars;

                jstring jtoken = env->NewStringUTF(cached_chars.c_str());
                env->CallObjectMethod(onToken, invokeMethod, jtoken);
                env->DeleteLocalRef(jtoken);

                cached_chars.clear();
            }
        }

        // Next decode step
        llama_batch_clear(batch);
        llama_batch_add(batch, new_token_id, n_cur, {0}, true);
        n_cur++;

        if (llama_decode(handle->ctx, batch) != 0) {
            LOGE("runInference: decode step %d failed", i);
            break;
        }
    }

    // Flush any remaining cached chars
    if (!cached_chars.empty() && is_valid_utf8(cached_chars.c_str())) {
        result += cached_chars;
        jstring jtoken = env->NewStringUTF(cached_chars.c_str());
        env->CallObjectMethod(onToken, invokeMethod, jtoken);
        env->DeleteLocalRef(jtoken);
    }

    llama_batch_free(batch);

    LOGI("runInference: complete — generated %zu chars", result.length());
    return env->NewStringUTF(result.c_str());
}

// ── stopInference ────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_neurallite_app_engine_LlamaEngine_stopInference(
    JNIEnv *, jobject, jlong handlePtr
) {
    auto * handle = reinterpret_cast<LlamaHandle *>(handlePtr);
    if (handle) {
        handle->stop_flag.store(true);
        LOGI("stopInference: flag set");
    }
}

// ── unloadModel ──────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_neurallite_app_engine_LlamaEngine_unloadModel(
    JNIEnv *, jobject, jlong handlePtr
) {
    auto * handle = reinterpret_cast<LlamaHandle *>(handlePtr);
    if (handle) {
        if (handle->ctx) {
            llama_free(handle->ctx);
            handle->ctx = nullptr;
        }
        if (handle->model) {
            llama_free_model(handle->model);
            handle->model = nullptr;
        }
        delete handle;
        LOGI("unloadModel: memory freed");
    }
    llama_backend_free();
}

// ── getModelInfo ─────────────────────────────────────────────────────────────
JNIEXPORT jstring JNICALL
Java_com_neurallite_app_engine_LlamaEngine_getModelInfo(
    JNIEnv * env, jobject, jlong handlePtr
) {
    auto * handle = reinterpret_cast<LlamaHandle *>(handlePtr);
    if (!handle || !handle->model) {
        return env->NewStringUTF("{\"error\":\"no model loaded\"}");
    }

    char desc[128];
    llama_model_desc(handle->model, desc, sizeof(desc));

    std::stringstream ss;
    ss << "{"
       << "\"name\":\"" << desc << "\","
       << "\"nParams\":" << llama_model_n_params(handle->model) << ","
       << "\"nCtx\":"    << handle->n_ctx << ","
       << "\"nLayers\":" << llama_n_layer(handle->model)
       << "}";

    return env->NewStringUTF(ss.str().c_str());
}

} // extern "C"
