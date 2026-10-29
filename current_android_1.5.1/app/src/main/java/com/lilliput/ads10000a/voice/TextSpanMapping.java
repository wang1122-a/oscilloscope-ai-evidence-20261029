package com.lilliput.ads10000a.voice;

import java.text.Normalizer;

/** Maps bounded NFKC-normalized matcher offsets back to the current utterance. */
final class TextSpanMapping {
    static final class Span {
        final int start;
        final int end;

        Span(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    final String original;
    final String normalized;
    private final int[] normalizedBoundaryByOriginalOffset;

    private TextSpanMapping(String input) {
        original = input == null ? "" : input;
        normalized = Normalizer.normalize(original, Normalizer.Form.NFKC);
        normalizedBoundaryByOriginalOffset = new int[original.length() + 1];
        for (int i = 0; i <= original.length(); i++) {
            normalizedBoundaryByOriginalOffset[i] =
                    Normalizer.normalize(original.substring(0, i), Normalizer.Form.NFKC).length();
        }
    }

    static TextSpanMapping of(String input) {
        return new TextSpanMapping(input);
    }

    Span originalSpan(int normalizedStart, int normalizedEnd) {
        int start = originalOffsetAtOrAfter(Math.max(0, normalizedStart));
        int end = originalOffsetAtOrAfter(Math.max(normalizedStart, normalizedEnd));
        if (end < start) end = start;
        return new Span(start, Math.min(end, original.length()));
    }

    private int originalOffsetAtOrAfter(int normalizedOffset) {
        for (int i = 0; i < normalizedBoundaryByOriginalOffset.length; i++) {
            if (normalizedBoundaryByOriginalOffset[i] >= normalizedOffset) return i;
        }
        return original.length();
    }
}
