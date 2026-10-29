package com.jmu.text2scpi.scpi;
import java.util.Locale;
/**
 * 比较设置目标与设备回读值。返回匹配、不匹配或无法可靠比较；无法比较不能当作已确认成功。
 */
public final class ReadbackVerifier {
    private ReadbackVerifier() {
    }
    /** 比较设置参数与回读值：true 为一致，false 为不一致，null 表示当前响应无法可靠比较。 */
    public static Boolean matches(String command,String response) {
        int space=command.indexOf(' ');
        String target=space<0?"":command.substring(space+1).trim(), actual=response.trim();
        if(target.isEmpty()) return null;
        try {
            double a=Double.parseDouble(target),b=Double.parseDouble(actual);
            if(Double.isFinite(a)&&Double.isFinite(b)) return Math.abs(a-b)<=1e-6*Math.max(Math.abs(a),1e-12);
        }
        catch(NumberFormatException ignored) {
        }
        if(target.matches("[A-Za-z0-9_]+")&&actual.matches("[A-Za-z0-9_]+")) return canonical(target).equals(canonical(actual));
        return null;
    }
    private static String canonical(String s) {
        String upper=s.toUpperCase(Locale.ROOT);
        if(upper.equals("ON")||upper.equals("TRUE")) return "1";
        if(upper.equals("OFF")||upper.equals("FALSE")) return "0";
        return upper;
    }
}
