#pragma once
#include <algorithm>
#include <cstdint>
#include <stdexcept>
#include <string>
#include <vector>

// Owned by one fixed SDK/model/tokenizer Engine. Keys are complete immutable
// system-prefix bytes, never user text, state, answers or retained request KV.
class StaticPrefixCatalog {
public:
 struct Entry {std::string prefix,path;long bytes;int kv_tokens;std::uint64_t used;};
 explicit StaticPrefixCatalog(int capacity=2):capacity_(capacity){if(capacity<1||capacity>2)throw std::invalid_argument("prefix_capacity");}
 const Entry* find(const std::string& prefix){for(auto& e:entries_)if(e.prefix==prefix){e.used=++clock_;return &e;}return nullptr;}
 std::string erase(const std::string& prefix){for(auto i=entries_.begin();i!=entries_.end();++i)if(i->prefix==prefix){auto path=i->path;entries_.erase(i);return path;}return {};}
 std::string reserveForMiss(){if(entries_.size()<static_cast<size_t>(capacity_))return {};auto i=std::min_element(entries_.begin(),entries_.end(),[](const Entry&a,const Entry&b){return a.used<b.used;});auto path=i->path;entries_.erase(i);return path;}
 void remember(const std::string& prefix,const std::string& path,long bytes,int kv_tokens){if(prefix.empty()||path.empty()||bytes<=0||entries_.size()>=static_cast<size_t>(capacity_))throw std::logic_error("prefix_catalog_commit");entries_.push_back({prefix,path,bytes,kv_tokens,++clock_});}
 std::vector<std::string> clear(){std::vector<std::string> paths;for(const auto& e:entries_)paths.push_back(e.path);entries_.clear();return paths;}
 void setCapacity(int n){if(n<1||n>2||!entries_.empty())throw std::invalid_argument("prefix_capacity_change_requires_empty_catalog");capacity_=n;}
 size_t size()const{return entries_.size();}
 long bytes()const{long n=0;for(const auto& e:entries_)n+=e.bytes;return n;}
 int capacity()const{return capacity_;}
private:
 int capacity_;std::uint64_t clock_=0;std::vector<Entry> entries_;
};
