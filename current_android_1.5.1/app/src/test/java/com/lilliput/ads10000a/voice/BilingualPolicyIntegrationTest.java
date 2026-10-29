package com.lilliput.ads10000a.voice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Protocol/parameter regressions. These fixtures are not an accuracy benchmark. */
public final class BilingualPolicyIntegrationTest {
    private String asset(String name)throws Exception {
        try(InputStream in=getClass().getResourceAsStream("/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            assertNotNull(name,in);byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private BilingualTextPipeline pipeline()throws Exception {
        String data=asset("scpi_contract.json");
        return BilingualTextPipeline.singleInstruction(new SemanticPolicy(data),data,asset("pc_asr_text_rules.json"),asset("single_instruction_runtime_config.json"),asset("single_instruction_zh_template.txt"),asset("single_instruction_en_template.txt"));
    }
    private BilingualTextPipeline.Result decide(String text,SemanticLanguage language,String raw)throws Exception {
        BilingualTextPipeline p=pipeline();return p.complete(p.prepare(text,language,Collections.emptyMap(),false,true),raw);
    }
    private static final String ON="{\"intent\":\"VERTICAL_set\",\"SCPIcommand\":\"SELect:CH1\",\"SCPIparams\":\"ON\"}";
    private static final String SCALE="{\"intent\":\"VERTICAL_set\",\"SCPIcommand\":\"CH1:SCALe\",\"SCPIparams\":0.5}";
    @Test public void equivalentLanguageInputsKeepSameChannelAndValue()throws Exception {
        BilingualTextPipeline.Result zh=decide("打开通道一",SemanticLanguage.ZH,ON);
        BilingualTextPipeline.Result en=decide("Turn on channel one",SemanticLanguage.EN,ON);
        assertEquals(zh.reason,"preview",zh.status);assertEquals(en.reason,"preview",en.status);
        assertEquals(zh.canonical.toUpperCase(Locale.ROOT),en.canonical.toUpperCase(Locale.ROOT));
        zh=decide("设置通道一的垂直档位为500毫伏每格",SemanticLanguage.ZH,SCALE);
        en=decide("Set channel one vertical scale to 500 millivolts per division",SemanticLanguage.EN,SCALE);
        assertEquals(zh.reason,"preview",zh.status);assertEquals(en.reason,"preview",en.status);
        assertEquals(zh.canonical.toUpperCase(Locale.ROOT),en.canonical.toUpperCase(Locale.ROOT));
    }
    @Test public void negationAndMissingSourceNeverAcquireExecution()throws Exception {
        assertNotEquals("preview",decide("不要打开通道一",SemanticLanguage.ZH,ON).status);
        assertNotEquals("preview",decide("Do not turn on channel one",SemanticLanguage.EN,ON).status);
        assertNotEquals("preview",decide("Set the vertical scale to 500 millivolts per division",SemanticLanguage.EN,SCALE).status);
    }
    @Test public void numericAndChannelMismatchBlocked()throws Exception {
        assertNotEquals("preview",decide("Set channel two vertical scale to 500 millivolts per division",SemanticLanguage.EN,SCALE).status);
        assertNotEquals("preview",decide("Set channel one vertical scale to 5 volts per division",SemanticLanguage.EN,SCALE).status);
    }
    @Test public void terminalAndMalformedModelOutputNeverEmitWire()throws Exception {
        for(String raw:new String[]{"{\"intent\":\"CLARIFY\",\"SCPIcommand\":\"\",\"SCPIparams\":{}}","{\"intent\":\"REJECT\",\"SCPIcommand\":\"\",\"SCPIparams\":{}}","```json\n"+ON+"\n```",ON+ON}) {
            BilingualTextPipeline.Result r=decide("Turn on channel one",SemanticLanguage.EN,raw);
            assertNotEquals(raw,"preview",r.status);assertTrue(r.canonical==null||r.canonical.isEmpty());
        }
    }
    @Test public void languageTemplatesPreserveTheOriginalUserText()throws Exception {
        BilingualTextPipeline p=pipeline();String original="打开 CH1，设置为 500 mV";
        BilingualTextPipeline.Prepared zh=p.prepare(original,SemanticLanguage.ZH,Collections.emptyMap(),false,true);
        assertTrue(zh.singlePrompt().contains(original));assertTrue(zh.singlePrompt().contains("将示波器指令转成JSON"));
        assertTrue(p.prepare("Turn on channel one",SemanticLanguage.EN,Collections.emptyMap(),false,true).singlePrompt().contains("Convert one oscilloscope instruction"));
    }

    @Test public void multiOperationAndAmbiguousChannelCannotSendASingleGuessedCommand()throws Exception {
        assertNotEquals("preview",decide("Turn on channel one and set channel two vertical scale to one volt per division",SemanticLanguage.EN,ON).status);
        assertNotEquals("preview",decide("打开通道一，然后设置通道二的垂直档位为一伏每格",SemanticLanguage.ZH,ON).status);
        assertNotEquals("preview",decide("Set channel one or channel two vertical scale to 500 millivolts per division",SemanticLanguage.EN,SCALE).status);
    }
}
