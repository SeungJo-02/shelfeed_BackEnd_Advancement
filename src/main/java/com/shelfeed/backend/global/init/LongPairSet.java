package com.shelfeed.backend.global.init;

import java.util.Arrays;

/**
 * (a, b) long 쌍의 중복 검사용 오픈 어드레싱 해시셋. {@code HashSet<Long>} 의 박싱·노드 오버헤드 없이
 * 수백만 쌍을 담기 위해 시더에서만 쓴다(외부 의존성 없음). 삭제는 지원하지 않는다.
 */
final class LongPairSet {
    private static final long EMPTY = Long.MIN_VALUE;
    private long[] a, b;
    private int size, mask;

    LongPairSet(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
        a = new long[cap]; b = new long[cap];
        Arrays.fill(a, EMPTY);
        mask = cap - 1;
    }

    /** 새 쌍이면 넣고 true, 이미 있으면 false. */
    boolean add(long x, long y) {
        if (size * 2 >= a.length) grow();
        int i = slot(x, y);
        while (a[i] != EMPTY) {
            if (a[i] == x && b[i] == y) return false;
            i = (i + 1) & mask;
        }
        a[i] = x; b[i] = y; size++;
        return true;
    }

    boolean contains(long x, long y) {
        int i = slot(x, y);
        while (a[i] != EMPTY) {
            if (a[i] == x && b[i] == y) return true;
            i = (i + 1) & mask;
        }
        return false;
    }

    int size() { return size; }

    private int slot(long x, long y) {
        long h = x * 0x9E3779B97F4A7C15L ^ (y + 0x7F4A7C15L) * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 32;
        return (int) h & mask;
    }

    private void grow() {
        long[] oa = a, ob = b;
        a = new long[oa.length * 2]; b = new long[oa.length * 2];
        Arrays.fill(a, EMPTY);
        mask = a.length - 1; size = 0;
        for (int i = 0; i < oa.length; i++) if (oa[i] != EMPTY) add(oa[i], ob[i]);
    }
}
