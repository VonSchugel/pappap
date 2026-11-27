#include "llama.h"
#include <jni.h>
#include <algorithm>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include <thread>

namespace {
template<class T, void (*Release)(T*)>
using Owned = std::unique_ptr<T, decltype(Release)>;

std::string complete(JNIEnv *env, const std::string& path, const std::string& prompt, int limit, bool planning) {
    static std::once_flag init;
    std::call_once(init, llama_backend_init);
    auto params = llama_model_default_params();
    params.n_gpu_layers = 0;
    Owned<llama_model, llama_model_free> model(llama_model_load_from_file(path.c_str(), params), llama_model_free);
    if (!model) throw std::runtime_error("Could not open the installed model.");
    const auto* vocab = llama_model_get_vocab(model.get());
    const int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
    if (count <= 0 || count > 2048 - limit) throw std::runtime_error("Shorten the input to fit the model context.");
    std::vector<llama_token> tokens(count);
    if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) != count)
        throw std::runtime_error("Could not tokenize the input.");

    auto cp = llama_context_default_params();
    cp.n_ctx = count + limit;
    cp.n_batch = 128;
    cp.n_ubatch = 128;
    cp.n_threads = cp.n_threads_batch = std::clamp(static_cast<int>(std::thread::hardware_concurrency()), 1, 4);
    Owned<llama_context, llama_free> ctx(llama_init_from_model(model.get(), cp), llama_free);
    if (!ctx) throw std::runtime_error("Insufficient memory for local inference.");
    Owned<llama_batch_ext, llama_batch_ext_free> batch(llama_batch_ext_init(ctx.get()), llama_batch_ext_free);
    Owned<llama_sampler, llama_sampler_free> sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), llama_sampler_free);
    if (!batch || !sampler) throw std::runtime_error("Could not start local inference.");
    if (planning) {
        // Constrain the shape only. The model still chooses the instructions.
        const char* format = R"gbnf(root ::= "1. " step "\n2. " step "\n3. " step
step ::= [A-Za-z] [^\r\n<>]{1,85}
)gbnf";
        auto* grammar = llama_sampler_init_grammar(vocab, format, "root");
        if (!grammar) throw std::runtime_error("Could not prepare the workflow format.");
        llama_sampler_chain_add(sampler.get(), grammar);
    }
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
    jclass threadClass = env->FindClass("java/lang/Thread");
    const auto current = env->GetStaticMethodID(threadClass, "currentThread", "()Ljava/lang/Thread;");
    const auto interrupted = env->GetMethodID(threadClass, "isInterrupted", "()Z");
    jobject thread = env->CallStaticObjectMethod(threadClass, current);
    auto decode = [&](int begin, int end) {
        if (env->CallBooleanMethod(thread, interrupted)) throw std::runtime_error("Generation stopped.");
        llama_batch_ext_clear(batch.get());
        for (llama_pos pos = begin; pos < end; ++pos) {
            int index = llama_batch_ext_add_token(batch.get(), 0, tokens[pos]);
            if (index < 0 || !llama_batch_ext_set_pos(batch.get(), index, &pos))
                throw std::runtime_error("Could not prepare model input.");
        }
        llama_batch_ext_set_output_logits(batch.get(), end - begin - 1, true);
        if (llama_process(ctx.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0)
            throw std::runtime_error("The phone could not evaluate this input.");
    };
    for (int i = 0; i < count; i += 128) decode(i, std::min(count, i + 128));
    std::string text;
    for (int i = 0; i < limit; ++i) {
        auto token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        std::vector<char> bytes(128);
        int n = llama_token_to_piece(vocab, token, bytes.data(), bytes.size(), 0, false);
        if (n < 0) {
            bytes.resize(-n);
            n = llama_token_to_piece(vocab, token, bytes.data(), bytes.size(), 0, false);
        }
        if (n < 0) throw std::runtime_error("Could not decode a model token.");
        text.append(bytes.data(), n);
        tokens.push_back(token);
        if (i + 1 < limit) decode(count + i, count + i + 1);
    }
    return text;
}
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_ma_marqicci_inference_LocalInference_generate(JNIEnv* env, jclass, jstring model, jbyteArray data, jint limit, jboolean planning) {
    try {
        if (limit < 1 || limit > 256) throw std::runtime_error("Unsupported generation length.");
        const char* chars = env->GetStringUTFChars(model, nullptr);
        if (!chars) return nullptr;
        std::string path(chars);
        env->ReleaseStringUTFChars(model, chars);
        std::string prompt(env->GetArrayLength(data), '\0');
        env->GetByteArrayRegion(data, 0, prompt.size(), reinterpret_cast<jbyte*>(prompt.data()));
        if (env->ExceptionCheck()) return nullptr;
        auto out = complete(env, path, prompt, limit, planning == JNI_TRUE);
        auto result = env->NewByteArray(out.size());
        if (result) env->SetByteArrayRegion(result, 0, out.size(), reinterpret_cast<const jbyte*>(out.data()));
        return result;
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
        return nullptr;
    }
}
