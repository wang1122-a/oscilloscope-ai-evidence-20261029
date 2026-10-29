package com.lilliput.ads10000a.voice;

/** Language is explicit for text; an ASR result without language is not English-authorized. */
enum SemanticLanguage {
    ZH("zh"), EN("en");
    final String id;
    SemanticLanguage(String id) { this.id = id; }
}
