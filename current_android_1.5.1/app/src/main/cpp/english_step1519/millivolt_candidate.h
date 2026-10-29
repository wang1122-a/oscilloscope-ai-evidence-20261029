#pragma once
#include <algorithm>
#include <cmath>
#include <limits>
#include <regex>
#include <string>
#include <vector>
#include "sentencepiece_processor.h"

namespace millivolt_candidate {
inline double add(double a,double b){if(!std::isfinite(a))return b;if(!std::isfinite(b))return a;return std::max(a,b)+std::log1p(std::exp(-std::abs(a-b)));}
// Exact CTC forward score of one tokenization. Per-frame maxima are common to
// every candidate, so omitting the softmax constant cannot change comparisons.
inline double acoustic(const std::string& text,const float* logits,int frames,int vocab,sentencepiece::SentencePieceProcessor& tokenizer){
    std::vector<int> ids;if(!tokenizer.Encode(text,&ids).ok()||ids.empty()||ids.size()>static_cast<size_t>(frames))return -std::numeric_limits<double>::infinity();
    std::vector<int> labels{0};for(int id:ids){if(id<1||id>=vocab)return -std::numeric_limits<double>::infinity();labels.push_back(id);labels.push_back(0);}
    double neg=-std::numeric_limits<double>::infinity();std::vector<double> previous(labels.size(),neg),next(labels.size(),neg);previous[0]=0;
    for(int t=0;t<frames;t++){
        const float* row=logits+t*vocab;double maximum=*std::max_element(row,row+vocab);
        for(size_t j=0;j<labels.size();j++){
            double p=previous[j];if(j>0)p=add(p,previous[j-1]);if(j>1&&labels[j]&&labels[j]!=labels[j-2])p=add(p,previous[j-2]);next[j]=p+row[labels[j]]-maximum;
        }previous.swap(next);
    }return add(previous.back(),previous[previous.size()-2]);
}
inline bool enough(double voltage,double time,double power){return std::isfinite(voltage)&&voltage>=time-4.0&&voltage>=power-9.0;}
struct Result {std::string text,reason="unchanged";double voltage=0,time=0,power=0;};
inline Result select(const std::string& raw,const std::string& decoded,const float* logits,int frames,int vocab,sentencepiece::SentencePieceProcessor& tokenizer){
    Result out;out.text=decoded;
    // This path is only for an abbreviated/fragmented milli-unit from greedy
    // recognition plus an explicit vertical scale. Normal requests keep the
    // original decoder result, weights, beam size and audio processing.
    if(!std::regex_search(raw,std::regex("\\b(?:mWs?|mill[a-z]{0,8})\\b",std::regex::icase)))return out;
    if(std::regex_search(raw,std::regex("\\b(?:milliseconds?|milliwatts?)\\b",std::regex::icase)))return out;
    if(!std::regex_search(raw+" "+decoded,std::regex("\\b(?:vertical|voltage)\\s+(?:scale|sensitivity)\\b",std::regex::icase)))return out;
    std::regex unit("\\b(?:ms|milliseconds?)\\b",std::regex::icase);std::smatch match;
    if(!std::regex_search(decoded,match,unit))return out;
    if(std::regex_search(match.suffix().str(),unit))return out;
    auto candidate=[&](const std::string& replacement){return decoded.substr(0,match.position())+replacement+decoded.substr(match.position()+match.length());};
    auto score=[&](const std::string& spelling){return acoustic(candidate(spelling),logits,frames,vocab,tokenizer);};
    out.voltage=std::max(score("mV"),score("millivolts"));
    out.time=std::max(score("ms"),score("milliseconds"));out.power=std::max(score("mW"),score("mWs"));
    // Bounded domain prior; clear acoustic evidence for another word wins.
    // Only the unit span can change. The CTC decoder's other text is untouched.
    if(enough(out.voltage,out.time,out.power)){out.text=candidate("mV");out.reason="vertical_millivolt_acoustic_candidate";}
    else out.reason="unit_acoustic_evidence_insufficient";
    return out;
}
}
