#include "word_lm.h"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <numeric>
#include <sstream>
#include <stdexcept>
#include <limits>
#include <cctype>
#include <cstdlib>
#include <regex>
namespace {
constexpr double NEG=-std::numeric_limits<double>::infinity();
std::string key(const std::vector<std::string>& words){std::string s;for(size_t i=0;i<words.size();++i){if(i)s+='\x1f';s+=words[i];}return s;}
std::vector<std::string> words(std::string text){
  auto letter=[](char c){return (c>='a'&&c<='z')||(c>='A'&&c<='Z');};auto digit=[](char c){return c>='0'&&c<='9';};
  std::vector<std::string> result;
  for(size_t i=0;i<text.size();){size_t begin=i;
    if(letter(text[i])){while(i<text.size()&&letter(text[i]))++i;if(i+1<text.size()&&text[i]=='\''&&letter(text[i+1])){++i;while(i<text.size()&&letter(text[i]))++i;}}
    else if(digit(text[i])||((text[i]=='-'||text[i]=='+')&&i+1<text.size()&&digit(text[i+1]))){if(text[i]=='-'||text[i]=='+')++i;while(i<text.size()&&digit(text[i]))++i;if(i+1<text.size()&&text[i]=='.'&&digit(text[i+1])){++i;while(i<text.size()&&digit(text[i]))++i;}}
    else{++i;continue;}
    std::string word=text.substr(begin,i-begin);
    static const std::unordered_map<std::string,std::string> units={{"MHz","megahertz"},{"mHz","millihertz"},{"kHz","kilohertz"},{"GHz","gigahertz"},{"mV","millivolts"},{"MV","megavolts"},{"V","volts"},{"ms","milliseconds"},{"us","microseconds"},{"ns","nanoseconds"}};
    auto unit=units.find(word);if(unit!=units.end())word=unit->second;else for(char& c:word)if(c>='A'&&c<='Z')c+='a'-'A';
    result.push_back(word);
  }return result;
}
double logadd(double a,double b){if(a==NEG)return b;if(b==NEG)return a;double hi=std::max(a,b);return hi+std::log1p(std::exp(std::min(a,b)-hi));}
struct PrefixHash {size_t operator()(const std::vector<int>& p)const{size_t h=1469598103934665603ULL;for(int id:p){h^=id;h*=1099511628211ULL;}return h;}};
struct Part {double blank=NEG,nonblank=NEG,rank=NEG,upper=NEG;};
struct TextScore {double partial=0,completed_score=0;int completed_words=0;};
struct Cached {std::string text;TextScore score;};
const std::vector<std::string> acronym_catalog={"rms","fft","usb","csv","bmp","png","bin","zip","vpp","ac","dc"};

}
std::vector<std::string> LexicalWords(const std::string& text){return words(text);}
std::vector<std::string> DetectAcronyms(const std::string& text){
  auto seq=words(text);std::vector<std::string> found;
  for(const auto& acronym:acronym_catalog){bool present=false;
    for(size_t i=0;i<seq.size()&&!present;++i){std::string joined;for(size_t j=i;j<seq.size();++j){joined+=seq[j];if(joined.size()>acronym.size())break;if(joined==acronym){present=true;break;}}}
    if(present)found.push_back(acronym);
  }return found;
}
std::string FormatAcronyms(const std::string& text){
  std::string result=text;
  for(const auto& acronym:acronym_catalog){std::string pattern="\\b",label=acronym;
    for(size_t i=0;i<acronym.size();++i){if(i)pattern+="[ \\t]*";pattern+=acronym[i];}pattern+="\\b";
    for(char& c:label)c=std::toupper(static_cast<unsigned char>(c));
    result=std::regex_replace(result,std::regex(pattern,std::regex::icase),label);
  }return result;
}
namespace {
int edit_distance(const std::string& a,const std::string& b){
  std::vector<int> row(b.size()+1);std::iota(row.begin(),row.end(),0);
  for(size_t i=0;i<a.size();++i){int diagonal=row[0];row[0]=i+1;for(size_t j=0;j<b.size();++j){int old=row[j+1];row[j+1]=std::min({row[j+1]+1,row[j]+1,diagonal+(a[i]!=b[j])});diagonal=old;}}return row.back();
}
std::string join_words(const std::vector<std::string>& seq){std::string s;for(const auto& w:seq){if(!s.empty())s+=' ';s+=w;}return s;}
}
std::string WordLM::RescoreAcronymSpelling(const std::string& text)const{
  if(DetectAcronyms(text).empty())return text;
  // Only acronym-bearing utterances use this word lattice. Existing vocabulary
  // words, acronym letters, and numbers cannot be edited individually. Unknown
  // spelling fragments may join one adjacent fragment before LM rescoring.
  auto input=words(FormatAcronyms(text));if(input.empty())return text;
  struct Candidate{std::vector<std::string> seq;int edits=0;double score=0;};
  std::vector<std::vector<Candidate>> lattice(input.size()+1);lattice[0].push_back({});
  auto rank=[&](Candidate& c){c.score=.75*Score(join_words(c.seq),false)+.5*c.seq.size()-2.0*c.edits;};
  auto trim=[&](std::vector<Candidate>& cs){for(auto& c:cs)rank(c);std::sort(cs.begin(),cs.end(),[](const auto& a,const auto& b){return a.score==b.score?a.seq<b.seq:a.score>b.score;});if(cs.size()>16)cs.resize(16);};
  for(size_t i=0;i<input.size();++i){trim(lattice[i]);
    std::vector<std::pair<std::string,int>> single{{input[i],0}},merged;
    for(size_t span=1;span<=2&&i+span<=input.size();++span){std::string joined;bool unknown=false,eligible=true;
      for(size_t j=i;j<i+span;++j){joined+=input[j];unknown|=!vocab_.count(input[j]);if(!std::all_of(input[j].begin(),input[j].end(),[](char c){return c>='a'&&c<='z';})||std::find(acronym_catalog.begin(),acronym_catalog.end(),input[j])!=acronym_catalog.end())eligible=false;}
      if(!unknown||!eligible||joined.size()<4)continue;
      for(const auto& target:vocab_){if(target.empty()||target[0]=='<'||target.size()<4||std::find(acronym_catalog.begin(),acronym_catalog.end(),target)!=acronym_catalog.end()||!std::all_of(target.begin(),target.end(),[](char c){return c>='a'&&c<='z';}))continue;
        int limit=joined.size()>=5&&target.size()>=6?2:1;if(std::abs(static_cast<int>(joined.size())-static_cast<int>(target.size()))>limit)continue;int distance=edit_distance(joined,target);
        if(distance>0&&distance<=limit){if(span==1)single.emplace_back(target,distance);else merged.emplace_back(target,distance);}
      }
    }
    for(const auto& parent:lattice[i]){
      for(const auto& proposal:single){auto c=parent;c.seq.push_back(proposal.first);c.edits+=proposal.second;lattice[i+1].push_back(std::move(c));}
      for(const auto& proposal:merged){auto c=parent;c.seq.push_back(proposal.first);c.edits+=proposal.second;lattice[i+2].push_back(std::move(c));}
    }
  }
  trim(lattice.back());double original=.75*Score(join_words(input),true)+.5*input.size();double best_score=original;std::string best;
  for(const auto& c:lattice.back()){double score=.75*Score(join_words(c.seq),true)+.5*c.seq.size()-2.0*c.edits;if(score>original+2.0&&score>best_score){best_score=score;best=join_words(c.seq);}}
  if(best.empty())return FormatAcronyms(text);
  best=FormatAcronyms(best);if(!best.empty())best[0]=std::toupper(static_cast<unsigned char>(best[0]));if(!text.empty()&&(text.back()=='.'||text.back()=='?'||text.back()=='!'))best+=text.back();return best;
}
bool UseAcronymModel(const std::string& raw){return !DetectAcronyms(raw).empty()||std::regex_search(raw,std::regex("\\b[RM]\\b"));}
std::string FinalizeAsrText(const std::string& raw,const std::string& decoded,const WordLM& lm,bool* fallback){
  auto expected=DetectAcronyms(raw),actual=DetectAcronyms(decoded);bool missing=false;
  for(const auto& name:expected)missing|=std::find(actual.begin(),actual.end(),name)==actual.end();
  if(fallback)*fallback=missing;
  return missing?lm.RescoreAcronymSpelling(raw):FormatAcronyms(decoded);
}
WordLM::WordLM(const std::string& path){
  std::ifstream in(path);std::string line;if(!std::getline(in,line))throw std::runtime_error("Missing 4-gram header");if(!line.empty()&&line.back()=='\r')line.pop_back();if(line!="WITTEN_BELL_4GRAM\t1")throw std::runtime_error("Invalid 4-gram header");
  while(std::getline(in,line)){if(!line.empty()&&line.back()=='\r')line.pop_back();std::istringstream row(line);std::vector<std::string> parts;std::string p;while(std::getline(row,p,'\t'))parts.push_back(p);
    if(parts.empty())continue;int n=std::stoi(parts[0]);if(n<0||n>3||parts.size()!=static_cast<size_t>(n+3))throw std::runtime_error("Invalid 4-gram row");
    std::vector<std::string> h(parts.begin()+1,parts.begin()+1+n);int count=std::stoi(parts.back());if(count<=0)throw std::runtime_error("Invalid 4-gram count");
    auto& c=counts_[key(h)];const auto& word=parts[n+1];if(!c.next.emplace(word,count).second)throw std::runtime_error("Duplicate 4-gram entry");c.total+=count;
    if(!word.empty()&&word[0]!='<')for(size_t j=1;j<=word.size();++j)c.prefix_counts[word.substr(0,j)]+=count;
  }
  auto base=counts_.find("");if(base==counts_.end())throw std::runtime_error("Missing unigram counts");
  for(const auto& pair:base->second.next)vocab_.insert(pair.first);vocab_.insert("<unk>");vocab_.erase("<s>");
  for(const auto& word:vocab_)if(!word.empty()&&word[0]!='<')for(size_t n=1;n<=word.size();++n)prefixes_[word.substr(0,n)].push_back(word);
}
DecoderConfig DecoderConfig::Load(const std::string& path){DecoderConfig c;if(!path.empty()){std::ifstream in(path);if(!(in>>c.alpha>>c.beam_size>>c.topk>>c.prefix_weight>>c.word_bonus))throw std::runtime_error("Invalid decoder configuration");}if(c.alpha<0||c.alpha>3||c.beam_size<1||c.beam_size>128||c.topk<1||c.topk>128||c.prefix_weight<0||c.prefix_weight>1||c.word_bonus<0||c.word_bonus>2)throw std::runtime_error("Decoder parameter out of range");return c;}
double WordLM::Prob(const std::string& word,const std::vector<std::string>& original)const{
  std::string w=vocab_.count(word)?word:"<unk>";std::vector<std::string> h=original;if(h.size()>3)h.erase(h.begin(),h.end()-3);
  std::string cache_key=key(h)+'\x1e'+w;auto cached=probability_cache_.find(cache_key);if(cached!=probability_cache_.end())return cached->second;
  double probability=1.0/vocab_.size();
  for(size_t n=0;n<=h.size();++n){std::vector<std::string> suffix(h.end()-n,h.end());auto found=counts_.find(key(suffix));if(found==counts_.end())continue;
    const auto& c=found->second;auto f=c.next.find(w);double count=f==c.next.end()?0:f->second;double denominator=c.total+c.next.size();
    probability=count/denominator+c.next.size()/denominator*probability;
  }
  probability=std::max(probability,1e-30);if(probability_cache_.size()>200000)probability_cache_.clear();probability_cache_[cache_key]=probability;return probability;
}
double WordLM::Score(const std::string& text,bool complete)const{
  auto seq=words(text);if(!complete&&!text.empty()&&!std::isspace(static_cast<unsigned char>(text.back()))&&!seq.empty())seq.pop_back();if(complete)seq.push_back("</s>");
  std::vector<std::string> h(3,"<s>");double score=0;
  for(const auto& word:seq){score+=std::log(Prob(word,h));h.erase(h.begin());h.push_back(word);}return score;
}
int WordLM::WordCount(const std::string& text,bool complete)const{auto seq=words(text);if(!complete&&!text.empty()&&!std::isspace(static_cast<unsigned char>(text.back()))&&!seq.empty())seq.pop_back();return seq.size();}
double WordLM::PrefixProb(const std::string& fragment,const std::vector<std::string>& history)const{
  std::string cache_key=key(history)+'\x1e'+fragment;auto cached=prefix_probability_cache_.find(cache_key);if(cached!=prefix_probability_cache_.end())return cached->second;
  auto vocabulary_prefix=prefixes_.find(fragment);double probability=(1.0+(vocabulary_prefix==prefixes_.end()?0:vocabulary_prefix->second.size()))/vocab_.size();
  // Summation distributes over the same Witten-Bell interpolation used in Prob.
  // Aggregate counts avoid enumerating every matching vocabulary word per candidate.
  for(size_t n=0;n<=history.size();++n){std::vector<std::string> suffix(history.end()-n,history.end());auto found=counts_.find(key(suffix));if(found==counts_.end())continue;
    const auto& c=found->second;auto prefix=c.prefix_counts.find(fragment);auto unknown=c.next.find("<unk>");double count=(prefix==c.prefix_counts.end()?0:prefix->second)+(unknown==c.next.end()?0:unknown->second);double denominator=c.total+c.next.size();probability=count/denominator+c.next.size()/denominator*probability;
  }
  probability=std::max(1e-30,std::min(1.0,probability));if(prefix_probability_cache_.size()>100000)prefix_probability_cache_.clear();prefix_probability_cache_[cache_key]=probability;return probability;
}
double WordLM::PartialScore(const std::string& text,double weight,int* completed_words,double* completed_score)const{
  auto seq=words(text);bool partial=!seq.empty()&&!text.empty()&&!std::isspace(static_cast<unsigned char>(text.back()));std::string fragment;
  if(partial){fragment=seq.back();seq.pop_back();}if(completed_words)*completed_words=seq.size();std::vector<std::string> h(3,"<s>");double score=0;
  double before_last=0;for(const auto& w:seq){before_last=score;score+=std::log(Prob(w,h));h.erase(h.begin());h.push_back(w);}
  // A following non-word piece can make the parser mark the last ASCII word
  // incomplete even after a space. Exclude that word from the permanent bound.
  if(completed_score)*completed_score=partial?score:before_last;
  return partial?score+weight*std::log(PrefixProb(fragment,h)):score;
}
BeamResult CtcBeam(const float* logits,int frames,int vocab,sentencepiece::SentencePieceProcessor& tokenizer,const WordLM& lm,const DecoderConfig& config,bool normalized_acoustic){
  if(frames<1||frames>200||vocab<1||!logits)throw std::runtime_error("Invalid CTC input");
  if(std::any_of(logits,logits+frames*vocab,[](float x){return !std::isfinite(x);}))throw std::runtime_error("NONFINITE_CTC_LOGITS: refusing invalid beam scores");
  double alpha=config.alpha;int beam_size=config.beam_size,topk=config.topk;
  using Prefix=std::vector<int>;using Map=std::unordered_map<Prefix,Part,PrefixHash>;
  Map beam;beam.reserve(beam_size);beam[{}].blank=0;std::unordered_map<Prefix,Cached,PrefixHash> cache;cache.reserve(25000);std::unordered_map<std::string,TextScore> text_scores;text_scores.reserve(25000);
  auto decoded=[&](const Prefix& prefix)->Cached&{auto it=cache.find(prefix);if(it!=cache.end())return it->second;Cached value;auto s=tokenizer.Decode(prefix,&value.text);if(!s.ok())throw std::runtime_error(s.ToString());auto score=text_scores.find(value.text);if(score!=text_scores.end())value.score=score->second;else{double language=lm.PartialScore(value.text,config.prefix_weight,&value.score.completed_words,&value.score.completed_score);value.score.partial=alpha*language+config.word_bonus*value.score.completed_words;text_scores.emplace(value.text,value.score);}return cache.emplace(prefix,std::move(value)).first->second;};
  std::vector<int> token_words(vocab);for(int i=0;i<vocab;++i)token_words[i]=words(tokenizer.IdToPiece(i)).size();size_t candidates=0,evaluated=0;bool verify_bound=std::getenv("OWON_VERIFY_BOUND")!=nullptr;
  decoded({});double acoustic_offset=0;
  for(int t=0;t<frames;++t){if(cache.size()>20000){std::unordered_map<Prefix,Cached,PrefixHash> retained;retained.reserve(25000);for(const auto& item:beam){auto old=cache.find(item.first);if(old!=cache.end())retained.emplace(item.first,std::move(old->second));}cache.swap(retained);}if(text_scores.size()>60000)text_scores.clear();const float* frame=logits+t*vocab;std::vector<int> ids(vocab);std::iota(ids.begin(),ids.end(),0);int k=std::min(topk,vocab);
    std::partial_sort(ids.begin(),ids.begin()+k,ids.end(),[&](int a,int b){return frame[a]==frame[b]?a<b:frame[a]>frame[b];});ids.resize(k);if(std::find(ids.begin(),ids.end(),0)==ids.end())ids.push_back(0);
    // Each path has one emission per frame. Log-softmax normalizers are a
    // common additive constant and cannot change beam ordering or its winner.
    double normalizer=frame[ids[0]];if(normalized_acoustic){double sum=0;for(int v=0;v<vocab;++v)sum+=std::exp(frame[v]-normalizer);acoustic_offset+=std::log(sum);}
    double lower_cutoff=NEG;if(beam.size()==static_cast<size_t>(beam_size)){lower_cutoff=std::numeric_limits<double>::infinity();for(const auto& item:beam)lower_cutoff=std::min(lower_cutoff,logadd(item.second.blank,item.second.nonblank)+frame[0]-normalizer+decoded(item.first).score.partial);}
    Map next;next.reserve(beam.size()*(k+1));auto add=[&](const Prefix& prefix,double pb,double pnb,double upper){if(pb==NEG&&pnb==NEG)return;auto& state=next[prefix];state.blank=logadd(state.blank,pb);state.nonblank=logadd(state.nonblank,pnb);state.upper=std::max(state.upper,upper);};
    for(const auto& item:beam){const auto& prefix=item.first;const auto& state=item.second;auto parent=decoded(prefix).score;double total=logadd(state.blank,state.nonblank);add(prefix,total+frame[0]-normalizer,NEG,parent.partial);
      for(int id:ids){if(id==0)continue;double p=frame[id]-normalizer;Prefix extended=prefix;extended.push_back(id);
        // Completed parent words cannot be rewritten by appending a token. All
        // additional log probabilities are <=0. A token introduces no more
        // lexical groups than its piece spelling, plus the parent's last word.
        double upper=alpha*parent.completed_score+config.word_bonus*(parent.completed_words+1+token_words[id]);
        bool repeat=!prefix.empty()&&id==prefix.back();if(repeat)add(prefix,NEG,state.nonblank+p,parent.partial);double acoustic=repeat?state.blank+p:total+p;
        // The blank continuation already supplies beam_size distinct candidates.
        // A NEW token sequence has exactly one parent, so no other CTC path can
        // lift a discarded extension. Existing beam sequences must still merge.
        if(acoustic+upper<lower_cutoff-1e-10&&beam.find(extended)==beam.end()){
          if(!verify_bound)continue;
          double actual=acoustic+decoded(extended).score.partial;if(actual>lower_cutoff+1e-9)throw std::runtime_error("Invalid extension cutoff");
        }
        add(extended,NEG,acoustic,upper);
      }
    }
    std::vector<Map::value_type*> ranked;ranked.reserve(next.size());
    for(auto& item:next){item.second.rank=logadd(item.second.blank,item.second.nonblank)+item.second.upper;ranked.push_back(&item);}candidates+=next.size();
    auto better=[](const auto* a,const auto* b){return a->second.rank==b->second.rank?a->first<b->first:a->second.rank>b->second.rank;};
    std::sort(ranked.begin(),ranked.end(),better);std::vector<Map::value_type*> kept;kept.reserve(beam_size);
    for(auto* item:ranked){double upper=item->second.rank;if(kept.size()==static_cast<size_t>(beam_size)&&upper<kept.front()->second.rank-1e-10)break;
      item->second.rank=logadd(item->second.blank,item->second.nonblank)+decoded(item->first).score.partial;++evaluated;
      if(verify_bound&&item->second.rank>upper+1e-9)throw std::runtime_error("Invalid candidate score upper bound");
      if(kept.size()<static_cast<size_t>(beam_size)){kept.push_back(item);std::push_heap(kept.begin(),kept.end(),better);}else if(better(item,kept.front())){std::pop_heap(kept.begin(),kept.end(),better);kept.back()=item;std::push_heap(kept.begin(),kept.end(),better);}
    }
    if(verify_bound){std::vector<Map::value_type*> full;full.reserve(next.size());for(auto& item:next){double actual=logadd(item.second.blank,item.second.nonblank)+decoded(item.first).score.partial;if(actual>logadd(item.second.blank,item.second.nonblank)+item.second.upper+1e-9){Prefix parent=item.first;if(!parent.empty())parent.pop_back();const auto& p=decoded(parent);throw std::runtime_error("Invalid pruned candidate bound text="+decoded(item.first).text+" parent="+p.text+" actualLanguage="+std::to_string(decoded(item.first).score.partial)+" upper="+std::to_string(item.second.upper)+" parentCompleted="+std::to_string(p.score.completed_score)+" parentWords="+std::to_string(p.score.completed_words)+" piece="+(item.first.empty()?"":tokenizer.IdToPiece(item.first.back()))); }item.second.rank=actual;full.push_back(&item);}std::partial_sort(full.begin(),full.begin()+kept.size(),full.end(),better);std::sort(kept.begin(),kept.end(),better);for(size_t i=0;i<kept.size();++i)if(full[i]->first!=kept[i]->first)throw std::runtime_error("Pruned beam differs from full scoring");}
    beam.clear();for(auto* item:kept)beam.emplace(item->first,item->second);
  }
  BeamResult best;best.combined=NEG;std::vector<ScoredCandidate> scored;
  for(const auto& item:beam){const auto& text=decoded(item.first).text;double acoustic=logadd(item.second.blank,item.second.nonblank),language=lm.Score(text,true),combined=acoustic+alpha*language+config.word_bonus*lm.WordCount(text,true);
    scored.push_back({text,item.first,acoustic-acoustic_offset,language,combined-acoustic_offset});
    if(combined>best.combined||(combined==best.combined&&item.first<best.ids))best={text,item.first,acoustic,language,combined};
  }std::sort(scored.begin(),scored.end(),[](const auto& a,const auto& b){return a.combined==b.combined?a.ids<b.ids:a.combined>b.combined;});if(scored.size()>10)scored.resize(10);best.nbest=std::move(scored);best.acoustic-=acoustic_offset;best.combined-=acoustic_offset;best.candidates=candidates;best.evaluated=evaluated;return best;
}
