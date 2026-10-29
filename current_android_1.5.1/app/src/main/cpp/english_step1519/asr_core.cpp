#include "millivolt_candidate.h"
#include <regex>
#include "asr_core.h"
#include "kaldi-native-fbank/csrc/online-feature.h"
#include <algorithm>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <cmath>
#include <chrono>
#include <cstdlib>
namespace {
void check(int value,const std::string& stage) {if(value<0)throw std::runtime_error(stage+" failed ("+std::to_string(value)+")");}
std::vector<float> numbers(const std::string& line) {
  auto first=line.find('['), last=line.find(']');
  if(first==std::string::npos||last==std::string::npos)throw std::runtime_error("Invalid CMVN vector");
  std::istringstream in(line.substr(first+1,last-first-1));std::vector<float> out;float v;
  while(in>>v)out.push_back(v);return out;
}
}
Frontend::Frontend(const std::string& path) {
  std::ifstream in(path);if(!in)throw std::runtime_error("Cannot read CMVN");
  std::string line;
  while(std::getline(in,line)) {
    if(line.find("<AddShift>")!=std::string::npos) {std::getline(in,line);shift_=numbers(line);}
    else if(line.find("<Rescale>")!=std::string::npos) {std::getline(in,line);scale_=numbers(line);}
  }
  if(shift_.size()!=560||scale_.size()!=560)throw std::runtime_error("CMVN must contain 560 coefficients");
}
Features Frontend::Extract(const std::vector<float>& samples,float dither) const {
  if(samples.size()<400)throw std::runtime_error("Audio is shorter than 25 ms");
  knf::FbankOptions options;
  options.frame_opts.samp_freq=16000;options.frame_opts.frame_length_ms=25;
  options.frame_opts.frame_shift_ms=10;options.frame_opts.window_type="hamming";
  options.frame_opts.dither=dither;options.frame_opts.snip_edges=true;
  options.mel_opts.num_bins=80;options.energy_floor=0;options.use_energy=false;
  knf::OnlineFbank fbank(options);
  std::vector<float> scaled(samples.size());
  for(size_t i=0;i<samples.size();++i)scaled[i]=samples[i]*32768.0f;
  fbank.AcceptWaveform(16000,scaled.data(),scaled.size());fbank.InputFinished();
  int frames=fbank.NumFramesReady(),length=(frames+5)/6;
  if(length<1||length>200)throw std::runtime_error("Audio exceeds the 200-frame capacity; record at most 12 seconds");
  Features result{std::vector<float>(200*560,0.0f),length};
  // FunASR LFR replicates the first three frames on the left and the final frame on the right.
  for(int t=0;t<length;++t)for(int k=0;k<7;++k) {
    const float* frame=fbank.GetFrame(std::clamp(t*6+k-3,0,frames-1));
    for(int j=0;j<80;++j) {int dim=k*80+j;result.data[t*560+dim]=(frame[j]+shift_[dim])*scale_[dim];}
  }
  return result;
}
AsrCore::AsrCore(const std::string& model,const std::string& tokenizer,const std::string& cmvn,const std::string& lm,const std::string& config):frontend_(cmvn),config_(DecoderConfig::Load(config)) {
  if(!lm.empty()){lm_=std::make_unique<WordLM>(lm);auto split=lm.find_last_of("/\\");std::string extra=(split==std::string::npos?"":lm.substr(0,split+1))+"lm-acronyms.tsv";if(std::ifstream(extra).good())acronym_lm_=std::make_unique<WordLM>(extra);}
  auto status=tokenizer_.Load(tokenizer);if(!status.ok())throw std::runtime_error(status.ToString());
  if(tokenizer_.GetPieceSize()!=25055)throw std::runtime_error("Tokenizer size mismatch");
  check(rknn_init(&ctx_,const_cast<char*>(model.c_str()),0,0,nullptr),"RKNN initialization");
  try {
    if(const char* mask=std::getenv("OWON_ASR_NPU_MASK"))check(rknn_set_core_mask(ctx_,static_cast<rknn_core_mask>(std::stoi(mask))),"Set experimental NPU mask");
    rknn_input_output_num count{};check(rknn_query(ctx_,RKNN_QUERY_IN_OUT_NUM,&count,sizeof(count)),"Query IO count");
    if(count.n_input!=2||count.n_output!=1)throw std::runtime_error("Unexpected RKNN IO count");
    for(int i=0;i<2;++i){input_[i].index=i;check(rknn_query(ctx_,RKNN_QUERY_INPUT_ATTR,&input_[i],sizeof(input_[i])),"Query input");}
    output_.index=0;check(rknn_query(ctx_,RKNN_QUERY_OUTPUT_ATTR,&output_,sizeof(output_)),"Query output");
    if(input_[0].n_elems!=200*560||input_[1].n_elems!=1||output_.n_elems!=204*25055)throw std::runtime_error("Unexpected RKNN shapes");
  } catch(...) {rknn_destroy(ctx_);ctx_=0;throw;}
}
AsrCore::~AsrCore(){if(ctx_)rknn_destroy(ctx_);}
DecodeResult AsrCore::Decode(const Features& features,bool use_lm,const std::string& dump) {
  if(features.length<1||features.length>200||features.data.size()!=200*560)throw std::runtime_error("Invalid feature input");
  auto stage=std::chrono::steady_clock::now();int length=features.length;
  rknn_input inputs[2]{};
  inputs[0].index=0;inputs[0].type=RKNN_TENSOR_FLOAT32;inputs[0].fmt=input_[0].fmt;
  inputs[0].buf=const_cast<float*>(features.data.data());inputs[0].size=features.data.size()*sizeof(float);
  inputs[1].index=1;inputs[1].type=RKNN_TENSOR_INT32;inputs[1].fmt=input_[1].fmt;inputs[1].buf=&length;inputs[1].size=sizeof(length);
  check(rknn_inputs_set(ctx_,2,inputs),"Set features and real length");check(rknn_run(ctx_,nullptr),"Run RKNN");
  std::vector<float> logits(output_.n_elems);
  rknn_output output{};output.index=0;output.want_float=1;output.is_prealloc=1;
  output.buf=logits.data();output.size=logits.size()*sizeof(float);
  check(rknn_outputs_get(ctx_,1,&output,nullptr),"Read logits");
  if(std::any_of(logits.begin(),logits.begin()+(length+4)*25055,[](float x){return !std::isfinite(x);})) { rknn_outputs_release(ctx_,1,&output); throw std::runtime_error("NONFINITE_ASR_LOGITS: RKNN produced invalid values; complete audio is retained, retry without truncating speech."); }
  DecodeResult result;result.inference_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-stage).count();stage=std::chrono::steady_clock::now();result.frames=length;int previous=-1;
  for(int t=0;t<length+4;++t) {
    auto first=logits.begin()+t*25055;int id=std::max_element(first,first+25055)-first;
    if(id!=previous&&id!=0)result.ids.push_back(id);previous=id;
  }
  rknn_outputs_release(ctx_,1,&output);
  auto status=tokenizer_.Decode(result.ids,&result.tagged);if(!status.ok())throw std::runtime_error(status.ToString());
  result.greedy_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-stage).count();stage=std::chrono::steady_clock::now();
  if(!dump.empty()){std::ofstream out(dump,std::ios::binary);out.write(reinterpret_cast<const char*>(logits.data()),logits.size()*sizeof(float));if(!out)throw std::runtime_error("Cannot save probe logits");}
  result.dump_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-stage).count();
  if(use_lm){if(!lm_)throw std::runtime_error("4-gram model not loaded");auto start=std::chrono::steady_clock::now();
    // The first four query positions carry rich labels; only speech positions enter word LM decoding.
    std::string raw=std::regex_replace(result.tagged,std::regex("<\\|[^>]*\\|>"),"");const WordLM& selected=acronym_lm_&&UseAcronymModel(raw)?*acronym_lm_:*lm_;
    auto beam=CtcBeam(logits.data()+4*25055,length,25055,tokenizer_,selected,config_,false);result.lm_text=FinalizeAsrText(raw,beam.text,*lm_,&result.acronym_fallback);
    auto unit=millivolt_candidate::select(raw,result.lm_text,logits.data()+4*25055,length,25055,tokenizer_);result.lm_text=unit.text;result.unit_diagnostic=unit.reason+" voltage="+std::to_string(unit.voltage)+" time="+std::to_string(unit.time)+" power="+std::to_string(unit.power);
    result.beam_ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
  }
  return result;
}
DecodeResult AsrCore::Transcribe(const std::vector<float>& samples,bool use_lm,const std::string& dump){auto stage=std::chrono::steady_clock::now();auto features=frontend_.Extract(samples);double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-stage).count();auto result=Decode(features,use_lm,dump);result.frontend_ms=ms;return result;}
