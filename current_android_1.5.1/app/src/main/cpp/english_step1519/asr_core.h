#pragma once
#include <string>
#include <vector>
#include <memory>
#include "rknn_api.h"
#include "sentencepiece_processor.h"
#include "word_lm.h"
struct Features { std::vector<float> data; int length; };
struct DecodeResult { std::string tagged,lm_text,unit_diagnostic; std::vector<int> ids; int frames; bool acronym_fallback=false;double beam_ms=0,frontend_ms=0,inference_ms=0,greedy_ms=0,dump_ms=0; };
class Frontend {
 public:
  explicit Frontend(const std::string& cmvn);
  Features Extract(const std::vector<float>& samples, float dither=1.0f) const;
 private:
  std::vector<float> shift_,scale_;
};
class AsrCore {
 public:
  AsrCore(const std::string& model, const std::string& tokenizer, const std::string& cmvn,const std::string& lm="",const std::string& config="");
  ~AsrCore();
  AsrCore(const AsrCore&)=delete;
  DecodeResult Decode(const Features& features,bool use_lm=false,const std::string& dump="");
  DecodeResult Transcribe(const std::vector<float>& samples,bool use_lm=true,const std::string& dump="");
  const Frontend& frontend() const {return frontend_;}
 private:
  rknn_context ctx_=0;
  Frontend frontend_;
  sentencepiece::SentencePieceProcessor tokenizer_;
  std::unique_ptr<WordLM> lm_,acronym_lm_;
  DecoderConfig config_;
  rknn_tensor_attr input_[2]{},output_{};
};
