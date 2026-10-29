package com.lilliput.ads10000a.voice;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lossless, typed normalization of the current ASR utterance.
 *
 * <p>This stage deliberately runs before the legacy PC text corrector. It never
 * invents a value: every normalized value retains the source span and conversion
 * rule which produced it.</p>
 */
public final class ChineseNumericNormalizer {
    public enum TokenType {
        CHANNEL, NUMBER, UNIT, ACTION, QUERY, NEGATION, OBJECT, PUNCTUATION, TAIL
    }

    public static final class Token {
        public final TokenType type;
        public final String evidenceText;
        public final String normalizedText;
        public final int rawStart;
        public final int rawEnd;
        public final int nfkcStart;
        public final int nfkcEnd;
        public final int normalizedStart;
        public final int normalizedEnd;
        public final Integer channelIndex;
        public final BigDecimal numericValue;
        public final String canonicalUnit;
        public final BigDecimal multiplier;
        public final String conversionRule;
        public final boolean valid;

        private Token(Lexeme source, int normalizedStart, int normalizedEnd) {
            type = source.type;
            evidenceText = source.evidenceText;
            normalizedText = source.replacement;
            rawStart = source.rawStart;
            rawEnd = source.rawEnd;
            nfkcStart = source.start;
            nfkcEnd = source.end;
            this.normalizedStart = normalizedStart;
            this.normalizedEnd = normalizedEnd;
            channelIndex = source.channelIndex;
            numericValue = source.numericValue;
            canonicalUnit = source.canonicalUnit;
            multiplier = source.multiplier;
            conversionRule = source.conversionRule;
            valid = source.valid;
        }

        @Override public String toString() {
            return type + "(" + evidenceText + "->" + normalizedText + ",raw="
                    + rawStart + ".." + rawEnd + ",normalized=" + normalizedStart + ".."
                    + normalizedEnd + ",channel=" + channelIndex + ",number=" + numericValue
                    + ",unit=" + canonicalUnit + ")";
        }
    }

    public static final class Result {
        public final String raw;
        public final String nfkc;
        public final String normalized;
        public final List<Token> tokens;

        private Result(String raw, String nfkc, String normalized, List<Token> tokens) {
            this.raw = raw;
            this.nfkc = nfkc;
            this.normalized = normalized;
            this.tokens = Collections.unmodifiableList(new ArrayList<>(tokens));
        }
    }

    private static final String CN_NUMBER = "负零〇一二两三四五六七八九十百千万点";
    private static final Pattern CHANNEL_PREFIX = Pattern.compile(
            "(?iu)(?:通道|频道)\\s*([0-9]+|[零〇一二两三四五六七八九十百千万]+)");
    private static final Pattern CHANNEL_SUFFIX = Pattern.compile(
            "(?iu)([0-9]+|[零〇一二两三四五六七八九十百千万]+)\\s*(?:模拟\\s*)?(?:通道|频道)");
    private static final Pattern CHANNEL_ORDINAL = Pattern.compile("第([0-9]+|[零〇一二两三四五六七八九十百千万]+)路");
    private static final Pattern CHANNEL_LATIN = Pattern.compile("(?iu)(?<![A-Za-z0-9])CH\\s*([0-9]+)");
    private static final Pattern NUMBER = Pattern.compile(
            "(?:负|[+-])?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?|(?:正|负|[+-])?半(?=毫伏|微伏|伏|格)|[" + CN_NUMBER + "]+");
    private static final Pattern UNIT = Pattern.compile(
            "(?iu)毫伏每格|微伏每格|伏每格|纳秒每格|微秒每格|毫秒每格|秒每格|"
                    + "兆赫兹|千赫兹|兆赫|千赫|赫兹|MHz|kHz|Hz|毫伏|微伏|伏|mV|uV|μV|V|"
                    + "纳秒|微秒|毫秒|秒|ns|us|μs|ms|s|兆|千|倍|次数|次|格|div|"
                    + "度|deg|分贝|dB|点|points?|百分比|%");
    private static final Pattern QUERY = Pattern.compile("查询|读取|读出|查看|检查|核对|确认|报告|获取|多少|哪些|什么|吗|么|呢");
    private static final Pattern NEGATION = Pattern.compile("不要|禁止|别|不得|不允许|无需|无须");
    private static final Pattern ACTION = Pattern.compile(
            "打开|开启|启用|关闭|关掉|关上|隐藏|显示|开始|启动|停止|清除|清空|"
                    + "设置|设定|设为|设成|调到|调整|改为|改成|切换|选择");
    private static final Pattern OBJECT = Pattern.compile(
            "(?:AFG|信号源|源)\\s*[1234一二三四]|采集平均次数|平均次数|垂直档位|垂直位置|水平时基|时基|探头倍率|探头比|"
                    + "触发状态|触发模式|触发源|触发电平|触发边沿|耦合方式|耦合|"
                    + "带宽限制|带宽|频率|幅值|相位|波特图点数|测试点数|错误队列|"
                    + "采集|通道|信号源|AFG|FRA");
    private static final Pattern PUNCTUATION = Pattern.compile("[，。！？、,.!?]");
    private static final Pattern TAIL = Pattern.compile("(?:一下|吧|呀|啦|可以吗|好不好)$");

    public Result normalize(String input) {
        String raw = input == null ? "" : input;
        TextSpanMapping mapping = TextSpanMapping.of(raw);
        String nfkc = mapping.normalized;
        boolean[] claimed = new boolean[nfkc.length()];
        List<Lexeme> lexemes = new ArrayList<>();

        addChannels(CHANNEL_ORDINAL, nfkc, mapping, claimed, lexemes);
        addChannels(CHANNEL_SUFFIX, nfkc, mapping, claimed, lexemes);
        addChannels(CHANNEL_LATIN, nfkc, mapping, claimed, lexemes);
        addChannels(CHANNEL_PREFIX, nfkc, mapping, claimed, lexemes);
        addSimple(NEGATION, TokenType.NEGATION, nfkc, mapping, claimed, lexemes);
        addSimple(QUERY, TokenType.QUERY, nfkc, mapping, claimed, lexemes);
        addSimple(ACTION, TokenType.ACTION, nfkc, mapping, claimed, lexemes);
        addSimple(OBJECT, TokenType.OBJECT, nfkc, mapping, claimed, lexemes);
        addNumbers(nfkc, mapping, claimed, lexemes);
        addUnits(nfkc, mapping, claimed, lexemes);
        addSimple(PUNCTUATION, TokenType.PUNCTUATION, nfkc, mapping, claimed, lexemes);
        addSimple(TAIL, TokenType.TAIL, nfkc, mapping, claimed, lexemes);

        lexemes.sort(Comparator.comparingInt(a -> a.start));
        StringBuilder normalized = new StringBuilder();
        List<Token> tokens = new ArrayList<>();
        int cursor = 0;
        Lexeme previous = null;
        for (Lexeme lexeme : lexemes) {
            if (lexeme.start < cursor) continue;
            normalized.append(nfkc, cursor, lexeme.start);
            if (previous != null && previous.type == TokenType.CHANNEL
                    && lexeme.type == TokenType.NUMBER && previous.end == lexeme.start
                    && normalized.length() > 0
                    && !Character.isWhitespace(normalized.charAt(normalized.length() - 1))) {
                normalized.append(' ');
            }
            int normalizedStart = normalized.length();
            normalized.append(lexeme.replacement);
            tokens.add(new Token(lexeme, normalizedStart, normalized.length()));
            cursor = lexeme.end;
            previous = lexeme;
        }
        normalized.append(nfkc.substring(cursor));
        return new Result(raw, nfkc, normalized.toString(), tokens);
    }

    private static void addChannels(Pattern pattern, String text, TextSpanMapping mapping,
                                    boolean[] claimed, List<Lexeme> out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int end = matcher.end();
            String sourceNumber = matcher.group(1);
            if (pattern == CHANNEL_PREFIX && splitSpokenScaleNumber(text, matcher.end(), sourceNumber)) {
                end = matcher.start(1) + 1;
                sourceNumber = sourceNumber.substring(0, 1);
            }
            if (overlaps(claimed, matcher.start(), end)) continue;
            Double parsed = ParameterSourceGuard.number(sourceNumber);
            if (parsed == null || !Double.isFinite(parsed) || parsed != Math.rint(parsed)) continue;
            int channel = parsed.intValue();
            TextSpanMapping.Span rawSpan = mapping.originalSpan(matcher.start(), end);
            Lexeme lexeme = new Lexeme(TokenType.CHANNEL, matcher.start(), end,
                    rawSpan.start, rawSpan.end, text.substring(matcher.start(), end), sourceNumber);
            lexeme.channelIndex = channel;
            lexeme.valid = !(pattern == CHANNEL_ORDINAL && text.substring(0,matcher.start()).matches("(?s).*(?:数字|数学|参考)\\s*$")) && channel >= 1 && channel <= 4
                    && !sourceNumber.matches("[零〇一二两三四五六七八九]{2,}");
            lexeme.conversionRule = end < matcher.end() ? "SPOKEN_SCALE_CHANNEL_BOUNDARY"
                    : matcher.group().toUpperCase(Locale.ROOT).startsWith("CH")
                    ? "LATIN_CHANNEL_INDEX" : "CHINESE_CHANNEL_INDEX";
            claim(claimed, matcher.start(), end);
            out.add(lexeme);
        }
    }

    private static boolean splitSpokenScaleNumber(String text, int end, String number) {
        if (number.length() < 2 || "零〇一二两三四五六七八九".indexOf(number.charAt(0)) < 0)
            return false;
        String value = number.substring(1);
        if ("十百千万".indexOf(value.charAt(0)) >= 0
                || value.matches("[零〇一二两三四五六七八九]{2,}")) return false;
        Matcher unit = UNIT.matcher(text);
        unit.region(end, text.length());
        if (!unit.lookingAt()) return false;
        UnitInfo info = unit(unit.group());
        if (info == null || !"V/div".equals(info.canonical)) return false;
        try {
            return UnitSourceModel.decimal(value).signum() >= 0;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    private static void addUnits(String text, TextSpanMapping mapping, boolean[] claimed,
                                 List<Lexeme> out) {
        Matcher matcher = UNIT.matcher(text);
        while (matcher.find()) {
            if (matcher.start() == matcher.end() || overlaps(claimed, matcher.start(), matcher.end())) continue;
            UnitInfo info = unit(matcher.group());
            if (info == null) continue;
            TextSpanMapping.Span rawSpan = mapping.originalSpan(matcher.start(), matcher.end());
            Lexeme lexeme = new Lexeme(TokenType.UNIT, matcher.start(), matcher.end(),
                    rawSpan.start, rawSpan.end, matcher.group(), matcher.group());
            lexeme.canonicalUnit = info.canonical;
            lexeme.multiplier = info.multiplier;
            lexeme.conversionRule = info.rule;
            claim(claimed, matcher.start(), matcher.end());
            out.add(lexeme);
        }
    }

    private static void addNumbers(String text, TextSpanMapping mapping, boolean[] claimed,
                                   List<Lexeme> out) {
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            int start = matcher.start(), end = matcher.end();
            while (start < end && claimed[start]) start++;
            if (start == end || overlaps(claimed, start, end)) continue;
            String source = text.substring(start, end);
            boolean chinese = source.matches("[" + CN_NUMBER + "]+");
            if (chinese && start > 0 && Character.isDigit(text.charAt(start - 1))
                    && UNIT.matcher(text).region(start, text.length()).lookingAt()) continue;
            if (chinese && !isBoundedChineseNumber(text, matcher.end())) continue;
            BigDecimal parsed;
            try {
                parsed = UnitSourceModel.decimal(source).stripTrailingZeros();
            } catch (RuntimeException invalid) {
                continue;
            }
            if (parsed.scale() < 0) parsed = parsed.setScale(0);
            TextSpanMapping.Span rawSpan = mapping.originalSpan(start, end);
            String replacement = parsed.toPlainString();
            Lexeme lexeme = new Lexeme(TokenType.NUMBER, start, end,
                    rawSpan.start, rawSpan.end, replacement,
                    source);
            lexeme.numericValue = parsed;
            lexeme.conversionRule = chinese ? "CHINESE_NUMBER_TO_DECIMAL" : "NFKC_DECIMAL";
            claim(claimed, start, end);
            out.add(lexeme);
        }
    }

    private static boolean isBoundedChineseNumber(String text, int end) {
        if (end == text.length()||text.substring(end).matches("[。.!！?？\\s]*")) return true;
        char next = text.charAt(end);
        if (Character.isDigit(next)) return true;
        return UNIT.matcher(text).region(end, text.length()).lookingAt();
    }

    private static void addSimple(Pattern pattern, TokenType type, String text,
                                  TextSpanMapping mapping, boolean[] claimed, List<Lexeme> out) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (overlaps(claimed, matcher.start(), matcher.end())) continue;
            TextSpanMapping.Span rawSpan = mapping.originalSpan(matcher.start(), matcher.end());
            Lexeme lexeme = new Lexeme(type, matcher.start(), matcher.end(), rawSpan.start,
                    rawSpan.end, matcher.group(), matcher.group());
            lexeme.conversionRule = "LEXICAL_" + type.name();
            claim(claimed, matcher.start(), matcher.end());
            out.add(lexeme);
        }
    }

    private static boolean overlaps(boolean[] claimed, int start, int end) {
        for (int i = start; i < end; i++) if (claimed[i]) return true;
        return false;
    }

    private static void claim(boolean[] claimed, int start, int end) {
        for (int i = start; i < end; i++) claimed[i] = true;
    }

    private static UnitInfo unit(String raw) {
        String value = raw.toLowerCase(Locale.ROOT);
        if (value.equals("毫伏每格")) return new UnitInfo("V/div", "0.001", "MILLIVOLT_PER_DIV");
        if (value.equals("微伏每格")) return new UnitInfo("V/div", "0.000001", "MICROVOLT_PER_DIV");
        if (value.equals("伏每格")) return new UnitInfo("V/div", "1", "VOLT_PER_DIV");
        if (value.equals("纳秒每格")) return new UnitInfo("s/div", "0.000000001", "NANOSECOND_PER_DIV");
        if (value.equals("微秒每格")) return new UnitInfo("s/div", "0.000001", "MICROSECOND_PER_DIV");
        if (value.equals("毫秒每格")) return new UnitInfo("s/div", "0.001", "MILLISECOND_PER_DIV");
        if (value.equals("秒每格")) return new UnitInfo("s/div", "1", "SECOND_PER_DIV");
        if (value.equals("兆赫兹") || value.equals("兆赫") || value.equals("mhz")) return new UnitInfo("Hz", "1000000", "MEGAHERTZ");
        if (value.equals("千赫兹") || value.equals("千赫") || value.equals("khz")) return new UnitInfo("Hz", "1000", "KILOHERTZ");
        if (value.equals("赫兹") || value.equals("hz")) return new UnitInfo("Hz", "1", "HERTZ");
        if (value.equals("兆")) return new UnitInfo("Hz", "1000000", "MEGA_FREQUENCY_SHORTHAND");
        if (value.equals("千")) return new UnitInfo("Hz", "1000", "KILO_FREQUENCY_SHORTHAND");
        if (value.equals("毫伏") || value.equals("mv")) return new UnitInfo("V", "0.001", "MILLIVOLT");
        if (value.equals("微伏") || value.equals("uv") || value.equals("μv")) return new UnitInfo("V", "0.000001", "MICROVOLT");
        if (value.equals("伏") || value.equals("v")) return new UnitInfo("V", "1", "VOLT");
        if (value.equals("纳秒") || value.equals("ns")) return new UnitInfo("s", "0.000000001", "NANOSECOND");
        if (value.equals("微秒") || value.equals("us") || value.equals("μs")) return new UnitInfo("s", "0.000001", "MICROSECOND");
        if (value.equals("毫秒") || value.equals("ms")) return new UnitInfo("s", "0.001", "MILLISECOND");
        if (value.equals("秒") || value.equals("s")) return new UnitInfo("s", "1", "SECOND");
        if (value.equals("倍")) return new UnitInfo("ratio", "1", "RATIO");
        if (value.equals("次数") || value.equals("次")) return new UnitInfo("count", "1", "COUNT");
        if (value.equals("格") || value.equals("div")) return new UnitInfo("div", "1", "DIVISION");
        if (value.equals("度") || value.equals("deg")) return new UnitInfo("deg", "1", "DEGREE");
        if (value.equals("分贝") || value.equals("db")) return new UnitInfo("dB", "1", "DECIBEL");
        if (value.equals("点") || value.startsWith("point")) return new UnitInfo("points", "1", "POINTS");
        if (value.equals("百分比") || value.equals("%")) return new UnitInfo("%", "1", "PERCENT");
        return null;
    }

    private static final class UnitInfo {
        final String canonical;
        final BigDecimal multiplier;
        final String rule;

        UnitInfo(String canonical, String multiplier, String rule) {
            this.canonical = canonical;
            this.multiplier = new BigDecimal(multiplier);
            this.rule = rule;
        }
    }

    private static final class Lexeme {
        final TokenType type;
        final int start;
        final int end;
        final int rawStart;
        final int rawEnd;
        final String replacement;
        final String evidenceText;
        Integer channelIndex;
        BigDecimal numericValue;
        String canonicalUnit;
        BigDecimal multiplier;
        String conversionRule;
        boolean valid = true;

        Lexeme(TokenType type, int start, int end, int rawStart, int rawEnd,
               String replacement, String evidenceText) {
            this.type = type;
            this.start = start;
            this.end = end;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
            this.replacement = replacement;
            this.evidenceText = evidenceText;
        }
    }
}
