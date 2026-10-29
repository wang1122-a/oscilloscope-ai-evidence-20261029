package com.jmu.text2scpi.ui;
import org.junit.Test;
import static org.junit.Assert.*;

public final class UiTextTest {
    @Test public void englishModeTranslatesVisibleControlsAndStatuses(){
        assertEquals("Confirm",UiText.display("en","确认执行"));
        assertEquals("Listening paused",UiText.display("en","助手已暂停"));
        assertEquals("English recognition + 4-gram: step1519\nBilingual Qwen commands: ready",UiText.display("en","英文识别＋4-gram：step1519\n双语千问指令：ready"));
        assertEquals("Please specify the channel.",UiText.display("en","request_missing_channel"));
    }
    @Test public void chineseModeKeepsOriginalCopy(){assertEquals("确认执行",UiText.display("zh","确认执行"));assertEquals("助手已暂停",UiText.display("zh","助手已暂停"));}
    @Test public void responseTranslationPreservesDeviceValue(){
        assertEquals("Setting confirmed · 5.0000E-3",UiText.response("en","已确认设置 · 5.0000E-3"));
        assertEquals("5.0000E-3",UiText.response("en","5.0000E-3"));
        assertEquals("设备原始文本",UiText.response("en","设备原始文本"));
    }
    @Test public void nestedErrorsUseEnglishWithoutClaimingExecution(){
        String text=UiText.display("en","结果不确定，不会重发：设置已发送，但独立回读仍未确认，设置不会重发：查询超时");
        assertTrue(text.contains("not confirmed"));assertTrue(text.contains("not resent"));assertFalse(text.matches("(?s).*[\\p{IsHan}].*"));
    }
}
