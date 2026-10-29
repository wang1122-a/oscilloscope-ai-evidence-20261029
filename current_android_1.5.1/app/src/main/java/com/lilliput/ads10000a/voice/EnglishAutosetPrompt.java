package com.lilliput.ads10000a.voice;

/** A bounded vocabulary prompt; model output still passes the unchanged command/source policy. */
final class EnglishAutosetPrompt {
    static final String ASSET="single_instruction_en_autoset_template.txt";
    static final String SHA256="0A5F37BA27CC42C40131ED21A75711265045B74FBC27BA6EB24499D5C44CA26F";
    private static final String OBJECT="(?:autoset|auto[- ]set|auto[- ]setup|automatic\\s+(?:waveform\\s+)?(?:setup|configuration))";
    private static final String POLITE="(?:(?:please\\s+)?(?:would|could|can|will)\\s+you\\s+(?:please\\s+)?|i\\s+(?:would\\s+like|want)(?:\\s+you)?\\s+to\\s+|please\\s+)?";
    private static final java.util.regex.Pattern REQUEST=java.util.regex.Pattern.compile(
            "(?i)^\\s*"+POLITE+"(?:"
            +"(?:autoset|auto[- ]set|auto[- ]setup)(?:\\s+(?:the\\s+)?(?:scope|oscilloscope))?"
            +"|(?:run|perform|execute|invoke|apply)\\s+(?:(?:an?|the)\\s+)?"+OBJECT+"(?:\\s+operation)?(?:\\s+once|\\s+for\\s+the\\s+present\\s+waveform)?"
            +"|(?:undo|revert)\\s+(?:the\\s+)?(?:last\\s+|previous\\s+)?"+OBJECT+"(?:\\s+(?:i|we)\\s+just\\s+(?:requested|performed))?"
            +"|auto[- ]adjust\\s+(?:the\\s+)?(?:scope|oscilloscope)"
            +"|have\\s+autoset\\s+choose\\s+the\\s+appropriate\\s+oscilloscope\\s+configuration"
            +"|have\\s+(?:the\\s+)?(?:scope|oscilloscope)\\s+auto[- ]adjust\\s+its\\s+waveform\\s+settings"
            +"|(?:the\\s+)?(?:required|requested|desired)\\s+(?:setting|action)\\s+for\\s+"+OBJECT+"\\s+is\\s+(?:undo|execute)"
            +")\\s*[.!?]?\\s*$");
    static boolean requested(String original,SemanticLanguage language){return language==SemanticLanguage.EN&&original!=null&&REQUEST.matcher(original).matches();}
    private EnglishAutosetPrompt(){}
}
