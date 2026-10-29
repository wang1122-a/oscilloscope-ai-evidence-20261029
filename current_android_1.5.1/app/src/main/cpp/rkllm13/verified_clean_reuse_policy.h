#pragma once
#include <atomic>
#include <cstdint>
// A fresh zero KV count alone is not proof: this instance must have observed its own full clear.
class VerifiedCleanReusePolicy {
 std::atomic<bool> on{false};
 std::atomic<uint64_t> mutation{1},clean{0};
public:
 bool enabled() const {return on.load();}
 uint64_t invalidate(){return mutation.fetch_add(1)+1;}
 void setEnabled(bool value){invalidate();on.store(value);}
 bool canSkip(int getRc,int tokens,bool idle,bool cancelled) const {
  return enabled()&&getRc==0&&tokens==0&&idle&&!cancelled&&clean.load()==mutation.load();
 }
 void observeClear(uint64_t epoch,int clearRc,int getRc,int tokens,bool idle,bool cancelled){
  if(enabled()&&clearRc==0&&getRc==0&&tokens==0&&idle&&!cancelled&&mutation.load()==epoch)clean.store(epoch);
 }
};
