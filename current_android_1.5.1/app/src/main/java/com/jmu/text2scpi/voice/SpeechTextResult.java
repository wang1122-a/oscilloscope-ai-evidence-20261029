package com.jmu.text2scpi.voice;
/**
 * 语音文本处理结果。分别保存原始识别文字、修正文字、领域分数及通过状态，避免丢失可核对的原始信息。
 */
public final class SpeechTextResult {
    private final String original,corrected,diagnostic;
    private final boolean passed;
    private final int score;
    public SpeechTextResult(String original,String corrected,boolean passed,int score) {
        this(original,corrected,passed,score,"");
    }
    public SpeechTextResult(String original,String corrected,boolean passed,int score,String diagnostic) {
        this.diagnostic=diagnostic;
        this.original=original;
        this.corrected=corrected;
        this.passed=passed;
        this.score=score;
    }
    public String getOriginal() {
        return original;
    }
    public String getCorrected() {
        return corrected;
    }
    public boolean getPassed() {
        return passed;
    }
    /** VAD can return noise as punctuation; this carries no spoken instruction. */
    public boolean hasSpokenContent(){return corrected!=null&&corrected.codePoints().anyMatch(Character::isLetterOrDigit);}
    public String getDiagnostic(){return diagnostic;}
    public int getScore() {
        return score;
    }
}
