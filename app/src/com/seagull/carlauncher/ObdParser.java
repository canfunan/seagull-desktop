package com.seagull.carlauncher;

/**
 * P1 OBD 响应解析（纯静态，JVM 自检覆盖）。
 * ELM327 headers-off（ATH0）+ 空格关闭（ATS0）的文本响应；克隆头子可能回显
 * 命令/混入 SEARCHING... 过渡行——逐行扫，只认 "41 <pid>" 数据行。
 * 公式（SAE J1979）：车速=A，转速=(A*256+B)/4，水温=A-40，电压=(A*256+B)/1000，
 * 油量=A*100/255。
 */
public final class ObdParser {
    /** Mode 01 PID。 */
    public static final int PID_COOLANT = 0x05;
    public static final int PID_RPM = 0x0C;
    public static final int PID_SPEED = 0x0D;
    public static final int PID_VOLT = 0x42;
    public static final int PID_FUEL = 0x2F;

    /** 解析失败哨兵（NaN = 该 PID 本轮失效）。 */
    public static final double NA = Double.NaN;

    private ObdParser() { }

    /**
     * 解析 Mode 01 响应；找不到有效数据行返回 NaN。
     * resp 可能多行（多 ECU/过渡文本/回显），逐行扫第一个匹配项。
     */
    public static double parse(int pid, String resp) {
        if (resp == null || resp.isEmpty()) return NA;
        String want = "41" + hex2(pid);
        for (String line0 : resp.split("[\r\n]+")) {
            String line = line0.trim();
            if (line.isEmpty() || isError(line)) continue;
            // 去空格归一（有的头子 ATS0 不生效）
            String flat = line.replace(" ", "").replace(":", "");
            int at = flat.indexOf(want);
            if (at < 0) continue;
            // 数据字节跟在 41+pid 之后（跳过可能的多帧序号行前缀已由去冒号处理）
            String data = flat.substring(at + want.length());
            if (data.length() < 2) continue;
            try {
                int a = Integer.parseInt(data.substring(0, 2), 16);
                switch (pid) {
                    case PID_SPEED: return a;
                    case PID_COOLANT: return a - 40;
                    case PID_VOLT:
                        if (data.length() < 4) continue;
                        int b = Integer.parseInt(data.substring(2, 4), 16);
                        return (a * 256 + b) / 1000.0;
                    case PID_RPM:
                        if (data.length() < 4) continue;
                        int rb = Integer.parseInt(data.substring(2, 4), 16);
                        return (a * 256 + rb) / 4.0;
                    case PID_FUEL: return a * 100.0 / 255.0;
                    default: return NA;
                }
            } catch (NumberFormatException e) {
                continue;
            }
        }
        return NA;
    }

    /** ATRV 响应（"12.3V" 或 "12.3"），解析失败 NaN。 */
    public static double parseVoltageAtrv(String resp) {
        if (resp == null) return NA;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+\\.\\d+)\\s*V?").matcher(resp);
        return m.find() ? Double.parseDouble(m.group(1)) : NA;
    }

    /** ELM327 错误/过渡 token（这些行整行丢弃，当本轮失败处理）。 */
    public static boolean isError(String line) {
        String u = line.trim().toUpperCase(java.util.Locale.US);
        if (u.equals("?") || u.isEmpty()) return true;
        return u.startsWith("NO DATA") || u.startsWith("UNABLE TO CONNECT")
                || u.startsWith("CAN ERROR") || u.startsWith("BUS ERROR")
                || u.startsWith("BUS INIT") || u.startsWith("BUS BUSY")
                || u.startsWith("STOPPED") || u.startsWith("BUFFER FULL")
                || u.startsWith("FB ERROR") || u.startsWith("DATA ERROR")
                || u.startsWith("SEARCHING") || u.startsWith("SEARCH...")
                || u.startsWith("ERR") || u.startsWith("LV RESET")
                || u.startsWith("OK") || u.startsWith("AT");
    }

    public static String hex2(int pid) {
        return String.format(java.util.Locale.US, "%02X", pid);
    }
}
