package pomodoro;

import java.awt.MouseInfo;
import java.awt.Point;
import java.time.Instant;

/**
 * 屏幕活动检测器
 * 通过周期性采样鼠标位置判断用户是否在操作电脑。
 * 位置发生变化即视为"有活动"，并刷新最近活动时间。
 */
public class ActivityMonitor {

    private Point lastMousePos;
    private Instant lastActivityTime;

    public ActivityMonitor() {
        lastMousePos = currentMouse();
        lastActivityTime = Instant.now();
    }

    /**
     * 采样当前鼠标位置，若与上次不同则刷新活动时间。
     */
    public void sample() {
        Point now = currentMouse();
        if (now != null && !now.equals(lastMousePos)) {
            lastMousePos = now;
            lastActivityTime = Instant.now();
        }
    }

    /**
     * 判断最近 N 秒内是否有鼠标活动。
     */
    public boolean isActiveWithinSeconds(long seconds) {
        return Instant.now().minusSeconds(seconds).isBefore(lastActivityTime);
    }

    private Point currentMouse() {
        try {
            return MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            return lastMousePos;
        }
    }
}
