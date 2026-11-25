package com.huya.pure;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Lightweight TAF/Tars/JCE binary codec + raw WUP RPC client running inside com.duowan.kiwi.
 *
 * Routes requests through Huya's own {@code IReactRequestHandler.jceRequest} + {@code UniPacket}
 * so we can invoke WUP RPCs whose JCE request/response classes live only inside Huya's React Native
 * Hermes bundles (e.g. {@code wupui.queryFansGroupTaskInfo}, {@code wupui.operateFansBox},
 * {@code wupui.queryFGCommonWelfare}).
 */
final class RawWupClient {

    private static final String TAG = "HuyaPure";

    // TAF / JCE wire types
    static final int JCE_BYTE = 0;
    static final int JCE_SHORT = 1;
    static final int JCE_INT = 2;
    static final int JCE_LONG = 3;
    static final int JCE_FLOAT = 4;
    static final int JCE_DOUBLE = 5;
    static final int JCE_STRING1 = 6;
    static final int JCE_STRING4 = 7;
    static final int JCE_MAP = 8;
    static final int JCE_LIST = 9;
    static final int JCE_STRUCT_BEGIN = 10;
    static final int JCE_STRUCT_END = 11;
    static final int JCE_ZERO_TAG = 12;
    static final int JCE_SIMPLE_LIST = 13;

    interface RawWupCallback {
        void onSuccess(byte[] rspStructBody, String matchedKey);
        void onError(String reason);
    }

    private RawWupClient() {
    }

    private static void log(String msg) {
        Log.i(TAG, "[RawWup] " + msg);
        XposedBridge.log("[" + TAG + "][RawWup] " + msg);
    }

    /**
     * Serializes an existing Huya {@code com.duowan.taf.jce.JceStruct} (such as {@code UserId})
     * into its raw inner field bytes (without outer STRUCT_BEGIN/STRUCT_END wrapper).
     */
    static byte[] serializeHuyaJceStruct(ClassLoader cl, Object jceStruct) throws Exception {
        Class<?> josClass = XposedHelpers.findClass("com.duowan.taf.jce.JceOutputStream", cl);
        Object jos = XposedHelpers.newInstance(josClass);
        XposedHelpers.callMethod(jos, "setServerEncoding", "UTF-8");
        XposedHelpers.callMethod(jceStruct, "writeTo", jos);
        return (byte[]) XposedHelpers.callMethod(jos, "toByteArray");
    }

    /**
     * Invokes an arbitrary WUP RPC using Huya's {@code UniPacket} v3 and native
     * {@code IReactRequestHandler.jceRequest}.
     *
     * @param reqStructBody inner JCE field bytes of the request struct (without outer tag-0 wrapper)
     */
    static void sendRawWup(
            ClassLoader cl,
            android.os.Handler mainHandler,
            String servantName,
            String funcName,
            String reqKey,
            byte[] reqStructBody,
            long timeoutMs,
            final RawWupCallback cb
    ) {
        try {
            Object handler = resolveReactRequestHandler(cl);
            if (handler == null) {
                cb.onError("未找到 Huya IReactRequestHandler");
                return;
            }

            Class<?> uniPacketClass = XposedHelpers.findClass("com.duowan.jce.wup.UniPacket", cl);
            Object reqPkt = XposedHelpers.newInstance(uniPacketClass);
            XposedHelpers.callMethod(reqPkt, "useVersion3");
            XposedHelpers.callMethod(reqPkt, "setEncodeName", "UTF-8");
            XposedHelpers.callMethod(reqPkt, "setServantName", servantName);
            XposedHelpers.callMethod(reqPkt, "setFuncName", funcName);

            JceWriter wrap = new JceWriter();
            wrap.writeStructRaw(0, reqStructBody);
            byte[] wrappedReq = wrap.toByteArray();

            @SuppressWarnings("unchecked")
            HashMap<String, byte[]> newData = (HashMap<String, byte[]>)
                    XposedHelpers.getObjectField(reqPkt, "_newData");
            if (newData == null) {
                newData = new HashMap<>();
                XposedHelpers.setObjectField(reqPkt, "_newData", newData);
            }
            newData.put(reqKey != null && !reqKey.isEmpty() ? reqKey : "tReq", wrappedReq);

            byte[] encodedPacket = (byte[]) XposedHelpers.callMethod(reqPkt, "encode");

            Map<String, Object> params = new HashMap<>();
            params.put("buffer", encodedPacket);
            params.put("retryCount", 1);
            params.put("cacheKey", "");

            Class<?> cbInterface = XposedHelpers.findClass(
                    "com.huya.hybrid.react.core.IReactRequestHandler$JceCallback", cl);

            final AtomicBoolean settled = new AtomicBoolean(false);
            final Runnable timeoutTask = () -> {
                if (settled.compareAndSet(false, true)) {
                    cb.onError(servantName + "." + funcName + " 超时");
                }
            };
            mainHandler.postDelayed(timeoutTask, timeoutMs);

            Object cbProxy = Proxy.newProxyInstance(
                    cl,
                    new Class<?>[]{cbInterface},
                    (proxy, method, args) -> {
                        String mName = method.getName();
                        if ("equals".equals(mName)) {
                            return args != null && args.length == 1 && proxy == args[0];
                        }
                        if ("hashCode".equals(mName)) {
                            return System.identityHashCode(proxy);
                        }
                        if ("toString".equals(mName)) {
                            return "HuyaPureRawWupCallback@" + servantName + "." + funcName;
                        }
                        if ("onResponse".equals(mName) && args != null && args.length >= 1) {
                            if (!settled.compareAndSet(false, true)) {
                                return null;
                            }
                            mainHandler.removeCallbacks(timeoutTask);
                            byte[] respBytes = (args[0] instanceof byte[]) ? (byte[]) args[0] : null;
                            mainHandler.post(() -> decodeUniPacketResponse(cl, respBytes, cb));
                            return null;
                        }
                        if ("onError".equals(mName)) {
                            if (!settled.compareAndSet(false, true)) {
                                return null;
                            }
                            mainHandler.removeCallbacks(timeoutTask);
                            String errDesc = formatJceErrorArgs(args);
                            mainHandler.post(() -> cb.onError(errDesc));
                            return null;
                        }
                        return null;
                    }
            );

            Method jceReqMethod = handler.getClass().getMethod(
                    "jceRequest", Map.class, int.class, cbInterface);
            jceReqMethod.setAccessible(true);
            Object handle = jceReqMethod.invoke(handler, params, 0, cbProxy);
            if (handle == null && settled.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutTask);
                cb.onError("jceRequest 返回 null（可能 RN 请求开关未开启）");
            }
        } catch (Throwable t) {
            log("sendRawWup " + servantName + "." + funcName + " failed: " + t);
            cb.onError(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
    }

    private static void decodeUniPacketResponse(ClassLoader cl, byte[] respBytes, RawWupCallback cb) {
        if (respBytes == null || respBytes.length == 0) {
            cb.onError("服务端返回空数据");
            return;
        }
        try {
            Class<?> uniPacketClass = XposedHelpers.findClass("com.duowan.jce.wup.UniPacket", cl);
            Object respPkt = XposedHelpers.newInstance(uniPacketClass);
            XposedHelpers.callMethod(respPkt, "setEncodeName", "UTF-8");
            XposedHelpers.callMethod(respPkt, "decode", respBytes);

            @SuppressWarnings("unchecked")
            Map<String, byte[]> newData = (Map<String, byte[]>)
                    XposedHelpers.getObjectField(respPkt, "_newData");
            if (newData == null || newData.isEmpty()) {
                cb.onError("UniPacket _newData 为空");
                return;
            }

            // Check WUP return code stored under "" key (int32 at tag 0) if present.
            byte[] retBytes = newData.get("");
            if (retBytes != null && retBytes.length > 0) {
                JceStructView retView = JceReader.parseFields(retBytes);
                int wupRet = retView.getInt(0, 0);
                if (wupRet != 0) {
                    cb.onError("WUP ret=" + wupRet + ", keys=" + newData.keySet());
                    return;
                }
            }

            String matchedKey = null;
            byte[] rawPayload = null;
            for (String candidate : new String[]{"tRsp", "rsp", "tResp", "resp"}) {
                byte[] v = newData.get(candidate);
                if (v != null && v.length > 0) {
                    matchedKey = candidate;
                    rawPayload = v;
                    break;
                }
            }
            if (rawPayload == null) {
                for (Map.Entry<String, byte[]> e : newData.entrySet()) {
                    if (e.getKey() != null && !e.getKey().isEmpty()
                            && e.getValue() != null && e.getValue().length > 0) {
                        matchedKey = e.getKey();
                        rawPayload = e.getValue();
                        break;
                    }
                }
            }
            if (rawPayload == null) {
                cb.onError("未在 UniPacket 中找到响应体，keys=" + newData.keySet());
                return;
            }

            // Unwrap outer tag-0 struct wrapper if present.
            JceStructView outer = JceReader.parseFields(rawPayload);
            byte[] innerStruct = outer.getStructBytes(0);
            cb.onSuccess(innerStruct != null ? innerStruct : rawPayload, matchedKey);
        } catch (Throwable t) {
            cb.onError("解析 UniPacket 响应失败：" + t.getMessage());
        }
    }

    private static String formatJceErrorArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "未知错误";
        }
        if (args.length == 2 && args[0] instanceof Integer) {
            return "serverCode=" + args[0];
        }
        Object err = args[0];
        if (err == null) {
            return "JceError(null)";
        }
        try {
            int code = XposedHelpers.getIntField(err, "code");
            String msg = "";
            try {
                Object m = XposedHelpers.callMethod(err, "getMessage");
                if (m instanceof String) {
                    msg = (String) m;
                }
            } catch (Throwable ignored) {
            }
            return "code=" + code + (!msg.isEmpty() ? ", " + msg : "");
        } catch (Throwable ignored) {
            return String.valueOf(err);
        }
    }

    private static Object resolveReactRequestHandler(ClassLoader cl) {
        // 1. Prefer already-registered handler in HYReactIsolation.IMPL_MAP (unobfuscated).
        try {
            Class<?> isolationCls = XposedHelpers.findClassIfExists(
                    "com.huya.hybrid.react.HYReactIsolation", cl);
            if (isolationCls != null) {
                Object mapObj = XposedHelpers.getStaticObjectField(isolationCls, "IMPL_MAP");
                if (mapObj instanceof Map) {
                    for (Object impl : ((Map<?, ?>) mapObj).values()) {
                        if (impl == null) {
                            continue;
                        }
                        Object reqHandler = XposedHelpers.getObjectField(impl, "request");
                        if (reqHandler != null
                                && !reqHandler.getClass().getName().contains("DefaultImplementation")) {
                            return reqHandler;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        // 2. Fallback: find ReactConfigInitAction's IReactRequestHandler inner class dynamically.
        try {
            Class<?> iface = XposedHelpers.findClassIfExists(
                    "com.huya.hybrid.react.core.IReactRequestHandler", cl);
            Class<?> initAction = XposedHelpers.findClassIfExists(
                    "com.duowan.kiwi.launch.action.ReactConfigInitAction", cl);
            if (iface == null || initAction == null) {
                return null;
            }
            for (Class<?> inner : initAction.getDeclaredClasses()) {
                if (iface.isAssignableFrom(inner)) {
                    Object inst = instantiateHandler(inner, initAction);
                    if (inst != null) {
                        return inst;
                    }
                }
            }
            for (char c = 'a'; c <= 'z'; c++) {
                Class<?> cand = XposedHelpers.findClassIfExists(initAction.getName() + "$" + c, cl);
                if (cand != null && iface.isAssignableFrom(cand)) {
                    Object inst = instantiateHandler(cand, initAction);
                    if (inst != null) {
                        return inst;
                    }
                }
            }
        } catch (Throwable t) {
            log("resolveReactRequestHandler fallback error: " + t);
        }
        return null;
    }

    private static Object instantiateHandler(Class<?> handlerClass, Class<?> outerClass) {
        try {
            for (Constructor<?> ctor : handlerClass.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 0) {
                    return ctor.newInstance();
                }
                if (params.length == 1 && params[0] == outerClass) {
                    return ctor.newInstance(new Object[]{null});
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ── Minimal TAF/JCE Writer ───────────────────────────────────────────────

    static final class JceWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private void writeHead(int tag, int type) {
            if (tag < 15) {
                out.write(((tag & 0x0F) << 4) | (type & 0x0F));
            } else {
                out.write(0xF0 | (type & 0x0F));
                out.write(tag & 0xFF);
            }
        }

        void writeInt(int tag, int value) {
            if (value == 0) {
                writeHead(tag, JCE_ZERO_TAG);
            } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
                writeHead(tag, JCE_BYTE);
                out.write(value & 0xFF);
            } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
                writeHead(tag, JCE_SHORT);
                out.write((value >> 8) & 0xFF);
                out.write(value & 0xFF);
            } else {
                writeHead(tag, JCE_INT);
                out.write((value >> 24) & 0xFF);
                out.write((value >> 16) & 0xFF);
                out.write((value >> 8) & 0xFF);
                out.write(value & 0xFF);
            }
        }

        void writeLong(int tag, long value) {
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                writeInt(tag, (int) value);
            } else {
                writeHead(tag, JCE_LONG);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((value >> shift) & 0xFF));
                }
            }
        }

        void writeString(int tag, String value) {
            byte[] bytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : new byte[0];
            if (bytes.length <= 255) {
                writeHead(tag, JCE_STRING1);
                out.write(bytes.length & 0xFF);
            } else {
                writeHead(tag, JCE_STRING4);
                int len = bytes.length;
                out.write((len >> 24) & 0xFF);
                out.write((len >> 16) & 0xFF);
                out.write((len >> 8) & 0xFF);
                out.write(len & 0xFF);
            }
            out.write(bytes, 0, bytes.length);
        }

        void writeStructRaw(int tag, byte[] innerFields) {
            writeHead(tag, JCE_STRUCT_BEGIN);
            if (innerFields != null && innerFields.length > 0) {
                out.write(innerFields, 0, innerFields.length);
            }
            writeHead(0, JCE_STRUCT_END);
        }

        void writeLongList(int tag, List<Long> list) {
            writeHead(tag, JCE_LIST);
            int size = list != null ? list.size() : 0;
            writeInt(0, size);
            if (list != null) {
                for (Long v : list) {
                    writeLong(0, v != null ? v : 0L);
                }
            }
        }

        byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    // ── Minimal TAF/JCE Reader ───────────────────────────────────────────────

    static final class JceStructView {
        final Map<Integer, Object> fields = new HashMap<>();

        int getInt(int tag, int def) {
            Object v = fields.get(tag);
            if (v instanceof Number) {
                return ((Number) v).intValue();
            }
            return def;
        }

        long getLong(int tag, long def) {
            Object v = fields.get(tag);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
            return def;
        }

        String getString(int tag, String def) {
            Object v = fields.get(tag);
            return v instanceof String ? (String) v : def;
        }

        byte[] getStructBytes(int tag) {
            Object v = fields.get(tag);
            return v instanceof byte[] ? (byte[]) v : null;
        }

        JceStructView getStruct(int tag) {
            byte[] b = getStructBytes(tag);
            return b != null ? JceReader.parseFields(b) : null;
        }

        @SuppressWarnings("unchecked")
        List<Object> getList(int tag) {
            Object v = fields.get(tag);
            return v instanceof List ? (List<Object>) v : null;
        }
    }

    static final class JceReader {
        private final byte[] buf;
        private int pos;

        private JceReader(byte[] buf) {
            this.buf = buf != null ? buf : new byte[0];
            this.pos = 0;
        }

        static JceStructView parseFields(byte[] data) {
            JceReader r = new JceReader(data);
            return r.readStructFields(false);
        }

        private boolean hasRemaining() {
            return pos < buf.length;
        }

        private int readHeadTagAndType(int[] outTagType) {
            if (!hasRemaining()) {
                return -1;
            }
            int b = buf[pos++] & 0xFF;
            int type = b & 0x0F;
            int tag = (b >> 4) & 0x0F;
            if (tag == 15) {
                if (!hasRemaining()) {
                    return -1;
                }
                tag = buf[pos++] & 0xFF;
            }
            outTagType[0] = tag;
            outTagType[1] = type;
            return type;
        }

        private JceStructView readStructFields(boolean stopAtEnd) {
            JceStructView view = new JceStructView();
            int[] tt = new int[2];
            while (hasRemaining()) {
                int type = readHeadTagAndType(tt);
                if (type < 0) {
                    break;
                }
                if (type == JCE_STRUCT_END) {
                    if (stopAtEnd) {
                        break;
                    }
                    continue;
                }
                int tag = tt[0];
                Object val = readValue(type);
                view.fields.put(tag, val);
            }
            return view;
        }

        private Object readValue(int type) {
            switch (type) {
                case JCE_ZERO_TAG:
                    return 0L;
                case JCE_BYTE:
                    return (long) readSignedByte();
                case JCE_SHORT:
                    return (long) readShort();
                case JCE_INT:
                    return (long) readInt();
                case JCE_LONG:
                    return readLong();
                case JCE_FLOAT:
                    return Float.intBitsToFloat(readInt());
                case JCE_DOUBLE:
                    return Double.longBitsToDouble(readLong());
                case JCE_STRING1: {
                    int len = readUnsignedByte();
                    return readUtf8(len);
                }
                case JCE_STRING4: {
                    int len = readInt();
                    return readUtf8(Math.max(0, len));
                }
                case JCE_STRUCT_BEGIN: {
                    int start = pos;
                    skipToStructEnd();
                    int end = pos - 1; // exclude STRUCT_END byte
                    if (end < start) {
                        return new byte[0];
                    }
                    byte[] slice = new byte[end - start];
                    System.arraycopy(buf, start, slice, 0, slice.length);
                    return slice;
                }
                case JCE_LIST: {
                    int size = (int) readTaggedLong();
                    List<Object> list = new ArrayList<>(Math.max(0, Math.min(size, 256)));
                    int[] tt = new int[2];
                    for (int i = 0; i < size && hasRemaining(); i++) {
                        int elemType = readHeadTagAndType(tt);
                        if (elemType < 0) {
                            break;
                        }
                        list.add(readValue(elemType));
                    }
                    return list;
                }
                case JCE_MAP: {
                    int size = (int) readTaggedLong();
                    Map<Object, Object> map = new HashMap<>();
                    int[] tt = new int[2];
                    for (int i = 0; i < size && hasRemaining(); i++) {
                        int kType = readHeadTagAndType(tt);
                        Object k = kType >= 0 ? readValue(kType) : null;
                        int vType = readHeadTagAndType(tt);
                        Object v = vType >= 0 ? readValue(vType) : null;
                        map.put(k, v);
                    }
                    return map;
                }
                case JCE_SIMPLE_LIST: {
                    // Element type head (always BYTE=0), followed by length int at tag 0, then raw bytes
                    int[] tt = new int[2];
                    readHeadTagAndType(tt);
                    int size = (int) readTaggedLong();
                    int safeSize = Math.max(0, Math.min(size, buf.length - pos));
                    byte[] raw = new byte[safeSize];
                    System.arraycopy(buf, pos, raw, 0, safeSize);
                    pos += safeSize;
                    return raw;
                }
                default:
                    return null;
            }
        }

        private long readTaggedLong() {
            int[] tt = new int[2];
            int type = readHeadTagAndType(tt);
            if (type < 0) {
                return 0L;
            }
            Object v = readValue(type);
            return v instanceof Number ? ((Number) v).longValue() : 0L;
        }

        private void skipToStructEnd() {
            int[] tt = new int[2];
            while (hasRemaining()) {
                int type = readHeadTagAndType(tt);
                if (type < 0 || type == JCE_STRUCT_END) {
                    return;
                }
                readValue(type);
            }
        }

        private byte readSignedByte() {
            return hasRemaining() ? buf[pos++] : 0;
        }

        private int readUnsignedByte() {
            return hasRemaining() ? (buf[pos++] & 0xFF) : 0;
        }

        private short readShort() {
            int b1 = readUnsignedByte();
            int b2 = readUnsignedByte();
            return (short) ((b1 << 8) | b2);
        }

        private int readInt() {
            int b1 = readUnsignedByte();
            int b2 = readUnsignedByte();
            int b3 = readUnsignedByte();
            int b4 = readUnsignedByte();
            return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
        }

        private long readLong() {
            long hi = readInt() & 0xFFFFFFFFL;
            long lo = readInt() & 0xFFFFFFFFL;
            return (hi << 32) | lo;
        }

        private String readUtf8(int len) {
            int safeLen = Math.min(len, buf.length - pos);
            if (safeLen <= 0) {
                return "";
            }
            String s = new String(buf, pos, safeLen, StandardCharsets.UTF_8);
            pos += safeLen;
            return s;
        }
    }
}
