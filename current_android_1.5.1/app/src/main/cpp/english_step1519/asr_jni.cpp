#include "asr_core.h"
#include <jni.h>
#include <mutex>
namespace {
std::mutex lock;std::unique_ptr<AsrCore> core;
std::string utf(JNIEnv* env,jstring value){const char* p=env->GetStringUTFChars(value,nullptr);std::string result=p;env->ReleaseStringUTFChars(value,p);return result;}
void fail(JNIEnv* env,const std::exception& e){env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),e.what());}
}
extern "C" JNIEXPORT void JNICALL Java_com_jmu_text2scpi_voice_NativeAsr_initialize(JNIEnv* env,jclass,jstring model,jstring tokenizer,jstring cmvn,jstring lm,jstring config) {
  try {std::lock_guard<std::mutex> guard(lock);core=std::make_unique<AsrCore>(utf(env,model),utf(env,tokenizer),utf(env,cmvn),utf(env,lm),utf(env,config));} catch(const std::exception& e){fail(env,e);}
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_jmu_text2scpi_voice_NativeAsr_transcribe(JNIEnv* env,jclass,jfloatArray audio,jboolean enabled,jstring dump) {
  try {std::lock_guard<std::mutex> guard(lock);if(!core)throw std::runtime_error("Model not loaded");
    std::vector<float> samples(env->GetArrayLength(audio));env->GetFloatArrayRegion(audio,0,samples.size(),samples.data());
    auto result=core->Transcribe(samples,enabled,utf(env,dump));auto out=env->NewObjectArray(10,env->FindClass("java/lang/String"),nullptr);
    env->SetObjectArrayElement(out,0,env->NewStringUTF(result.tagged.c_str()));env->SetObjectArrayElement(out,1,env->NewStringUTF(result.lm_text.c_str()));env->SetObjectArrayElement(out,2,env->NewStringUTF(std::to_string(result.beam_ms).c_str()));env->SetObjectArrayElement(out,3,env->NewStringUTF(std::to_string(result.frames).c_str()));env->SetObjectArrayElement(out,4,env->NewStringUTF(std::to_string(result.frontend_ms).c_str()));env->SetObjectArrayElement(out,5,env->NewStringUTF(std::to_string(result.inference_ms).c_str()));env->SetObjectArrayElement(out,6,env->NewStringUTF(std::to_string(result.greedy_ms).c_str()));env->SetObjectArrayElement(out,7,env->NewStringUTF(std::to_string(result.dump_ms).c_str()));env->SetObjectArrayElement(out,8,env->NewStringUTF(result.acronym_fallback?"true":"false"));env->SetObjectArrayElement(out,9,env->NewStringUTF(result.unit_diagnostic.c_str()));return out;
  } catch(const std::exception& e){fail(env,e);return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_com_jmu_text2scpi_voice_NativeAsr_release(JNIEnv*,jclass){std::lock_guard<std::mutex> guard(lock);core.reset();}
