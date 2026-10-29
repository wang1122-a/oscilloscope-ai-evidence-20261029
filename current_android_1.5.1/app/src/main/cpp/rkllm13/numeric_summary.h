#pragma once
#include <cmath>
#include <algorithm>
#include <cstdint>
#include <sstream>
#include <iomanip>
#include <limits>
namespace sv {
template<class T> std::string numericSummary(const T* data,size_t n,const char* dtype){
 size_t finite=0,nan=0,pos=0,neg=0,zero=0;long double sum=0,squares=0;double lo=INFINITY,hi=-INFINITY;uint64_t hash=1469598103934665603ULL;
 const auto* bytes=reinterpret_cast<const unsigned char*>(data);for(size_t i=0;i<n*sizeof(T);i++){hash^=bytes[i];hash*=1099511628211ULL;}
 for(size_t i=0;i<n;i++){double v=double(data[i]);if(std::isnan(v)){nan++;continue;}if(std::isinf(v)){if(v>0)pos++;else neg++;continue;}finite++;zero+=v==0;lo=std::min(lo,v);hi=std::max(hi,v);sum+=v;squares+=static_cast<long double>(v)*v;}
 std::ostringstream o;o<<std::setprecision(12)<<"{\"dtype\":\""<<dtype<<"\",\"elements\":"<<n<<",\"bytes\":"<<n*sizeof(T)<<",\"finite\":"<<finite<<",\"nan\":"<<nan<<",\"positive_inf\":"<<pos<<",\"negative_inf\":"<<neg<<",\"zero\":"<<zero<<",\"zero_fraction\":"<<(n?double(zero)/n:0)<<",\"clipping_count\":0,\"clipping_applied\":false,\"fnv1a64\":\""<<std::hex<<hash<<std::dec<<"\",\"min\":";
 if(finite)o<<lo<<",\"max\":"<<hi<<",\"mean\":"<<double(sum/finite)<<",\"rms\":"<<std::sqrt(double(squares/finite));else o<<"null,\"max\":null,\"mean\":null,\"rms\":null";
 o<<",\"edge_values\":[";for(size_t i=0;i<std::min(size_t(8),n);i++){if(i)o<<',';double v=double(data[i]);if(std::isfinite(v))o<<v;else o<<'"'<<(std::isnan(v)?"NaN":v>0?"+Inf":"-Inf")<<'"';}o<<"]}";return o.str();
}
}
