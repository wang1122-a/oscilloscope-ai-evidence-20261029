#pragma once
#include <string>
#include <vector>
#include <unordered_map>
#include <unordered_set>
#include "sentencepiece_processor.h"
struct ScoredCandidate {std::string text;std::vector<int> ids;double acoustic=0,lm=0,combined=0;};
struct BeamResult {std::string text;std::vector<int> ids;double acoustic=0,lm=0,combined=0;size_t candidates=0,evaluated=0;std::vector<ScoredCandidate> nbest;};
std::vector<std::string> LexicalWords(const std::string& text);
struct DecoderConfig {double alpha=1.0,prefix_weight=.35,word_bonus=.2;int beam_size=64,topk=64;static DecoderConfig Load(const std::string& path);};
class WordLM {
 public:
  explicit WordLM(const std::string& path);
  double Prob(const std::string& word,const std::vector<std::string>& history) const;
  double Score(const std::string& text,bool complete=true) const;
  double PartialScore(const std::string& text,double prefix_weight,int* completed_words=nullptr,double* completed_score=nullptr)const;
  int WordCount(const std::string& text,bool complete)const;
  std::string RescoreAcronymSpelling(const std::string& text)const;
  size_t VocabularySize() const{return vocab_.size();}
 private:
  struct Counts {std::unordered_map<std::string,int> next;std::unordered_map<std::string,double> prefix_counts;double total=0;};
  std::unordered_map<std::string,Counts> counts_;
  std::unordered_set<std::string> vocab_;
  mutable std::unordered_map<std::string,double> probability_cache_;
  std::unordered_map<std::string,std::vector<std::string>> prefixes_;
  mutable std::unordered_map<std::string,double> prefix_probability_cache_;
  double PrefixProb(const std::string& fragment,const std::vector<std::string>& history)const;
};
std::vector<std::string> DetectAcronyms(const std::string& text);
std::string FormatAcronyms(const std::string& text);
bool UseAcronymModel(const std::string& raw);
std::string FinalizeAsrText(const std::string& raw,const std::string& decoded,const WordLM& lm,bool* fallback=nullptr);
BeamResult CtcBeam(const float* logits,int frames,int vocab,sentencepiece::SentencePieceProcessor& tokenizer,const WordLM& lm,const DecoderConfig& config,bool normalized_acoustic=true);
