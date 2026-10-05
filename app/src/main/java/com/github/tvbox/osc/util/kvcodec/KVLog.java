package com.github.tvbox.osc.util.kvcodec;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 编解码失败留痕出口。本包不依赖 Android(为可单测),故由 App 侧注入 {@code util.LOG} 实现;
 * 未注入时退化为 System.err,绝不静默丢弃(spec §4.6)。
 */
public final class KVLog {

    public interface Sink {
        void e(@NonNull String message);
    }

    private static final Sink FALLBACK = message -> System.err.println("[KV] " + message);

    private static volatile Sink sink = FALLBACK;

    private KVLog() {
    }

    public static void setSink(@Nullable Sink newSink) {
        sink = newSink == null ? FALLBACK : newSink;
    }

    public static void e(@NonNull String message) {
        sink.e(message);
    }
}
