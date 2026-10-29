#pragma once

// Only computation reuse is controlled here. No user text or semantic result
// is stored. A failed SDK generation requires one complete full-prompt request
// before its fixed-system-prefix computation is eligible for reuse again.
class PrefixCacheRecoveryPolicy {
    bool pending_ = false;
public:
    bool useForRequest(bool configured) const { return configured && !pending_; }
    bool recoveryRequested(bool configured) const { return configured && pending_; }
    void afterRequest(bool configured, bool completedSafely) {
        pending_ = configured && !completedSafely;
    }
    void reset() { pending_ = false; }
};
