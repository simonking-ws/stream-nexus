package com.simonking.stream.nexus.common.util;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 消息ID 生成器
 *
 * <p>基于毫秒时间戳，保证【单调递增】：并发冲突时自增避让，
 * 时钟回拨时沿用上一个值继续递增。客户端与服务端均依赖该顺序判断消息新旧。
 *
 * @author simonking
 */
public final class IdGenerator {

    private static final AtomicLong LAST = new AtomicLong(System.currentTimeMillis());

    private IdGenerator() {
    }

    /**
     * 生成下一个单调递增的消息ID
     *
     * @return 纯数字字符串
     */
    public static String nextId() {
        while (true) {
            long prev = LAST.get();
            long next = Math.max(System.currentTimeMillis(), prev + 1);
            if (LAST.compareAndSet(prev, next)) {
                return Long.toString(next);
            }
        }
    }
}
