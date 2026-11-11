package com.example.reviewreminder;

import java.util.ArrayList;

public final class SpellingPlan {
    public static final int ALL = 0;
    public static final int WRONG_ONLY = 1;
    public static final int NONE = 2;

    private SpellingPlan() {}

    public static int[] indices(int mode, int wordCount, int[] missed) {
        if (wordCount < 0) throw new IllegalArgumentException("单词数量不能为负数");
        if (mode == ALL) {
            int[] out = new int[wordCount];
            for (int i = 0; i < wordCount; i++) out[i] = i;
            return out;
        }
        if (mode == WRONG_ONLY) {
            if (missed == null || missed.length == 0) return new int[0];
            ArrayList<Integer> valid = new ArrayList<>();
            boolean[] seen = new boolean[wordCount];
            for (int index : missed) {
                if (index >= 0 && index < wordCount && !seen[index]) {
                    valid.add(index);
                    seen[index] = true;
                }
            }
            int[] out = new int[valid.size()];
            for (int i = 0; i < valid.size(); i++) out[i] = valid.get(i);
            return out;
        }
        if (mode == NONE) return new int[0];
        throw new IllegalArgumentException("未知的拼写方式");
    }

    public static int[] wrongBookIndices(int wordCount, int[] wrongCounts) {
        if (wordCount < 0) throw new IllegalArgumentException("单词数量不能为负数");
        if (wrongCounts == null || wrongCounts.length == 0) return new int[0];
        int length = Math.min(wordCount, wrongCounts.length);
        int count = 0;
        for (int i = 0; i < length; i++) if (wrongCounts[i] > 0) count++;
        int[] out = new int[count];
        for (int i = 0, p = 0; i < length; i++) if (wrongCounts[i] > 0) out[p++] = i;
        return out;
    }

    public static int[] include(int[] selected, int[] additional, int wordCount) {
        if (wordCount < 0) throw new IllegalArgumentException("单词数量不能为负数");
        boolean[] seen = new boolean[wordCount];
        ArrayList<Integer> valid = new ArrayList<>();
        appendUnique(selected, seen, valid);
        appendUnique(additional, seen, valid);
        int[] out = new int[valid.size()];
        for (int i = 0; i < valid.size(); i++) out[i] = valid.get(i);
        return out;
    }

    private static void appendUnique(int[] values, boolean[] seen, ArrayList<Integer> out) {
        if (values == null) return;
        for (int value : values) {
            if (value >= 0 && value < seen.length && !seen[value]) {
                seen[value] = true;
                out.add(value);
            }
        }
    }
}
