package org.json;

/** 自检垫片：org.json 最小实现（android.jar 里是 Stub，JVM 跑不了）。 */
public class JSONException extends RuntimeException {
    public JSONException(String s) { super(s); }
    public JSONException(String s, Throwable t) { super(s, t); }
}
