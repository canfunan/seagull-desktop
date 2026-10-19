package com.seagull.carlauncher;

/**
 * 自检：ObdParser SAE J1979 公式 + 容错（P1，纯 JVM 无 Android 依赖）。
 * 覆盖：五 PID 正常值/无空格/多 ECU/回显/SEARCHING 过渡/错误 token/空串/ATRV。
 */
public class ObdCheck {

    private static int n = 0;

    public static void main(String[] args) {
        eq(ObdParser.parse(ObdParser.PID_SPEED, "41 0D 64"), 100.0, "车速带空格");
        eq(ObdParser.parse(ObdParser.PID_SPEED, "410D64"), 100.0, "车速无空格");
        eq(ObdParser.parse(ObdParser.PID_RPM, "41 0C 1A F8"), 1726.0, "转速公式");
        eq(ObdParser.parse(ObdParser.PID_COOLANT, "41 05 5A"), 50.0, "水温 A-40");
        eq(ObdParser.parse(ObdParser.PID_VOLT, "41 42 34 A8"), 13.48, "电压 (A*256+B)/1000");
        near(ObdParser.parse(ObdParser.PID_FUEL, "41 2F 80"), 50.196, "油量 A*100/255");

        eq(ObdParser.parse(ObdParser.PID_SPEED,
                "SEARCHING...\r\n7E8\r\n41 0D 32"), 50.0, "过渡行+多 ECU 行跳过");
        eq(ObdParser.parse(ObdParser.PID_SPEED, "010D\r\n41 0D 0A"), 10.0, "回显命令行跳过");
        eq(ObdParser.parse(ObdParser.PID_RPM, "41 0C 00 00"), 0.0, "怠速 0 转速");
        isNan(ObdParser.parse(ObdParser.PID_SPEED, "41 0D"), "缺数据字节 NaN");

        isNan(ObdParser.parse(ObdParser.PID_SPEED, "NO DATA"), "NO DATA");
        isNan(ObdParser.parse(ObdParser.PID_SPEED, "UNABLE TO CONNECT"), "UNABLE");
        isNan(ObdParser.parse(ObdParser.PID_SPEED, "?"), "问号");
        isNan(ObdParser.parse(ObdParser.PID_SPEED, ""), "空串");
        isNan(ObdParser.parse(ObdParser.PID_SPEED, null), "null");
        isNan(ObdParser.parse(ObdParser.PID_SPEED, "410DZZ"), "非法十六进制");

        eq(ObdParser.parseVoltageAtrv("12.3V"), 12.3, "ATRV 带 V");
        eq(ObdParser.parseVoltageAtrv("12.3"), 12.3, "ATRV 裸数");
        isNan(ObdParser.parseVoltageAtrv("garbage"), "ATRV 垃圾");

        boolean[] err = {true, true, true, true};
        err[0] = ObdParser.isError("NO DATA");
        err[1] = ObdParser.isError("SEARCHING...");
        err[2] = ObdParser.isError("BUS INIT: ...OK");
        err[3] = ObdParser.isError("BUFFER FULL");
        check(err[0] && err[1] && err[2] && err[3], "isError 全命中");
        check(!ObdParser.isError("41 0D 64"), "数据行不算错");
        check(ObdParser.isError("STOPPED") && ObdParser.isError("ERR12"),
                "STOPPED/ERR 命中");

        check(ObdParser.hex2(0x0D).equals("0D"), "hex2 车速");
        check(ObdParser.hex2(0x2F).equals("2F"), "hex2 油量");

        System.out.println("ObdCheck: " + n + " 项全绿");
    }

    private static void check(boolean ok, String what) {
        n++;
        if (!ok) throw new AssertionError("第 " + n + " 项失败: " + what);
    }

    private static void eq(double got, double want, String what) {
        check(Math.abs(got - want) < 1e-6, what + " got=" + got + " want=" + want);
    }

    private static void near(double got, double want, String what) {
        check(Math.abs(got - want) < 0.01, what + " got=" + got + " want=" + want);
    }

    private static void isNan(double got, String what) {
        check(Double.isNaN(got), what + " 应 NaN got=" + got);
    }
}
