package com.jmu.text2scpi.ui;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import com.jmu.text2scpi.model.*;
import com.jmu.text2scpi.service.OscilloscopeControlService;
import java.util.function.*;
/**
 * 主页面和悬浮面板共用的操作界面。根据状态快照显示识别结果、确认入口及反馈状态；事件回调交由服务处理。
 */
public final class AssistantPanel extends ScrollView {
    private final Supplier<OscilloscopeControlService> service;
    private final LinearLayout column,confirmRow,more;
    private final TextView subtitle,status,transcript,corrected,response,error,confirmation,details;
    private final VoiceOrb orb;
    private final Switch autoSwitch;
    private final EditText input;
    private ControlUiState current=new ControlUiState();
    private boolean updating;
    private String renderedLanguage="";
    private final java.util.Map<TextView,String> labels=new java.util.LinkedHashMap<>();
    private final Switch feedbackSwitch;
    private final Spinner feedbackLanguage;
    private final Spinner asrLanguage;
    private final Switch lmSwitch,zhLmSwitch,continuousSwitch,followScopeSwitch;
    private final TextView resourceStatus;
    public AssistantPanel(Context context,Supplier<OscilloscopeControlService> service,Runnable microphone,Runnable collapse,Runnable close) {
        super(context);
        this.service=service;
        column=new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(22),dp(16),dp(22),dp(16));
        subtitle=text(12,0xffa7b5ca);
        status=text(15,0xffb9cafa);
        transcript=text(22,Color.WHITE);
        corrected=text(13,0xffa9bbd0);
        response=text(19,0xff86e5c0);
        error=text(14,0xffffb2ac);
        confirmation=text(14,0xffffd49a);
        details=text(12,0xffa7b5ca);
        orb=new VoiceOrb(context);
        confirmRow=new LinearLayout(context);
        more=new LinearLayout(context);
        more.setOrientation(LinearLayout.VERTICAL);
        more.setVisibility(GONE);
        autoSwitch=new Switch(context);
        label(autoSwitch,"全自动下发");
        autoSwitch.setTextSize(14);
        autoSwitch.setTextColor(Color.WHITE);
        feedbackSwitch=new Switch(context);
        label(feedbackSwitch,"语音反馈");
        feedbackSwitch.setTextSize(14);
        feedbackSwitch.setTextColor(Color.WHITE);
        feedbackLanguage=new Spinner(context);
        feedbackLanguage.setAdapter(languageAdapter(new String[]{"自动","中文","English"},new String[]{"Auto","Chinese","English"}));
        input=new EditText(context);
        input.setHint("例如：查询CH3的带宽");
        input.setTextSize(15);
        input.setSingleLine();
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xff8190aa);
        setFillViewport(false);
        GradientDrawable background=new GradientDrawable();
        background.setColor(0xff141c2c);
        background.setCornerRadius(dp(24));
        background.setStroke(dp(1),0xff35415a);
        setBackground(background);
        addView(column,new LayoutParams(-1,-2));
        LinearLayout top=new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text(21,Color.WHITE);
        label(title,"小欧");
        title.setTypeface(null,Typeface.BOLD);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        top.addView(button("收起",collapse));
        top.addView(button("关闭",close));
        column.addView(top);
        column.addView(subtitle);
        asrLanguage=new Spinner(context);
        asrLanguage.setAdapter(languageAdapter(new String[]{"中文识别","English 识别"},new String[]{"Chinese recognition","English recognition"}));
        asrLanguage.setContentDescription("识别语言：中文或English");
        asrLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> p,View view,int position,long id){String selected=position==1?"en":"zh";if(!updating&&!selected.equals(current.asrLanguage))with(v->v.switchAsrLanguage(selected));}
            @Override public void onNothingSelected(AdapterView<?> p){}
        });
        column.addView(asrLanguage);
        lmSwitch=new Switch(context);label(lmSwitch,"英文 4-gram 解码");lmSwitch.setTextColor(Color.WHITE);
        lmSwitch.setOnCheckedChangeListener((view,checked)->{if(!updating&&checked!=current.lmEnabled)with(v->v.setLmEnabled(checked));});
        more.addView(lmSwitch);
        continuousSwitch=new Switch(context);label(continuousSwitch,"持续监听（播报结束后自动听下一条）");continuousSwitch.setTextColor(Color.WHITE);
        continuousSwitch.setOnCheckedChangeListener((view,checked)->{if(!updating&&checked!=current.continuousListening)with(v->v.setContinuousListening(checked));});more.addView(continuousSwitch);
        followScopeSwitch=new Switch(context);label(followScopeSwitch,"跟随示波器语言（默认）");followScopeSwitch.setTextColor(Color.WHITE);
        followScopeSwitch.setOnCheckedChangeListener((view,checked)->{if(!updating&&checked!=current.followScopeLanguage)with(v->v.setFollowScopeLanguage(checked));});more.addView(followScopeSwitch);
        zhLmSwitch=new Switch(context);label(zhLmSwitch,"中文 4-gram 纠歧");zhLmSwitch.setTextColor(Color.WHITE);
        zhLmSwitch.setOnCheckedChangeListener((view,checked)->{if(!updating&&checked!=current.zhLmEnabled)with(v->v.setZhLmEnabled(checked));});more.addView(zhLmSwitch);
        resourceStatus=text(12,0xffa7b5ca);resourceStatus.setTextIsSelectable(true);
        more.addView(button("导入模型资源包",()->with(OscilloscopeControlService::requestResourceImport)));
        more.addView(button("重新检查资源",()->with(OscilloscopeControlService::prepareResources)));
        more.addView(resourceStatus);
        column.addView(orb,new LinearLayout.LayoutParams(-1,dp(90)));
        orb.setContentDescription("点击说话");
        orb.setOnClickListener(v->microphone.run());
        status.setGravity(Gravity.CENTER);
        column.addView(status);
        transcript.setText("想调整什么？");
        transcript.setTextIsSelectable(true);
        column.addView(transcript);
        column.addView(corrected);
        column.addView(response);
        column.addView(error);
        column.addView(confirmation);
        confirmRow.addView(button("确认执行",()-> {
            if(current.requestId!=null)with(s->s.confirm(current.requestId));
        }
        ),new LinearLayout.LayoutParams(0,dp(48),1));
        confirmRow.addView(button("取消",()-> {
            if(current.requestId!=null)with(s->s.cancel(current.requestId));
        }
        ),new LinearLayout.LayoutParams(0,dp(48),1));
        column.addView(confirmRow);
        LinearLayout bottom=new LinearLayout(context);
        bottom.addView(button("说话 / 重说",microphone),new LinearLayout.LayoutParams(0,dp(48),1));
        bottom.addView(button("更多",()->more.setVisibility(more.getVisibility()==VISIBLE?GONE:VISIBLE)),new LinearLayout.LayoutParams(0,dp(48),1));
        column.addView(bottom);
        autoSwitch.setOnCheckedChangeListener((view,checked)-> {
            if(!updating)with(s->s.setAutoSend(checked));
        }
        );
        more.addView(autoSwitch);
        TextView warning=text(12,0xffb9a6b5);
        label(warning,"默认查询自动、设置确认。全自动仍可能执行合法的误识别。");
        more.addView(warning);
        more.addView(input);
        more.addView(button("提交文字",()->with(s->s.submitText(input.getText().toString()))));
        feedbackSwitch.setOnCheckedChangeListener((view,checked)-> {
            if(!updating)with(s->s.setFeedbackEnabled(checked));
        }
        );
        more.addView(feedbackSwitch);
        TextView languageLabel=text(13,0xffa7b5ca);
        label(languageLabel,"播报语言（切换识别时默认跟随，可单独改）");
        more.addView(languageLabel);
        more.addView(feedbackLanguage);
        feedbackLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id) {
                String selected=new String[] {
                    "AUTO","CHINESE","ENGLISH"
                }
                [position];if(!updating&&!selected.equals(current.feedbackLanguage))with(s->s.setFeedbackLanguage(selected));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {
            }
        }
        );
        more.addView(button("打开示波器",()->with(OscilloscopeControlService::openOscilloscopeApp)));
        more.addView(button("暂停监听",()->with(OscilloscopeControlService::pauseVoice)));
        details.setTextIsSelectable(true);
        more.addView(details);
        column.addView(more);
    }
    public void setOverlayInputActivation(Runnable activate){input.setOnTouchListener((v,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)activate.run();return false;});}
    public void finishTextInput(){input.clearFocus();android.view.inputmethod.InputMethodManager manager=getContext().getSystemService(android.view.inputmethod.InputMethodManager.class);if(manager!=null)manager.hideSoftInputFromWindow(input.getWindowToken(),0);}
    private void label(TextView view,String zh){labels.put(view,zh);view.setText(zh);}
    private String copy(String zh,String en){return UiText.choose(current.asrLanguage,zh,en);}
    private String translated(String text){return UiText.display(current.asrLanguage,text);}
    private ArrayAdapter<String> languageAdapter(String[] zh,String[] en){
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(getContext(),android.R.layout.simple_spinner_item,zh){
            @Override public View getView(int position,View recycled,ViewGroup parent){View view=super.getView(position,recycled,parent);((TextView)view).setText(UiText.english(current.asrLanguage)?en[position]:zh[position]);((TextView)view).setTextColor(Color.WHITE);return view;}
            @Override public View getDropDownView(int position,View recycled,ViewGroup parent){View view=super.getDropDownView(position,recycled,parent);((TextView)view).setText(UiText.english(current.asrLanguage)?en[position]:zh[position]);return view;}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);return adapter;
    }
    private void with(Consumer<OscilloscopeControlService> action) {
        OscilloscopeControlService s=service.get();
        if(s!=null)action.accept(s);
    }
    private int dp(int n) {
        return (int)(n*getResources().getDisplayMetrics().density);
    }
    private TextView text(float size,int color) {
        TextView v=new TextView(getContext());
        v.setTextSize(size);
        v.setTextColor(color);
        v.setPadding(0,dp(5),0,dp(5));
        return v;
    }
    private Button button(String label,Runnable action) {
        Button b=new Button(getContext());
        label(b,label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setTextColor(0xffd2dff6);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(10),0,dp(10),0);
        b.setOnClickListener(v->action.run());
        return b;
    }
    /** 根据服务快照更新按钮、识别文字和执行结果；渲染本身不发送指令或触发播报。 */
    public void render(ControlUiState s) {
        current=s;
        if(!s.asrLanguage.equals(renderedLanguage)){
            renderedLanguage=s.asrLanguage;
            for(java.util.Map.Entry<TextView,String> entry:labels.entrySet())entry.getKey().setText(translated(entry.getValue()));
            input.setHint(copy("例如：查询CH3的带宽","Example: Query channel 3 bandwidth"));
            asrLanguage.setContentDescription(copy("识别语言：中文或English","Recognition language: Chinese or English"));
            feedbackLanguage.setContentDescription(copy("播报语言","Speech output language"));
            orb.setContentDescription(copy("点击说话","Tap to speak"));
            ((BaseAdapter)asrLanguage.getAdapter()).notifyDataSetChanged();((BaseAdapter)feedbackLanguage.getAdapter()).notifyDataSetChanged();
        }
        subtitle.setText(copy("离线助手","Offline assistant")+"  ·  "+(s.tcpState==TcpState.READY?copy("示波器已连接","Oscilloscope connected"):copy("等待示波器连接","Waiting for oscilloscope"))+"  ·  "+(s.autoSend?copy("全自动","Automatic sending"):copy("设置需确认","Confirm settings")));
        orb.level=s.level;
        orb.listening=s.listening;
        orb.invalidate();
        orb.setEnabled(s.voiceReady&&s.modelState==ModelState.READY&&!s.processing&&!s.resourcesBusy);
        status.setText(translated(s.speaking?"正在播报 · 点击可打断":s.voiceState));
        transcript.setText(s.rawTranscript.trim().isEmpty()?translated(s.listening?"正在听…":"想调整什么？"):("en".equals(s.asrLanguage)?s.correctedTranscript:s.rawTranscript));
        corrected.setVisibility(!"en".equals(s.asrLanguage)&&!s.correctedTranscript.trim().isEmpty()&&!s.correctedTranscript.equals(s.rawTranscript)?VISIBLE:GONE);
        corrected.setText(copy("实际理解为：","Interpreted as: ")+s.correctedTranscript);
        response.setVisibility(s.response!=null?VISIBLE:GONE);
        response.setText(UiText.response(s.asrLanguage,s.response));
        error.setVisibility(s.error!=null?VISIBLE:GONE);
        error.setText(translated(s.error));
        confirmRow.setVisibility(s.awaitingConfirmation?VISIBLE:GONE);
        confirmation.setVisibility(confirmRow.getVisibility());
        confirmation.setText(copy("将执行：","Will execute: ")+(s.scpi==null?"":s.scpi)+copy("\n请核对通道和数值，30秒后自动取消。","\nCheck the channel and value. Cancels automatically after 30 seconds."));
        updating=true;
        boolean idle=!s.resourcesBusy&&!s.processing&&!s.listening&&!s.awaitingConfirmation&&!s.speaking;
        asrLanguage.setEnabled(idle);asrLanguage.setSelection(s.asrLanguage.equals("en")?1:0);
        lmSwitch.setVisibility(s.asrLanguage.equals("en")?VISIBLE:GONE);lmSwitch.setEnabled(idle);lmSwitch.setChecked(s.lmEnabled);
        zhLmSwitch.setVisibility(s.asrLanguage.equals("zh")?VISIBLE:GONE);zhLmSwitch.setEnabled(idle&&s.zhLmReady);zhLmSwitch.setChecked(s.zhLmEnabled&&s.zhLmReady);
        continuousSwitch.setEnabled(!s.resourcesBusy);continuousSwitch.setChecked(s.continuousListening);
        followScopeSwitch.setEnabled(idle);followScopeSwitch.setChecked(s.followScopeLanguage);
        resourceStatus.setText(translated(s.resourceStatus));
        autoSwitch.setChecked(s.autoSend);
        autoSwitch.setEnabled(!s.processing);
        feedbackSwitch.setChecked(s.feedbackEnabled);
        feedbackLanguage.setSelection(s.feedbackLanguage.equals("CHINESE")?1:s.feedbackLanguage.equals("ENGLISH")?2:0);
        updating=false;
        StringBuilder logs=new StringBuilder();
        for(int i=Math.max(0,s.logs.size()-8);i<s.logs.size();i++) {
            if(logs.length()>0)logs.append('\n');
            logs.append(s.logs.get(i).content);
        }
        details.setText(translated(s.latency)+"\n"+translated(s.ttsStatus)+"\nIntent: "+(s.intent==null?"—":s.intent)+"\nSCPI: "+(s.scpi==null?"—":s.scpi)+"\n"+(s.ruleDisposition==null?"":s.ruleDisposition.getWireName())+" · "+s.deliveryState+"\n"+logs);
    }
}
