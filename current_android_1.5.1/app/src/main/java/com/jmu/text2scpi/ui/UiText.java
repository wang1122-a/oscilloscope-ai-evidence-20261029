package com.jmu.text2scpi.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/** Interface copy follows ASR selection. User transcripts and SCPI values are never translated. */
public final class UiText {
    private UiText() {}
    public static boolean english(String language){return "en".equals(language);}
    public static String choose(String language,String zh,String en){return english(language)?en:zh;}
    private static final Map<String,String> TEXT=new LinkedHashMap<>();
    private static final Map<String,String> PREFIX=new LinkedHashMap<>();
    static {
        String[][] labels={
            {"持续监听（播报结束后自动听下一条）","Continuous listening (resume after speech)"},{"跟随示波器语言（默认）","Follow oscilloscope language (default)"},
            {"已唤醒，正在回应","Awake · Responding"},{"已发送","Command sent"},{"已发送。","Command sent."},{"请再说一遍。","Please say that again."},
            {"小欧","XiaoOu"},{"收起","Collapse"},{"关闭","Close"},{"确认执行","Confirm"},{"取消","Cancel"},
            {"说话 / 重说","Speak / Retry"},{"更多","More"},{"全自动下发","Automatic sending"},{"语音反馈","Spoken feedback"},
            {"导入模型资源包","Import model package"},{"重新检查资源","Check resources"},{"提交文字","Submit text"},
            {"打开示波器","Open oscilloscope"},{"暂停监听","Pause listening"},{"英文 4-gram 解码","English 4-gram decoding"},
            {"默认查询自动、设置确认。全自动仍可能执行合法的误识别。","Queries send automatically; settings require confirmation. Automatic mode can execute a misrecognized command."},
            {"播报语言（切换识别时默认跟随，可单独改）","Speech output language (follows recognition when switched; adjustable separately)"},
            {"正在准备离线助手","Preparing offline assistant"},{"正在加载语音反馈","Loading speech output"},{"离线语音反馈已就绪","Offline speech output ready"},
            {"点击说话","Tap to speak"},{"正在听…","Listening…"},{"想调整什么？","What would you like to adjust?"},
            {"助手已暂停","Listening paused"},{"助手已暂停 · 模型已就绪","Listening paused · Model ready"},
            {"说「小欧小欧」或「Hey OWON」","Say 'Xiao Ou Xiao Ou' or 'Hey OWON'"},
            {"完成 · 继续说「小欧小欧」或「Hey OWON」","Done · Say 'Xiao Ou Xiao Ou' or 'Hey OWON'"},
            {"模型已就绪 · 点击说话或语音唤醒","Model ready · Tap to speak or use the wake phrase"},
            {"正在听，请说一条指令","Listening · Say one command"},{"正在识别 · NPU","Recognizing · NPU"},
            {"正在理解 · NPU","Interpreting · NPU"},{"正在执行","Executing"},{"正在播报 · 点击可打断","Speaking · Tap to interrupt"},
            {"正在播报","Speaking"},{"正在切换识别模型…","Switching recognition model…"},{"正在校验并准备资源…","Checking and preparing resources…"},
            {"请确认这条设置","Confirm this setting"},{"请重试","Please try again"},{"已取消，未发送","Cancelled; not sent"},
            {"声音太短，请重说","Speech was too short. Please try again."},{"音频拥堵，请重说","Audio queue overloaded. Please try again."},
            {"没有听到指令，点击重试","No command heard. Tap to retry."},{"没有识别到文字，请重说","No speech recognized. Please try again."},
            {"英文指令超过12秒，请分句重说","English input exceeded 12 seconds. Please use shorter commands."},
            {"语音超过15秒，请分成单条指令","Speech exceeded 15 seconds. Please use shorter commands."},
            {"请在主页允许麦克风权限","Allow microphone access on the main screen."},{"请在主页授予麦克风权限","Allow microphone access on the main screen."},
            {"请在更多中导入当前语言和公共资源","Import the selected language and common resources under More."},
            {"请先导入并准备双语千问指令资源","Import and prepare the bilingual command model first."},
            {"请先准备当前语言和公共资源","Prepare the selected language and common resources first."},
            {"请重启助手恢复模型","Restart the assistant to restore its models."},{"模型切换与恢复失败，请重启助手","Model switching and recovery failed. Restart the assistant."},
            {"原模型释放状态不确定，请重启助手","Model release could not be confirmed. Restart the assistant."},
            {"无法保存语言选择，已恢复原模型","Could not save the language selection; the previous model was restored."},
            {"请等当前任务结束后再检查资源","Wait for the current operation before checking resources."},
            {"请等当前语音、播报或设备操作结束后再切换","Wait for recording, inference, playback and device operations before switching."},
            {"请等当前语音、播报或设备操作结束后再导入","Wait for recording, inference, playback and device operations before importing."},
            {"助手正忙，请稍后重新导入","The assistant is busy. Please import again when idle."},
            {"语音反馈未就绪，文字结果保留","Speech output is not ready. The text result is available."},
            {"唤醒不可用，保留点击入口","Wake detection unavailable. Tap to speak."},
            {"指令未通过校验，未发送","Command validation failed. Nothing was sent."},{"未识别为示波器指令，未发送","No oscilloscope command recognized. Nothing was sent."},
            {"当前操作已取消，不生成命令。","Operation cancelled. No command generated."},
            {"模型需要进一步说明，请补充通道、数值或操作","Please specify the channel, value or operation."},
            {"模型拒绝该请求，未生成可执行指令","The request was rejected. No executable command generated."},{"模型拒绝了该指令，未发送","The request was rejected. Nothing was sent."},
            {"request_missing_channel","Please specify the channel."},
            {"结果不确定，不会重复发送。","The result is uncertain. The setting will not be sent again."},
            {"指令未发送，请查看屏幕。","Command not sent. Please check the screen."},
            {"回读与目标不一致，请查看屏幕。","Readback does not match the target. Please check the screen."},
            {"已发送，尚未确认生效。","Setting sent; effectiveness not confirmed."},
            {"唤醒 / VAD / TTS","Wake / VAD / TTS"},{"中文识别","Chinese recognition"},{"英文识别＋4-gram","English recognition + 4-gram"},
            {"中文 4-gram 纠歧","Chinese 4-gram disambiguation"},{"中文4-gram纠歧（可选）","Chinese 4-gram (optional)"},{"explicit_unit_dimension_conflict","The spoken unit does not match this setting. Say millivolts for voltage or milliseconds for time."},{"英文单位识别不确定，请重说；毫伏请说 millivolts","Uncertain unit recognition. Please repeat; say millivolts for mV."},{"双语千问指令","Bilingual Qwen commands"},{"待导入","Not imported"}
        };
        for(String[] pair:labels)TEXT.put(pair[0],pair[1]);
        String[][] prefixes={
            {"资源导入失败，现用资源保持不变：","Import failed; active resources are unchanged: "},
            {"新模型加载失败，已恢复原模型：","Target model failed to load; previous model restored: "},
            {"语音反馈不可用，文字结果保留：","Speech output unavailable; text result retained: "},
            {"语音反馈失败，文字结果保留：","Speech output failed; text result retained: "},
            {"未发送：请确认示波器测量界面已打开。","Not sent. Open the oscilloscope measurement screen. "},
            {"结果不确定，不会重发：","Readback was not confirmed; the setting was not resent. "},
            {"设置已发送，但独立回读仍未确认，设置不会重发：","Fresh readback could not confirm the setting. "},
            {"资源准备失败：","Resource preparation failed: "},{"切换失败：","Switch failed: "},{"语音加载失败：","Speech model failed to load: "},
            {"语音处理失败：","Speech processing failed: "},{"识别失败：","Recognition failed: "},{"录音失败：","Recording failed: "},{"理解失败：","Interpretation failed: "},
            {"正在导入 ","Importing "},{"正在读取资源包…","Reading model package…"},
            {"已确认设置 · ","Setting confirmed · "},{"未达到目标值，设备回读：","Target not reached; device readback: "},
            {"已发送，未验证生效","Sent; effectiveness not confirmed"}
        };
        for(String[] pair:prefixes)PREFIX.put(pair[0],pair[1]);
    }
    public static String display(String language,String text) {
        if(!english(language)||text==null||text.isEmpty())return text;
        String exact=TEXT.get(text);if(exact!=null)return exact;
        if(text.contains("\n")){String[] lines=text.split("\n",-1);for(int i=0;i<lines.length;i++)lines[i]=display(language,lines[i]);return String.join("\n",lines);}
        for(Map.Entry<String,String> pair:TEXT.entrySet())if(text.startsWith(pair.getKey()+"："))return pair.getValue()+": "+display(language,text.substring(pair.getKey().length()+1));
        for(Map.Entry<String,String> pair:PREFIX.entrySet())if(text.startsWith(pair.getKey()))return pair.getValue()+display(language,text.substring(pair.getKey().length()));
        if(text.contains("ms · 理解 "))return text.replace("ms · 理解 ","ms · Interpretation ");
        if(text.codePoints().anyMatch(c->Character.UnicodeScript.of(c)==Character.UnicodeScript.HAN))return "See diagnostic details.";
        return text;
    }
    /** Translate only application framing; preserve literal device replies. */
    public static String response(String language,String text) {
        if(!english(language)||text==null)return text;
        for(String prefix:new String[]{"已确认设置 · ","未达到目标值，设备回读：","已发送，未验证生效"})
            if(text.startsWith(prefix))return PREFIX.get(prefix)+text.substring(prefix.length());
        return text;
    }
}
