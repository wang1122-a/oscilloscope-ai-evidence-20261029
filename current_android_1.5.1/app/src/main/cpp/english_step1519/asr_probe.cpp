#include "millivolt_candidate.h"
#include "asr_core.h"
#include <fstream>
#include <iostream>
#include <cstring>
#include <chrono>
#include <cstdlib>
#include <sstream>
template<class T> std::vector<T> npy(const std::string& path,size_t count) {
  std::ifstream in(path,std::ios::binary);char head[8];in.read(head,8);
  if(!in||std::memcmp(head,"\x93NUMPY",6))throw std::runtime_error("Invalid NPY "+path);
  uint16_t size=0;in.read(reinterpret_cast<char*>(&size),2);std::string metadata(size,' ');in.read(metadata.data(),size);
  if(metadata.find("False")==std::string::npos)throw std::runtime_error("Fortran NPY unsupported");
  std::vector<T> result(count);in.read(reinterpret_cast<char*>(result.data()),count*sizeof(T));
  if(!in)throw std::runtime_error("Short NPY data");return result;
}
int main(int argc,char** argv) {
  try {
    if(argc==2&&std::string(argv[1])=="--unit-score-check"){
      if(!millivolt_candidate::enough(-6.565184,-3.740018,1.896828))throw std::runtime_error("Retained live 100mV score receipt rejected");
      if(millivolt_candidate::enough(-40,-3.740018,1.896828))throw std::runtime_error("Weak acoustic candidate accepted");
      std::cout<<"UNIT_SCORE_CHECK\tretained_live_scores_pass\tweak_candidate_rejected\n";return 0;
    }
    if(argc==7&&std::string(argv[1])=="--unit-check"){
      sentencepiece::SentencePieceProcessor tokenizer;if(!tokenizer.Load(argv[2]).ok())throw std::runtime_error("Missing tokenizer");
      std::ifstream in(argv[3],std::ios::binary);std::vector<float> logits(204*25055);in.read(reinterpret_cast<char*>(logits.data()),logits.size()*4);if(!in)throw std::runtime_error("Short unit logits");
      auto result=millivolt_candidate::select(argv[5],argv[6],logits.data()+4*25055,std::stoi(argv[4]),25055,tokenizer);
      std::cout<<"UNIT\t"<<result.text<<"\t"<<result.reason<<"\t"<<result.voltage<<"\t"<<result.time<<"\t"<<result.power<<"\n";return 0;
    }
    if(argc==5&&std::string(argv[1])=="--batch"){
      std::string root=argv[2];AsrCore core(root+"/model.rknn",root+"/chn_jpn_yue_eng_ko_spectok.bpe.model",root+"/am.mvn");
      std::ifstream list(argv[3]);if(!list)throw std::runtime_error("Missing batch list");std::string line;float dither=std::stof(argv[4]);
      while(std::getline(list,line)){
        if(!line.empty()&&line.back()=='\r')line.pop_back();
        std::istringstream row(line);std::string id,path,prefix;std::getline(row,id,'\t');std::getline(row,path,'\t');std::getline(row,prefix,'\t');if(id.empty())continue;
        std::ifstream in(path,std::ios::binary|std::ios::ate);if(!in)throw std::runtime_error("Missing input "+path);size_t n=in.tellg();in.seekg(0);if(n%4)throw std::runtime_error("Invalid float input");std::vector<float> audio(n/4);in.read(reinterpret_cast<char*>(audio.data()),n);
        // The production frontend stays unchanged. Only diagnostic replay fixes the random seed.
        std::srand(20261019);auto features=core.frontend().Extract(audio,dither);
        std::ofstream out(prefix+".features.f32",std::ios::binary);out.write(reinterpret_cast<const char*>(features.data.data()),features.data.size()*4);out.close();
        auto r=core.Decode(features,false,prefix+".logits.f32");std::cout<<"WAVE\t"<<id<<"\t"<<r.frames<<"\t"<<r.tagged<<"\t"<<r.inference_ms<<"\n"<<std::flush;
      }return 0;
    }
    if((argc==7&&(std::string(argv[1])=="--replay"||std::string(argv[1])=="--replay-fast"))||(argc==8&&std::string(argv[1])=="--replay-app")){
      sentencepiece::SentencePieceProcessor tokenizer;auto status=tokenizer.Load(argv[2]);if(!status.ok())throw std::runtime_error(status.ToString());WordLM lm(argv[3]);auto config=DecoderConfig::Load(argv[4]);
      std::ifstream in(argv[5],std::ios::binary);std::vector<float> logits(204*25055);in.read(reinterpret_cast<char*>(logits.data()),logits.size()*sizeof(float));if(!in)throw std::runtime_error("Incomplete replay logits");int frames=std::stoi(argv[6]);if(frames<1||frames>200)throw std::runtime_error("Invalid frames");
bool app=std::string(argv[1])=="--replay-app";std::unique_ptr<WordLM> extra;std::string raw=app?argv[7]:"";if(app&&UseAcronymModel(raw)){std::string path=argv[3];path=path.substr(0,path.find_last_of("/")+1)+"lm-acronyms.tsv";extra=std::make_unique<WordLM>(path);}auto begin=std::chrono::steady_clock::now();auto result=CtcBeam(logits.data()+4*25055,frames,25055,tokenizer,extra?*extra:lm,config,std::string(argv[1])=="--replay");bool fallback=false;if(app)result.text=FinalizeAsrText(raw,result.text,lm,&fallback);double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();std::cout<<"REPLAY\t"<<result.text<<"\t"<<result.acoustic<<"\t"<<result.lm<<"\t"<<ms<<"\t"<<result.candidates<<"\t"<<result.evaluated<<"\n";std::cout<<"FORMATTED\t"<<FormatAcronyms(result.text)<<"\n";if(app)std::cout<<"ROUTE\t"<<(extra?"acronym":"default")<<"\t"<<fallback<<"\n";return 0;
    }
    if(argc==4&&std::string(argv[1])=="--lm-score"){WordLM lm(argv[2]);std::cout.precision(17);std::cout<<"SCORE\t"<<lm.Score(argv[3],true)<<"\t"<<lm.Score(argv[3],false)<<"\t"<<lm.PartialScore(argv[3],.35)<<"\n";return 0;}
    if(argc==4&&std::string(argv[1])=="--acronym-rescore"){WordLM lm(argv[2]);std::cout<<"RESCORE\t"<<lm.RescoreAcronymSpelling(argv[3])<<"\n";return 0;}
    if(argc==5&&std::string(argv[1])=="--frontend") {
      Frontend frontend(argv[2]);std::ifstream in(argv[3],std::ios::binary|std::ios::ate);size_t size=in.tellg();in.seekg(0);
      std::vector<float> samples(size/sizeof(float));in.read(reinterpret_cast<char*>(samples.data()),size);
      auto features=frontend.Extract(samples,0);std::ofstream out(argv[4],std::ios::binary);
      out.write(reinterpret_cast<char*>(features.data.data()),features.data.size()*sizeof(float));std::cout<<"frames="<<features.length<<"\n";return 0;
    }
    if(argc!=3&&argc!=4)throw std::runtime_error("Usage: asr_probe speech-dir probes-dir [--lm]");
    bool lm=argc==4;std::string root=argv[1], probes=argv[2];AsrCore core(root+"/model.rknn",root+"/chn_jpn_yue_eng_ko_spectok.bpe.model",root+"/am.mvn",lm?root+"/lm.tsv":"",lm?root+"/decoder.cfg":"");
    for(int i=0;i<4;++i) {
      std::string name="probe_0"+std::to_string(i);auto x=npy<float>(probes+"/"+name+"_x.npy",200*560);auto length=npy<int32_t>(probes+"/"+name+"_length.npy",1);
      auto result=core.Decode({std::move(x),length[0]},lm,lm?probes+"/"+name+".logits.f32":"");std::cout<<"PROBE\t"<<name<<"\t";
      std::cout.flush();
      for(size_t j=0;j<result.ids.size();++j)std::cout<<(j?",":"")<<result.ids[j];std::cout<<"\t"<<result.tagged<<"\n";
      if(lm)std::cout<<"LM\t"<<name<<"\t"<<result.lm_text<<"\t"<<result.beam_ms<<"\n";
      std::cout<<"STAGES\t"<<name<<"\t"<<result.frontend_ms<<"\t"<<result.inference_ms<<"\t"<<result.greedy_ms<<"\t"<<result.dump_ms<<"\t"<<result.beam_ms<<"\n";
    }
    return 0;
  } catch(const std::exception& e){std::cerr<<"FAILED: "<<e.what()<<"\n";return 1;}
}
