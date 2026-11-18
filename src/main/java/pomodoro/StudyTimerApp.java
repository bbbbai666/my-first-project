package pomodoro;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.LocalDate;

/**
 * 番茄钟学习计时器 - 主程序（Swing GUI）
 *
 * 检测逻辑：
 *  - 运动检测：画面有人在动 = 人在场
 *  - 正脸检测：检测到正脸 = 面朝屏幕（专注）
 *  - 人在场 + 专注 → 学习计时中
 *  - 人在场 + 连续无正脸 → 分心（可能低头看手机）→ 暂停 + 提醒
 *  - 无运动 → 离开 → 停止计时
 *  - 离开超 20 分钟 → 自动结束本次会话
 *
 * 手动继续后给 10 分钟宽限期，避免戴帽子用户被反复暂停。
 */
public class StudyTimerApp extends JFrame {

    private static final String LOG_PATH =
            System.getProperty("user.home") + "/Desktop/学习记录.md";

    private final StudySession session = new StudySession();
    private final StudyLogger logger = new StudyLogger(LOG_PATH);
    private final ActivityMonitor activityMonitor = new ActivityMonitor();
    private CameraDetector cameraDetector;

    private JLabel stateLabel;
    private JLabel sessionTimeLabel;
    private JLabel todayTotalLabel;
    private JLabel statusLabel;
    private JButton pauseBtn;
    private JButton endBtn;

    private javax.swing.Timer uiTimer;
    private javax.swing.Timer detectTimer;

    public StudyTimerApp() {
        super("番茄钟学习计时器");
        initUI();
        initTimers();
        initCameraDetectorAsync();
        session.setTodayBaseline(
                logger.getDayTotal(LocalDate.now()).getSeconds());
    }

    private void initUI() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));
        getRootPane().setBorder(
                BorderFactory.createEmptyBorder(15, 20, 15, 20));

        JPanel center = new JPanel(new GridLayout(4, 1, 8, 8));

        stateLabel = new JLabel("状态：初始化中...", SwingConstants.CENTER);
        stateLabel.setFont(new Font("微软雅黑", Font.BOLD, 20));

        sessionTimeLabel = new JLabel("本次：00:00", SwingConstants.CENTER);
        sessionTimeLabel.setFont(new Font("微软雅黑", Font.PLAIN, 30));

        todayTotalLabel = new JLabel("今日总计：00:00", SwingConstants.CENTER);
        todayTotalLabel.setFont(new Font("微软雅黑", Font.PLAIN, 16));

        statusLabel = new JLabel("正在初始化摄像头...", SwingConstants.CENTER);
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        statusLabel.setForeground(Color.GRAY);

        center.add(stateLabel);
        center.add(sessionTimeLabel);
        center.add(todayTotalLabel);
        center.add(statusLabel);
        add(center, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 5));
        pauseBtn = new JButton("暂停");
        endBtn = new JButton("结束本次");
        pauseBtn.setEnabled(false);
        endBtn.setEnabled(false);
        pauseBtn.addActionListener(e -> onPause());
        endBtn.addActionListener(e -> onEnd());
        south.add(pauseBtn);
        south.add(endBtn);
        add(south, BorderLayout.SOUTH);

        setSize(480, 360);
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                cleanup();
            }
        });
    }

    private void initTimers() {
        uiTimer = new javax.swing.Timer(1000, e -> onTick());
        detectTimer = new javax.swing.Timer(3000, e -> onDetect());
    }

    private void initCameraDetectorAsync() {
        new Thread(() -> {
            cameraDetector = new CameraDetector();
            SwingUtilities.invokeLater(() -> {
                if (cameraDetector.isAvailable()) {
                    statusLabel.setText("检测已就绪 · 坐到摄像头前自动开始计时");
                } else {
                    statusLabel.setText("<html>摄像头不可用：" + cameraDetector.getUnavailableReason()
                            + "<br>已降级为仅鼠标活动检测</html>");
                }
                uiTimer.start();
                detectTimer.start();
                pauseBtn.setEnabled(true);
                endBtn.setEnabled(true);
                updateUI();
            });
        }, "CameraDetector-init").start();
    }

    private void onTick() {
        activityMonitor.sample();
        session.tick();

        StudyLogger.Record ended = session.checkAwayTimeout();
        if (ended != null) {
            logger.append(ended);
            session.setTodayBaseline(
                    logger.getDayTotal(LocalDate.now()).getSeconds());
            statusLabel.setText("离开超过 20 分钟，本次学习已结束（"
                    + StudyLogger.formatDuration(ended.duration) + "），等待重新开始");
        }
        updateUI();
    }

    private void onDetect() {
        new Thread(() -> {
            activityMonitor.sample();
            boolean present;
            boolean focused;
            if (cameraDetector != null && cameraDetector.isAvailable()) {
                CameraDetector.DetectionResult r = cameraDetector.detect();
                present = r.hasMotion;
                focused = r.hasFace;
            } else {
                // 降级：仅鼠标活动检测
                present = activityMonitor.isActiveWithinSeconds(60);
                focused = present; // 降级时无法检测专注度
            }
            final boolean p = present;
            final boolean f = focused;
            SwingUtilities.invokeLater(() -> {
                session.onDetectionResult(p, f);
                // 进入分心状态时弹窗提醒
                if (session.getState() == StudySession.State.DISTRACTED) {
                    showDistractWarning();
                }
                updateUI();
            });
        }, "Detection").start();
    }

    /** 分心提醒弹窗（每次进入 DISTRACTED 只弹一次，避免反复弹窗） */
    private boolean distractWarned = false;

    private void showDistractWarning() {
        if (distractWarned) {
            return; // 已经提醒过，不重复弹窗
        }
        distractWarned = true;
        statusLabel.setText("<html>⚠ 检测到您可能未注视屏幕（低头/看手机？），计时已暂停<br>"
                + "如在学习请点击「继续」，将给予 10 分钟宽限期</html>");
        // 播放系统提示音
        Toolkit.getDefaultToolkit().beep();
    }

    private void onPause() {
        StudySession.State s = session.getState();
        if (s == StudySession.State.STUDYING) {
            session.pause();
            pauseBtn.setText("继续");
            statusLabel.setText("已手动暂停（检测仍在运行）");
        } else if (s == StudySession.State.PAUSED || s == StudySession.State.DISTRACTED) {
            boolean present;
            boolean focused;
            if (cameraDetector != null && cameraDetector.isAvailable()) {
                CameraDetector.DetectionResult r = cameraDetector.detect();
                present = r.hasMotion;
                focused = r.hasFace;
            } else {
                present = activityMonitor.isActiveWithinSeconds(60);
                focused = present;
            }
            boolean ok = session.resume(present, focused);
            if (ok) {
                distractWarned = false; // 重置提醒标记
                pauseBtn.setText("暂停");
                StudySession.State now = session.getState();
                if (now == StudySession.State.STUDYING) {
                    long grace = session.getGraceRemainingMinutes();
                    statusLabel.setText("已继续学习计时（宽限期 " + grace + " 分钟）");
                } else {
                    statusLabel.setText("未检测到人，等待回归");
                }
            }
        }
        updateUI();
    }

    private void onEnd() {
        StudyLogger.Record rec = session.endManual();
        if (rec != null) {
            logger.append(rec);
            session.setTodayBaseline(
                    logger.getDayTotal(LocalDate.now()).getSeconds());
            statusLabel.setText("已手动结束本次学习（"
                    + StudyLogger.formatDuration(rec.duration) + "），等待重新开始");
        } else {
            statusLabel.setText("当前无有效学习时长，已重置");
        }
        distractWarned = false;
        pauseBtn.setText("暂停");
        updateUI();
    }

    private void updateUI() {
        StudySession.State s = session.getState();
        stateLabel.setText("状态：" + StudySession.stateText(s));
        sessionTimeLabel.setText("本次："
                + StudyLogger.formatDuration(java.time.Duration.ofSeconds(session.getStudySeconds())));
        todayTotalLabel.setText("今日总计："
                + StudyLogger.formatDuration(java.time.Duration.ofSeconds(session.getTodayTotalSeconds())));

        // 根据状态更新提示
        if (s == StudySession.State.AWAY) {
            statusLabel.setText("已离开，"
                    + StudyLogger.formatDuration(session.getAwayRemaining())
                    + " 后将自动结束本次学习");
            statusLabel.setForeground(Color.GRAY);
        } else if (s == StudySession.State.DISTRACTED) {
            statusLabel.setText("⚠ 可能未注视屏幕，计时已暂停 · 点「继续」恢复（宽限期 10 分钟）");
            statusLabel.setForeground(new Color(204, 120, 0));
        } else if (s == StudySession.State.STUDYING) {
            statusLabel.setForeground(Color.GRAY);
            long grace = session.getGraceRemainingMinutes();
            if (grace > 0
                    && !statusLabel.getText().startsWith("已手动")
                    && !statusLabel.getText().startsWith("已继续")) {
                statusLabel.setText("正在学习，检测正常（专注宽限期剩 " + grace + " 分钟）");
            } else if (!statusLabel.getText().startsWith("已手动")
                    && !statusLabel.getText().startsWith("已继续")) {
                statusLabel.setText("正在学习，检测正常");
            }
        } else {
            statusLabel.setForeground(Color.GRAY);
        }

        // 按钮状态
        boolean canPause = s == StudySession.State.STUDYING;
        boolean canResume = s == StudySession.State.PAUSED || s == StudySession.State.DISTRACTED;
        pauseBtn.setEnabled(canPause || canResume);
        pauseBtn.setText(canResume ? "继续" : "暂停");
    }

    private void cleanup() {
        uiTimer.stop();
        detectTimer.stop();
        if (cameraDetector != null) {
            new Thread(cameraDetector::close, "CameraDetector-close").start();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            StudyTimerApp app = new StudyTimerApp();
            app.setVisible(true);
        });
    }
}
