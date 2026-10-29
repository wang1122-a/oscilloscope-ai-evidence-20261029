package com.lilliput.ads10000a.voice;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict response normalization for table-backed channel state queries. */
final class ReadonlyStateResponse {
    private static final Pattern CHANNEL_QUERY =
            Pattern.compile("^SELect:CH([1-4])\\?$", Pattern.CASE_INSENSITIVE);

    private ReadonlyStateResponse() {}

    static boolean isChannelQuery(String command) {
        return command != null && CHANNEL_QUERY.matcher(command.trim()).matches();
    }

    static String normalize(String command, String response) throws IOException {
        if (response == null || response.trim().isEmpty()
                || !response.matches("[\\x20-\\x7E\\t\\r\\n]+")) {
            throw new IOException("INVALID_STATE_RESPONSE");
        }
        String value = response.trim();
        if (isChannelQuery(command) && !"0".equals(value) && !"1".equals(value)) {
            throw new IOException("INVALID_STATE_RESPONSE");
        }
        return value;
    }

    static String explain(String command, String response) throws IOException {
        String value = normalize(command, response);
        Matcher matcher = CHANNEL_QUERY.matcher(command.trim());
        if (!matcher.matches()) return "当前值：" + value;
        String channel = matcher.group(1);
        String label;
        switch (channel) {
            case "1": label = "一"; break;
            case "2": label = "二"; break;
            case "3": label = "三"; break;
            case "4": label = "四"; break;
            default: throw new IOException("INVALID_CHANNEL_QUERY");
        }
        return "通道" + label + "当前为" + ("1".equals(value) ? "开启" : "关闭") + "状态。";
    }

    static String normalizeStateForRevision(String command, String response) throws IOException {
        return normalize(command, response).toUpperCase(Locale.ROOT);
    }
}
