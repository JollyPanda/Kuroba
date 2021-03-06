package com.github.adamantcheese.chan.ui.captcha;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.github.adamantcheese.chan.utils.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.github.adamantcheese.chan.utils.StringUtils.centerEllipsize;

public class CaptchaHolder {
    private static final long INTERVAL = 5000;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH);
    private AtomicBoolean running = new AtomicBoolean(false);

    private Timer timer;

    private List<CaptchaValidationListener> captchaValidationListeners = new ArrayList<>();

    // this Deque operates as a queue, where the first added captcha token is the first removed, as it would be the first to expire
    @GuardedBy("itself")
    private final Deque<CaptchaInfo> captchaQueue = new ArrayDeque<>();

    public void addListener(CaptchaValidationListener listener) {
        captchaValidationListeners.add(listener);
    }

    public void removeListener(CaptchaValidationListener listener) {
        captchaValidationListeners.remove(listener);
    }

    public void addNewToken(String token, long tokenLifetime) {
        removeNotValidTokens();

        synchronized (captchaQueue) {
            captchaQueue.addLast(new CaptchaInfo(token, tokenLifetime + System.currentTimeMillis()));
            Logger.d(this, "New token added, validCount = " + captchaQueue.size() + ", token = " + captchaQueue.peek());
        }

        notifyListeners();
        startTimer();
    }

    public boolean hasToken() {
        removeNotValidTokens();

        synchronized (captchaQueue) {
            if (captchaQueue.isEmpty()) {
                stopTimer();
                return false;
            }
        }

        return true;
    }

    @Nullable
    public String getToken() {
        removeNotValidTokens();

        synchronized (captchaQueue) {
            if (captchaQueue.isEmpty()) {
                stopTimer();
                return null;
            }

            CaptchaInfo token = captchaQueue.removeFirst();
            Logger.d(this, "Got token " + token);

            notifyListeners();
            return token.token;
        }
    }

    private void startTimer() {
        if (running.compareAndSet(false, true)) {
            timer = new Timer();
            timer.scheduleAtFixedRate(new CheckCaptchaFreshnessTask(), INTERVAL, INTERVAL);

            Logger.d(this, "Timer started");
        }
    }

    private void stopTimer() {
        if (running.compareAndSet(true, false)) {
            timer.cancel();
            timer.purge();

            Logger.d(this, "Timer stopped");
        }
    }

    private void removeNotValidTokens() {
        long now = System.currentTimeMillis();
        boolean captchasCountDecreased = false;

        synchronized (captchaQueue) {
            Iterator<CaptchaInfo> it = captchaQueue.iterator();
            while (it.hasNext()) {
                CaptchaInfo captchaInfo = it.next();
                if (now > captchaInfo.validUntil) {
                    captchasCountDecreased = true;
                    it.remove();

                    Logger.d(this, "Captcha token expired, now = " + sdf.format(now) + ", token " + captchaInfo);
                }
            }

            if (captchaQueue.isEmpty()) {
                stopTimer();
            }
        }

        if (captchasCountDecreased) {
            notifyListeners();
        }
    }

    private void notifyListeners() {
        synchronized (captchaQueue) {
            for (CaptchaValidationListener listener : captchaValidationListeners) {
                listener.onCaptchaCountChanged(captchaQueue.size());
            }
        }
    }

    private class CheckCaptchaFreshnessTask
            extends TimerTask {
        @Override
        public void run() {
            removeNotValidTokens();
        }
    }

    public interface CaptchaValidationListener {
        void onCaptchaCountChanged(int validCaptchaCount);
    }

    private static class CaptchaInfo {
        public String token;
        public long validUntil;

        public CaptchaInfo(String token, long validUntil) {
            this.token = token;
            this.validUntil = validUntil;
        }

        @Override
        public int hashCode() {
            long mask = 0x00000000FFFFFFFFL;
            return token.hashCode() * 31 * (int) (validUntil & mask) * 31 * (int) ((validUntil >> 32) & mask);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof CaptchaInfo)) return false;
            CaptchaInfo otherCaptchaInfo = (CaptchaInfo) other;
            return token.equals(otherCaptchaInfo.token) && validUntil == otherCaptchaInfo.validUntil;
        }

        @NonNull
        @Override
        public String toString() {
            return "validUntil = " + sdf.format(validUntil) + ", token = " + centerEllipsize(token, 16);
        }
    }
}
