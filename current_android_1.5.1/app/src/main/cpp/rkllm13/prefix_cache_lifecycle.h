#pragma once

// A cancelled or failed generation cannot prove that the fixed prefix was
// fully computed. Discard that experimental cache instead of reusing it.
inline bool resetPrefixAfterGeneration(bool enabled, bool cancelled,
                                       int runRc, bool callbackError,
                                       bool finished, bool completeJson) {
    // A callback stop also covers stale/cancelled Java requests. Only the
    // SDK FINISH event proves that this prefix generation completed normally.
    (void) completeJson;
    return enabled && (cancelled || runRc != 0 || callbackError || !finished);
}
