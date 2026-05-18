package dev.engine_room.flywheel.backend.util;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.BitSet;

public final class AtomicBitSet {
    private static final VarHandle AA = MethodHandles.arrayElementVarHandle(int[].class);
    private static final VarHandle WORDS;

    static {
        try {
            WORDS = MethodHandles.lookup()
                    .findVarHandle(AtomicBitSet.class, "words", int[].class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final int[] EMPTY = new int[0];

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int[] words = EMPTY;

    public void set(int position) {
        if (position < 0) return;
        int wordIdx = position >>> 5;
        int mask = 1 << (position & 31);
        while (true) {
            int[] arr = ensureCapacity(wordIdx + 1);
            int cur;
            do {
                cur = (int) AA.getOpaque(arr, wordIdx);
                if ((cur & mask) != 0) {
                    if (words == arr) return;
                    break;
                }
            } while (!AA.compareAndSet(arr, wordIdx, cur, cur | mask));
            if (words == arr) return;
        }
    }

    public void clear(int position) {
        if (position < 0) return;
        int wordIdx = position >>> 5;
        int mask = 1 << (position & 31);
        while (true) {
            int[] arr = words;
            if (wordIdx >= arr.length) return;
            int cur;
            do {
                cur = (int) AA.getOpaque(arr, wordIdx);
                if ((cur & mask) == 0) {
                    if (words == arr) return;
                    break;
                }
            } while (!AA.compareAndSet(arr, wordIdx, cur, cur & ~mask));
            if (words == arr) return;
        }
    }

    public void set(int position, boolean value) {
        if (value) set(position);
        else clear(position);
    }

    public boolean get(int position) {
        int wordIdx = position >>> 5;
        int[] arr = words;
        if (wordIdx >= arr.length) return false;
        return ((int) AA.getAcquire(arr, wordIdx) & (1 << (position & 31))) != 0;
    }

    public void set(int fromIndex, int toIndex) {
        if (toIndex <= fromIndex) return;
        int fromWord = fromIndex >>> 5;
        int toWord = (toIndex - 1) >>> 5;
        int[] arr = ensureCapacity(toWord + 1);
        int fromMask = -1 << fromIndex;
        int toMask = -1 >>> -toIndex;
        if (fromWord == toWord) {
            casOr(arr, fromWord, fromMask & toMask);
        } else {
            casOr(arr, fromWord, fromMask);
            for (int i = fromWord + 1; i < toWord; i++) {
                AA.setRelease(arr, i, -1);
            }
            casOr(arr, toWord, toMask);
        }
    }

    public void clear(int fromIndex, int toIndex) {
        if (toIndex <= fromIndex) return;
        int[] arr = words;
        if (arr.length == 0) return;
        int fromWord = fromIndex >>> 5;
        int toWord = (toIndex - 1) >>> 5;
        if (fromWord >= arr.length) return;
        if (toWord >= arr.length) toWord = arr.length - 1;
        int fromMask = -1 << fromIndex;
        int toMask = -1 >>> -toIndex;
        if (fromWord == toWord) {
            casAnd(arr, fromWord, ~(fromMask & toMask));
        } else {
            casAnd(arr, fromWord, ~fromMask);
            for (int i = fromWord + 1; i < toWord; i++) {
                AA.setRelease(arr, i, 0);
            }
            casAnd(arr, toWord, ~toMask);
        }
    }

    public void clear() {
        int[] arr = words;
        for (int i = 0; i < arr.length; i++) {
            AA.setRelease(arr, i, 0);
        }
    }

    public boolean isEmpty() {
        int[] arr = words;
        for (int i = 0; i < arr.length; i++) {
            if ((int) AA.getAcquire(arr, i) != 0) return false;
        }
        return true;
    }

    public int cardinality() {
        int[] arr = words;
        int count = 0;
        for (int i = 0; i < arr.length; i++) {
            count += Integer.bitCount((int) AA.getAcquire(arr, i));
        }
        return count;
    }

    public int currentCapacity() {
        return words.length << 5;
    }

    public int nextSetBit(int fromIndex) {
        if (fromIndex < 0) throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        int[] arr = words;
        int wordIdx = fromIndex >>> 5;
        if (wordIdx >= arr.length) return -1;
        int word = (int) AA.getAcquire(arr, wordIdx) & (-1 << fromIndex);
        while (true) {
            if (word != 0) {
                return (wordIdx << 5) + Integer.numberOfTrailingZeros(word);
            }
            if (++wordIdx >= arr.length) return -1;
            word = (int) AA.getAcquire(arr, wordIdx);
        }
    }

    public long maxSetBit() {
        int[] arr = words;
        for (int i = arr.length - 1; i >= 0; i--) {
            int word = (int) AA.getAcquire(arr, i);
            if (word != 0) {
                return ((long) i << 5) + (31 - Integer.numberOfLeadingZeros(word));
            }
        }
        return -1;
    }

    public int nextClearBit(int fromIndex) {
        if (fromIndex < 0) throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);
        int[] arr = words;
        int wordIdx = fromIndex >>> 5;
        if (wordIdx >= arr.length) return fromIndex;
        int word = ~((int) AA.getAcquire(arr, wordIdx)) & (-1 << fromIndex);
        while (true) {
            if (word != 0) {
                return (wordIdx << 5) + Integer.numberOfTrailingZeros(word);
            }
            if (++wordIdx >= arr.length) return arr.length << 5;
            word = ~((int) AA.getAcquire(arr, wordIdx));
        }
    }

    public void forEachSetSpan(BitSpanConsumer consumer) {
        int[] arr = words;
        int start = -1;
        int end = -1;
        for (int i = 0; i < arr.length; i++) {
            int word = (int) AA.getAcquire(arr, i);
            if (word != 0) {
                int base = i << 5;
                for (int b = 0; b < 32; b++) {
                    if ((word & (1 << b)) != 0) {
                        int pos = base + b;
                        if (start == -1) start = pos;
                        end = pos;
                    } else if (start != -1) {
                        consumer.accept(start, end);
                        start = -1;
                        end = -1;
                    }
                }
            } else if (start != -1) {
                consumer.accept(start, end);
                start = -1;
                end = -1;
            }
        }
        if (start != -1) consumer.accept(start, end);
    }

    private static void casOr(int[] arr, int wordIdx, int mask) {
        int cur;
        do {
            cur = (int) AA.getOpaque(arr, wordIdx);
            if ((cur | mask) == cur) return;
        } while (!AA.compareAndSet(arr, wordIdx, cur, cur | mask));
    }

    private static void casAnd(int[] arr, int wordIdx, int mask) {
        int cur;
        do {
            cur = (int) AA.getOpaque(arr, wordIdx);
            if ((cur & ~mask) == 0) return;
        } while (!AA.compareAndSet(arr, wordIdx, cur, cur & mask));
    }

    public BitSet toBitSet() {
        BitSet out = new BitSet();
        for (int p = nextSetBit(0); p >= 0; p = nextSetBit(p + 1)) {
            out.set(p);
        }
        return out;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof AtomicBitSet other)) return false;
        int[] a = this.words;
        int[] b = other.words;
        int common = Math.min(a.length, b.length);
        for (int i = 0; i < common; i++) {
            if ((int) AA.getAcquire(a, i) != (int) AA.getAcquire(b, i)) return false;
        }
        for (int i = common; i < a.length; i++) {
            if ((int) AA.getAcquire(a, i) != 0) return false;
        }
        for (int i = common; i < b.length; i++) {
            if ((int) AA.getAcquire(b, i) != 0) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int[] arr = words;
        int trailingZeroWords = 0;
        for (int i = arr.length - 1; i >= 0; i--) {
            if ((int) AA.getAcquire(arr, i) != 0) break;
            trailingZeroWords++;
        }
        int len = arr.length - trailingZeroWords;
        int h = 1;
        for (int i = 0; i < len; i++) {
            h = 31 * h + (int) AA.getAcquire(arr, i);
        }
        return h;
    }

    @Override
    public String toString() {
        return toBitSet().toString();
    }

    private int[] ensureCapacity(int minWords) {
        int[] cur = words;
        while (cur.length < minWords) {
            int newLen = Math.max(minWords, cur.length == 0 ? 4 : cur.length << 1);
            int[] next = new int[newLen];
            System.arraycopy(cur, 0, next, 0, cur.length);
            if (WORDS.compareAndSet(this, cur, next)) {
                return next;
            }
            cur = words;
        }
        return cur;
    }

    @FunctionalInterface
    public interface BitSpanConsumer {
        void accept(int startInclusive, int endInclusive);
    }
}
