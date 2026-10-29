#pragma once

#include <sched.h>
#include <cerrno>
#include <sstream>
#include <string>

// RKLLM may change the calling thread's affinity. Keep its worker settings,
// but do not let those settings leak into Java or the next native backend.
class CallingThreadAffinityScope {
public:
    CallingThreadAffinityScope() noexcept {
        CPU_ZERO(&before_);
        capture_rc_ = sched_getaffinity(0, sizeof(before_), &before_);
        capture_errno_ = capture_rc_ == 0 ? 0 : errno;
    }

    ~CallingThreadAffinityScope() noexcept { restore(); }
    CallingThreadAffinityScope(const CallingThreadAffinityScope&) = delete;
    CallingThreadAffinityScope& operator=(const CallingThreadAffinityScope&) = delete;

    void restore() noexcept {
        if (restored_) return;
        restored_ = true;
        CPU_ZERO(&sdk_after_);
        CPU_ZERO(&after_);
        sdk_get_rc_ = sched_getaffinity(0, sizeof(sdk_after_), &sdk_after_);
        if (capture_rc_ != 0) return; // No guessed/default mask on capture failure.
        restore_rc_ = sched_setaffinity(0, sizeof(before_), &before_);
        restore_errno_ = restore_rc_ == 0 ? 0 : errno;
        after_get_rc_ = sched_getaffinity(0, sizeof(after_), &after_);
        matches_ = after_get_rc_ == 0 && CPU_EQUAL(&before_, &after_);
    }

    // Call after the final SDK operation, before returning to the Java caller.
    std::string restoredJsonFields() {
        restore();
        std::ostringstream out;
        out << "\"caller_affinity_capture_rc\":" << capture_rc_
            << ",\"caller_affinity_capture_errno\":" << capture_errno_
            << ",\"caller_affinity_before\":" << list(before_, capture_rc_)
            << ",\"caller_affinity_sdk_after\":" << list(sdk_after_, sdk_get_rc_)
            << ",\"caller_affinity_restore_rc\":" << restore_rc_
            << ",\"caller_affinity_restore_errno\":" << restore_errno_
            << ",\"caller_affinity_after\":" << list(after_, after_get_rc_)
            << ",\"caller_affinity_restored\":" << (matches_ ? "true" : "false");
        return out.str();
    }

private:
    static std::string list(const cpu_set_t& mask, int rc) {
        if (rc != 0) return "null";
        std::ostringstream out;
        out << '[';
        bool first = true;
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (!CPU_ISSET(cpu, &mask)) continue;
            if (!first) out << ',';
            out << cpu;
            first = false;
        }
        out << ']';
        return out.str();
    }
    cpu_set_t before_{}, sdk_after_{}, after_{};
    int capture_rc_ = -1, capture_errno_ = 0, sdk_get_rc_ = -1;
    int restore_rc_ = -1, restore_errno_ = 0, after_get_rc_ = -1;
    bool restored_ = false, matches_ = false;

public:
    // Used only by the pinned SenseVoice repair entry. Never widen an inherited
    // cpuset or change SDK workers; ordinary RKLLM scopes retain their behavior.
    int preferSenseVoiceCallerPair() noexcept {
        if (capture_rc_ != 0 || restored_) return -1;
        if (!CPU_ISSET(6, &before_) || !CPU_ISSET(7, &before_)) return 0;
        cpu_set_t selected{}, observed{};
        CPU_ZERO(&selected); CPU_SET(6, &selected); CPU_SET(7, &selected);
        if (sched_setaffinity(0, sizeof(selected), &selected)) return -2;
        if (sched_getaffinity(0, sizeof(observed), &observed)
                || !CPU_EQUAL(&selected, &observed)) return -3;
        return 1;
    }
    bool restoreConfirmed() noexcept {
        restore();
        return capture_rc_ == 0 && restore_rc_ == 0 && matches_;
    }
};
