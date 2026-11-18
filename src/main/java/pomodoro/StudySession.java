package pomodoro;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * 学习会话状态机
 *
 * 状态流转：
 *   IDLE       --人在+专注-->        STUDYING
 *   STUDYING   --人离开-->           AWAY
 *   STUDYING   --人在但连续无正脸-->  DISTRACTED（暂停+提醒）
 *   DISTRACTED --恢复专注-->         STUDYING
 *   DISTRACTED --人离开-->           AWAY
 *   AWAY       --人在+专注-->        STUDYING
 *   AWAY       --人在但不专注-->     DISTRACTED
 *   AWAY       --离开>20分钟-->      结束→IDLE
 *   STUDYING   --手动暂停-->         PAUSED
 *   PAUSED/DISTRACTED --手动继续-->  STUDYING（触发10分钟宽限期）
 *
 * 宽限期：手动继续后 10 分钟内不因"无正脸"进入 DISTRACTED，
 *         避免戴帽子用户被反复暂停。
 */
public class StudySession {

    public enum State { IDLE, STUDYING, AWAY, DISTRACTED, PAUSED }

    private static final Duration AWAY_TIMEOUT = Duration.ofMinutes(20);
    /** 连续多少次检测不到正脸才判定为分心（3 次 × 3 秒 ≈ 9 秒） */
    private static final int NO_FACE_THRESHOLD = 3;
    /** 手动继续后的宽限期（毫秒），期间不因无正脸暂停 */
    private static final long GRACE_PERIOD_MS = 10 * 60 * 1000L;

    private State state = State.IDLE;
    private long studySeconds = 0;
    private LocalDate sessionDate;
    private LocalTime sessionStartClock;
    private Instant awayStart;
    private long todayBaselineSeconds = 0;

    // 分心检测
    private int noFaceCount = 0;
    private long distractGraceUntil = 0;

    public void setTodayBaseline(long seconds) {
        this.todayBaselineSeconds = seconds;
    }

    /**
     * 根据检测结果驱动状态。
     * @param present  人在场（有运动）
     * @param focused  专注（检测到正脸）
     */
    public void onDetectionResult(boolean present, boolean focused) {
        boolean inGrace = System.currentTimeMillis() < distractGraceUntil;

        switch (state) {
            case IDLE:
                if (present && focused) {
                    beginSession();
                }
                break;

            case STUDYING:
                if (!present) {
                    state = State.AWAY;
                    awayStart = Instant.now();
                    noFaceCount = 0;
                } else if (!focused && !inGrace) {
                    noFaceCount++;
                    if (noFaceCount >= NO_FACE_THRESHOLD) {
                        state = State.DISTRACTED;
                    }
                } else {
                    noFaceCount = 0;
                }
                break;

            case DISTRACTED:
                if (!present) {
                    state = State.AWAY;
                    awayStart = Instant.now();
                } else if (focused) {
                    state = State.STUDYING;
                    noFaceCount = 0;
                }
                // 不专注时保持 DISTRACTED（暂停计时，等待恢复或手动继续）
                break;

            case AWAY:
                if (present && focused) {
                    state = State.STUDYING;
                    awayStart = null;
                    noFaceCount = 0;
                } else if (present && !focused) {
                    state = State.DISTRACTED;
                }
                break;

            case PAUSED:
                // 暂停期间检测继续但不自动改变状态
                break;
        }
    }

    private void beginSession() {
        studySeconds = 0;
        sessionDate = LocalDate.now();
        sessionStartClock = LocalTime.now();
        awayStart = null;
        noFaceCount = 0;
        state = State.STUDYING;
    }

    public void tick() {
        if (state == State.STUDYING) {
            studySeconds++;
        }
        // DISTRACTED / AWAY / PAUSED / IDLE 都不计时
    }

    public StudyLogger.Record checkAwayTimeout() {
        if (state == State.AWAY && awayStart != null) {
            if (Duration.between(awayStart, Instant.now()).compareTo(AWAY_TIMEOUT) >= 0) {
                return endAndReset();
            }
        }
        return null;
    }

    public boolean pause() {
        if (state == State.STUDYING) {
            state = State.PAUSED;
            return true;
        }
        return false;
    }

    /**
     * 手动继续（从 PAUSED 或 DISTRACTED 恢复）。
     * 设置 10 分钟宽限期，避免戴帽子用户被反复暂停。
     */
    public boolean resume(boolean present, boolean focused) {
        if (state == State.PAUSED || state == State.DISTRACTED) {
            // 手动继续 = 用户确认在学习，给宽限期
            distractGraceUntil = System.currentTimeMillis() + GRACE_PERIOD_MS;
            noFaceCount = 0;
            if (present) {
                state = State.STUDYING;
                awayStart = null;
            } else {
                state = State.AWAY;
                awayStart = Instant.now();
            }
            return true;
        }
        return false;
    }

    public StudyLogger.Record endManual() {
        return endAndReset();
    }

    private StudyLogger.Record endAndReset() {
        StudyLogger.Record rec = null;
        if (studySeconds > 0 && sessionDate != null) {
            rec = new StudyLogger.Record(
                    sessionDate,
                    sessionStartClock,
                    LocalTime.now(),
                    Duration.ofSeconds(studySeconds));
        }
        studySeconds = 0;
        sessionDate = null;
        sessionStartClock = null;
        awayStart = null;
        noFaceCount = 0;
        distractGraceUntil = 0;
        state = State.IDLE;
        return rec;
    }

    public State getState() {
        return state;
    }

    public long getStudySeconds() {
        return studySeconds;
    }

    public long getTodayTotalSeconds() {
        return todayBaselineSeconds + studySeconds;
    }

    public Duration getAwayDuration() {
        if (state == State.AWAY && awayStart != null) {
            return Duration.between(awayStart, Instant.now());
        }
        return Duration.ZERO;
    }

    public Duration getAwayRemaining() {
        if (state == State.AWAY && awayStart != null) {
            Duration elapsed = Duration.between(awayStart, Instant.now());
            return AWAY_TIMEOUT.minus(elapsed);
        }
        return AWAY_TIMEOUT;
    }

    /** 宽限期剩余分钟数（用于 UI 提示） */
    public long getGraceRemainingMinutes() {
        long remaining = distractGraceUntil - System.currentTimeMillis();
        return remaining > 0 ? remaining / 60000 : 0;
    }

    public static String stateText(State s) {
        switch (s) {
            case IDLE: return "待机（等待检测）";
            case STUDYING: return "学习计时中";
            case AWAY: return "已离开（暂停计时）";
            case DISTRACTED: return "分心提醒（已暂停）";
            case PAUSED: return "手动暂停";
            default: return s.toString();
        }
    }
}
