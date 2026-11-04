package com.example.reviewreminder;

import java.util.Calendar;
import java.util.List;

public final class Schedule {
    private Schedule() {}

    // Windows use local wall-clock minutes and may span midnight.
    public static long nextWindow(long requested, List<int[]> windows) {
        if (windows.isEmpty()) return requested;
        Calendar base = Calendar.getInstance();
        base.setTimeInMillis(requested);
        base.set(Calendar.HOUR_OF_DAY, 0);
        base.set(Calendar.MINUTE, 0);
        base.set(Calendar.SECOND, 0);
        base.set(Calendar.MILLISECOND, 0);
        long best = Long.MAX_VALUE;
        for (int day = -1; day <= 2; day++) {
            for (int[] window : windows) {
                Calendar start = (Calendar) base.clone();
                start.add(Calendar.DAY_OF_MONTH, day);
                start.add(Calendar.MINUTE, window[0]);
                Calendar end = (Calendar) base.clone();
                end.add(Calendar.DAY_OF_MONTH, day + (window[1] <= window[0] ? 1 : 0));
                end.add(Calendar.MINUTE, window[1]);
                long s = start.getTimeInMillis(), e = end.getTimeInMillis();
                if (requested >= s && requested < e) return requested;
                if (s >= requested && s < best) best = s;
            }
        }
        if (best == Long.MAX_VALUE) throw new IllegalArgumentException("No valid window");
        return best;
    }

    public static long due(long completedAt, long minutes, List<int[]> windows) {
        if(minutes<1||minutes>5256000||completedAt>Long.MAX_VALUE-minutes*60000L)throw new IllegalArgumentException("Invalid interval");
        return nextWindow(completedAt+minutes*60000L, windows);
    }
}
