#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

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
    batch.n_seq_id[batch.n_tokens] = static_cast<int32_t>(seq_ids.size());
    for (size_t i = 0; i < seq_ids.size(); ++i) {
        batch.seq_id[batch.n_tokens][i] = seq_ids[i];
    }
    batch.logits  [batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

static std::string jbytearray_to_string(JNIEnv * env, jbyteArray arr) {
    if (!arr) return {};
    const jsize len = env->GetArrayLength(arr);
    if (len <= 0) return {};
    std::string out(static_cast<size_t>(len), '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

static jbyteArray string_to_jbytearray(JNIEnv * env, const std::string & s) {
    const jsize len = static_cast<jsize>(s.size());
    jbyteArray arr = env->NewByteArray(len);
    if (arr && len > 0) {
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(s.data()));
    }
    return arr;
}

/**
 * Splits `buf` into:
 * - `emit_out`: valid UTF-8 prefix (with any malformed bytes replaced by U+FFFD)
 * - `remaining`: at most 3 trailing bytes forming an incomplete (but valid-so-far) UTF-8 codepoint.
 * When `flush_incomplete` is true, any trailing incomplete bytes are also replaced by U+FFFD.
 */
static void extract_utf8_streamable(
    std::string & buf,
    std::string & emit_out,
    bool flush_incomplete
) {
    emit_out.clear();
    const size_t n = buf.size();
    size_t i = 0;
    size_t keep_from = n;

    while (i < n) {
        const unsigned char b0 = static_cast<unsigned char>(buf[i]);
        size_t expected = 0;
        uint32_t cp = 0;

        if ((b0 & 0x80) == 0x00) {
            emit_out.push_back(static_cast<char>(b0));
            i += 1;
            continue;
        } else if ((b0 & 0xE0) == 0xC0) {
            expected = 2;
            cp = b0 & 0x1F;
            if (cp < 2) expected = 0; // overlong 0xC0 / 0xC1
        } else if ((b0 & 0xF0) == 0xE0) {
            expected = 3;
            cp = b0 & 0x0F;
        } else if ((b0 & 0xF8) == 0xF0) {
            expected = 4;
            cp = b0 & 0x07;
            if (b0 > 0xF4) expected = 0; // > U+10FFFF
        } else {
            expected = 0;
        }

        if (expected == 0) {
            emit_out.append("\xEF\xBF\xBD");
            i += 1;
            continue;
        }

        const size_t avail = n - i;
        bool valid_cont = true;
        const size_t check_len = std::min(expected, avail);
        for (size_t k = 1; k < check_len; ++k) {
            const unsigned char bk = static_cast<unsigned char>(buf[i + k]);
            if ((bk & 0xC0) != 0x80) {
                valid_cont = false;
                break;
            }
            if (k == 1) {
                if (expected == 3 && b0 == 0xE0 && bk < 0xA0) valid_cont = false; // overlong
                if (expected == 3 && b0 == 0xED && bk >= 0xA0) valid_cont = false; // UTF-16 surrogate
                if (expected == 4 && b0 == 0xF0 && bk < 0x90) valid_cont = false; // overlong
                if (expected == 4 && b0 == 0xF4 && bk > 0x8F) valid_cont = false; // > U+10FFFF
                if (!valid_cont) break;
            }
        }

        if (!valid_cont) {
            emit_out.append("\xEF\xBF\xBD");
            i += 1;
            continue;
        }

        if (avail < expected) {
            if (flush_incomplete) {
                emit_out.append("\xEF\xBF\xBD");
                i = n;
            } else {
                keep_from = i;
            }
            break;
        }

        emit_out.append(buf, i, expected);
        i += expected;
    }

    if (keep_from < n) {
        buf = buf.substr(keep_from);
    } else {
        buf.clear();
    }
}

static std::string escape_json_string(const std::string & s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\b': out += "\\b";  break;
            case '\f': out += "\\f";  break;
            case '\n': out += "\\n";  break;
            case '\r': out += "\\r";  break;
            case '\t': out += "\\t";  break;
            default:
                if (c < 0x20) {
                    char hex[7];
                    snprintf(hex, sizeof(hex), "\\u%04x", c);
                    out += hex;
                } else {
                    out.push_back(static_cast<char>(c));
                }
                break;
        }
    }
    return out;
}

static void write_stats(JNIEnv * env, jintArray outStats, jint promptTokens, jint completionTokens, jint finishReason) {
    if (!outStats) return;
    if (env->GetArrayLength(outStats) < 3) return;
    jint buf[3] = { promptTokens, completionTokens, finishReason };
    env->SetIntArrayRegion(outStats, 0, 3, buf);
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

static bool llama_abort_callback(void * data) {
    auto * handle = reinterpret_cast<LlamaHandle *>(data);
    return handle && handle->stop_flag.load(std::memory_order_relaxed);
}

// ── Redirect llama.cpp logs to Android Logcat ────────────────────────────────

static void log_callback(ggml_log_level level, const char * fmt, void * /*data*/) {
    if (!fmt) return;
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
    jbyteArray modelPathBytes, jint nThreads, jint nCtx
) {
    std::string path = jbytearray_to_string(env, modelPathBytes);
    if (path.empty()) {
        LOGE("loadModel: empty model path");
        return -1L;
    }

    const int safeThreads = std::max(1, static_cast<int>(nThreads));
    const int safeCtx     = std::max(64, static_cast<int>(nCtx));
    const int batchSize   = std::min(safeCtx, 512);

    LOGI("loadModel: path=%s threads=%d ctx=%d batch=%d",
         path.c_str(), safeThreads, safeCtx, batchSize);

    llama_log_set(log_callback, nullptr);
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    llama_model * model = llama_load_model_from_file(path.c_str(), model_params);

    if (!model) {
        LOGE("loadModel: llama_load_model_from_file failed");
        llama_backend_free();
        return -1L;
    }

    auto * handle     = new LlamaHandle();
    handle->model     = model;
    handle->n_threads = safeThreads;
    handle->stop_flag.store(false);

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx               = static_cast<uint32_t>(safeCtx);
    ctx_params.n_batch             = static_cast<uint32_t>(batchSize);
    ctx_params.n_ubatch            = static_cast<uint32_t>(batchSize);
    ctx_params.n_threads           = static_cast<uint32_t>(safeThreads);
    ctx_params.n_threads_batch     = static_cast<uint32_t>(safeThreads);
    ctx_params.seed                = 1234;
    ctx_params.abort_callback      = llama_abort_callback;
    ctx_params.abort_callback_data = handle;

    llama_context * ctx = llama_new_context_with_model(model, ctx_params);
    if (!ctx) {
        LOGE("loadModel: llama_new_context_with_model failed");
        delete handle;
        llama_free_model(model);
        llama_backend_free();
        return -1L;
    }

    llama_set_abort_callback(ctx, llama_abort_callback, handle);
    handle->ctx   = ctx;
    handle->n_ctx = static_cast<int>(llama_n_ctx(ctx));

    LOGI("loadModel: success — handle=%p n_ctx=%d", handle, handle->n_ctx);
    return reinterpret_cast<jlong>(handle);
}

// ── runInference ─────────────────────────────────────────────────────────────
// finishReason codes in outStats[2]:
//   0 = "stop", 1 = "length", 2 = "cancelled", 3 = "error", 4 = "context_overflow"
JNIEXPORT jbyteArray JNICALL
Java_com_neurallite_app_engine_LlamaEngine_runInference(
    JNIEnv * env, jobject /* this */,
    jlong handlePtr,
    jbyteArray promptBytes,
    jint maxTokens,
    jfloat temperature,
    jboolean parseSpecial,
    jobject tokenCallback,
    jintArray outStats
) {
    auto * handle = reinterpret_cast<LlamaHandle *>(handlePtr);
    if (!handle || !handle->model || !handle->ctx) {
        LOGE("runInference: invalid handle");
        write_stats(env, outStats, 0, 0, 3);
        return string_to_jbytearray(env, "");
    }

    if (handle->stop_flag.load()) {
        LOGI("runInference: cancelled before start");
        write_stats(env, outStats, 0, 0, 2);
        return string_to_jbytearray(env, "");
    }

    const std::string prompt_str = jbytearray_to_string(env, promptBytes);
    LOGI("runInference: prompt_bytes=%zu maxTokens=%d temp=%.2f parseSpecial=%d",
         prompt_str.size(), maxTokens, temperature, parseSpecial == JNI_TRUE ? 1 : 0);

    // ── Tokenize ──
    int n_prompt_max = static_cast<int>(prompt_str.size()) + 32;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(
        handle->model,
        prompt_str.data(),
        static_cast<int32_t>(prompt_str.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,                    // add_special (BOS)
        parseSpecial == JNI_TRUE // parse_special
    );
    if (n_tokens < 0) {
        tokens.resize(static_cast<size_t>(-n_tokens));
        n_tokens = llama_tokenize(
            handle->model,
            prompt_str.data(),
            static_cast<int32_t>(prompt_str.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            parseSpecial == JNI_TRUE
        );
    }
    if (n_tokens < 0) {
        LOGE("runInference: tokenization failed");
        write_stats(env, outStats, 0, 0, 3);
        return string_to_jbytearray(env, "");
    }
    if (n_tokens == 0) {
        llama_token bos = llama_token_bos(handle->model);
        if (bos != -1) {
            tokens.resize(1);
            tokens[0] = bos;
            n_tokens = 1;
        } else {
            LOGE("runInference: zero prompt tokens and no BOS token");
            write_stats(env, outStats, 0, 0, 3);
            return string_to_jbytearray(env, "");
        }
    } else {
        tokens.resize(static_cast<size_t>(n_tokens));
    }

    LOGI("runInference: %d prompt tokens (n_ctx=%d)", n_tokens, handle->n_ctx);

    if (n_tokens >= handle->n_ctx) {
        LOGE("runInference: prompt (%d tokens) >= n_ctx (%d)", n_tokens, handle->n_ctx);
        write_stats(env, outStats, n_tokens, 0, 4);
        return string_to_jbytearray(env, "");
    }

    if (handle->stop_flag.load()) {
        LOGI("runInference: cancelled before prompt evaluation");
        write_stats(env, outStats, n_tokens, 0, 2);
        return string_to_jbytearray(env, "");
    }

    if (maxTokens <= 0) {
        write_stats(env, outStats, n_tokens, 0, 1);
        return string_to_jbytearray(env, "");
    }

    // ── Clear KV cache ──
    llama_kv_cache_clear(handle->ctx);

    // ── Chunked prompt evaluation bounded by n_batch ──
    const int n_batch_ctx = static_cast<int>(llama_n_batch(handle->ctx));
    const int batch_cap = std::max(1, std::min(512, n_batch_ctx));
    llama_batch batch = llama_batch_init(batch_cap, 0, 1);

    for (int offset = 0; offset < n_tokens; offset += batch_cap) {
        if (handle->stop_flag.load()) {
            LOGI("runInference: cancelled during prompt evaluation");
            llama_batch_free(batch);
            write_stats(env, outStats, n_tokens, 0, 2);
            return string_to_jbytearray(env, "");
        }

        llama_batch_clear(batch);
        const int chunk = std::min(batch_cap, n_tokens - offset);
        for (int j = 0; j < chunk; ++j) {
            const int pos = offset + j;
            const bool want_logits = (pos == n_tokens - 1);
            llama_batch_add(batch, tokens[static_cast<size_t>(pos)], pos, {0}, want_logits);
        }

        const int decode_ret = llama_decode(handle->ctx, batch);
        if (handle->stop_flag.load()) {
            LOGI("runInference: cancelled during prompt decode");
            llama_batch_free(batch);
            write_stats(env, outStats, n_tokens, 0, 2);
            return string_to_jbytearray(env, "");
        }
        if (decode_ret != 0) {
            LOGE("runInference: prompt decode failed at offset=%d (ret=%d)", offset, decode_ret);
            llama_batch_free(batch);
            write_stats(env, outStats, n_tokens, 0, 3);
            return string_to_jbytearray(env, "");
        }
    }

    // ── Resolve TokenCallback::onToken(byte[]) ──
    jmethodID onTokenMethod = nullptr;
    if (tokenCallback) {
        jclass callbackClass = env->GetObjectClass(tokenCallback);
        if (callbackClass) {
            onTokenMethod = env->GetMethodID(callbackClass, "onToken", "([B)V");
            env->DeleteLocalRef(callbackClass);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            onTokenMethod = nullptr;
        }
    }

    // ── Token generation loop ──
    std::string result;
    std::string cached_chars;
    std::string emit_chunk;
    int n_cur = n_tokens;
    int completion_tokens = 0;
    int finish_reason = 1; // default length if loop exhausts maxTokens
    bool callback_failed = false;

    const int n_vocab = llama_n_vocab(handle->model);
    if (n_vocab <= 0) {
        LOGE("runInference: invalid n_vocab (%d)", n_vocab);
        llama_batch_free(batch);
        write_stats(env, outStats, n_tokens, 0, 3);
        return string_to_jbytearray(env, "");
    }
    std::vector<llama_token_data> candidates;
    candidates.reserve(static_cast<size_t>(n_vocab));

    for (int i = 0; i < maxTokens; ++i) {
        if (handle->stop_flag.load()) {
            LOGI("runInference: stopped via stop_flag");
            finish_reason = 2;
            break;
        }

        float * logits = llama_get_logits_ith(handle->ctx, -1);
        if (!logits) {
            LOGE("runInference: null logits at step %d", i);
            finish_reason = 3;
            break;
        }

        candidates.clear();
        for (llama_token tid = 0; tid < n_vocab; ++tid) {
            candidates.push_back({tid, logits[tid], 0.0f});
        }

        llama_token_data_array candidates_p = {
            candidates.data(), candidates.size(), false
        };

        llama_token new_token_id;
        if (!std::isfinite(temperature) || temperature < 1e-4f) {
            new_token_id = llama_sample_token_greedy(handle->ctx, &candidates_p);
        } else {
            llama_sample_temp(handle->ctx, &candidates_p, temperature);
            llama_sample_top_p(handle->ctx, &candidates_p, 0.95f, 1);
            new_token_id = llama_sample_token(handle->ctx, &candidates_p);
        }

        // Check end-of-generation
        if (llama_token_is_eog(handle->model, new_token_id)) {
            LOGI("runInference: EOG reached after %d tokens", completion_tokens);
            finish_reason = 0;
            break;
        }

        // Convert token piece safely (resize if > 256 bytes)
        char stack_buf[256];
        int n = llama_token_to_piece(handle->model, new_token_id, stack_buf, sizeof(stack_buf), false);
        if (n < 0) {
            std::vector<char> dyn_buf(static_cast<size_t>(-n));
            int n2 = llama_token_to_piece(handle->model, new_token_id, dyn_buf.data(), static_cast<int32_t>(dyn_buf.size()), false);
            if (n2 > 0) {
                cached_chars.append(dyn_buf.data(), static_cast<size_t>(n2));
            }
        } else if (n > 0) {
            cached_chars.append(stack_buf, static_cast<size_t>(n));
        }

        completion_tokens++;

        if (!cached_chars.empty()) {
            extract_utf8_streamable(cached_chars, emit_chunk, false);
            if (!emit_chunk.empty()) {
                result += emit_chunk;
                if (tokenCallback && onTokenMethod) {
                    jbyteArray jtoken = string_to_jbytearray(env, emit_chunk);
                    if (jtoken) {
                        env->CallVoidMethod(tokenCallback, onTokenMethod, jtoken);
                        env->DeleteLocalRef(jtoken);
                    }
                    if (env->ExceptionCheck()) {
                        LOGW("runInference: Kotlin callback threw exception; stopping");
                        env->ExceptionClear();
                        callback_failed = true;
                        finish_reason = 2;
                        break;
                    }
                    if (handle->stop_flag.load()) {
                        LOGI("runInference: stopped during token callback");
                        finish_reason = 2;
                        break;
                    }
                }
            }
        }

        if (handle->stop_flag.load()) {
            LOGI("runInference: stopped before next decode step %d", i);
            finish_reason = 2;
            break;
        }

        // Stop before decoding past maxTokens or n_ctx
        if (i + 1 >= maxTokens) {
            finish_reason = 1;
            break;
        }
        if (n_cur >= handle->n_ctx) {
            LOGI("runInference: context window full (n_cur=%d)", n_cur);
            finish_reason = 1;
            break;
        }

        llama_batch_clear(batch);
        llama_batch_add(batch, new_token_id, n_cur, {0}, true);
        n_cur++;

        const int decode_ret = llama_decode(handle->ctx, batch);
        if (handle->stop_flag.load()) {
            LOGI("runInference: cancelled during decode step %d", i);
            finish_reason = 2;
            break;
        }
        if (decode_ret != 0) {
            LOGE("runInference: decode step %d failed (ret=%d)", i, decode_ret);
            finish_reason = 3;
            break;
        }
    }

    // Flush any remaining buffered bytes safely (only if generation was not cancelled/aborted)
    if (!callback_failed && !handle->stop_flag.load() && !cached_chars.empty()) {
        extract_utf8_streamable(cached_chars, emit_chunk, true);
        if (!emit_chunk.empty()) {
            result += emit_chunk;
            if (tokenCallback && onTokenMethod) {
                jbyteArray jtoken = string_to_jbytearray(env, emit_chunk);
                if (jtoken) {
                    env->CallVoidMethod(tokenCallback, onTokenMethod, jtoken);
                    env->DeleteLocalRef(jtoken);
                }
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                    finish_reason = 2;
                }
            }
        }
    }

    llama_batch_free(batch);
    write_stats(env, outStats, n_tokens, completion_tokens, finish_reason);

    LOGI("runInference: complete — prompt=%d completion=%d reason=%d bytes=%zu",
         n_tokens, completion_tokens, finish_reason, result.size());
    return string_to_jbytearray(env, result);
}

// ── resetStopFlag ────────────────────────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_neurallite_app_engine_LlamaEngine_resetStopFlag(
    JNIEnv *, jobject, jlong handlePtr
) {
    auto * handle = reinterpret_cast<LlamaHandle *>(handlePtr);
    if (handle) {
        handle->stop_flag.store(false);
    }
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
            llama_set_abort_callback(handle->ctx, nullptr, nullptr);
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

    char desc[128] = {0};
    llama_model_desc(handle->model, desc, sizeof(desc));

    std::stringstream ss;
    ss << "{"
       << "\"name\":\"" << escape_json_string(desc) << "\","
       << "\"nParams\":" << llama_model_n_params(handle->model) << ","
       << "\"nCtx\":"    << handle->n_ctx << ","
       << "\"nLayers\":" << llama_n_layer(handle->model)
       << "}";

    return env->NewStringUTF(ss.str().c_str());
}

} // extern "C"
