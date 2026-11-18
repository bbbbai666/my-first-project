package pomodoro;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.RectVector;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_objdetect.CascadeClassifier;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.bytedeco.opencv.global.opencv_core.absdiff;
import static org.bytedeco.opencv.global.opencv_core.countNonZero;
import static org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2GRAY;
import static org.bytedeco.opencv.global.opencv_imgproc.GaussianBlur;
import static org.bytedeco.opencv.global.opencv_imgproc.THRESH_BINARY;
import static org.bytedeco.opencv.global.opencv_imgproc.cvtColor;
import static org.bytedeco.opencv.global.opencv_imgproc.threshold;

/**
 * 统一摄像头检测器
 *
 * 一次抓帧同时完成两项检测：
 * 1. 运动检测（帧差法）：判断画面中是否有人在动 → 人在不在
 * 2. 正脸检测（Haar 级联）：判断是否面朝屏幕 → 在看屏幕还是低头看手机
 *
 * 不依赖人脸特征判断"在场"（运动检测兜底），
 * 正脸检测仅用于专注度判断，戴帽子检测不到正脸时可通过手动继续 + 宽限期处理。
 */
public class CameraDetector implements AutoCloseable {

    private static final String CASCADE_URL =
            "https://raw.githubusercontent.com/opencv/opencv/4.x/data/haarcascades/haarcascade_frontalface_default.xml";
    private static final String MODEL_DIR = System.getProperty("user.home") + "/.pomodoro";
    private static final String MODEL_FILE = MODEL_DIR + "/haarcascade_frontalface_default.xml";

    // 运动检测参数
    private static final double PIXEL_DIFF_THRESHOLD = 25.0;
    private static final int MOTION_PIXEL_THRESHOLD = 500;
    private static final int NO_MOTION_TOLERANCE = 2;
    private int noMotionCount = 0;

    private OpenCVFrameGrabber grabber;
    private OpenCVFrameConverter.ToMat converter;
    private CascadeClassifier classifier;
    private Mat prevGray;
    private boolean available = false;
    private String reason = "";

    public CameraDetector() {
        try {
            Path model = ensureModel();
            grabber = new OpenCVFrameGrabber(0);
            grabber.start();
            converter = new OpenCVFrameConverter.ToMat();
            classifier = new CascadeClassifier(model.toString());
            available = true;
        } catch (Exception e) {
            reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            available = false;
        }
    }

    private Path ensureModel() throws Exception {
        Path path = Paths.get(MODEL_FILE);
        if (Files.exists(path) && Files.size(path) > 1000) {
            return path;
        }
        Files.createDirectories(Paths.get(MODEL_DIR));
        System.out.println("[Camera] 正在下载正脸检测模型...");
        try (InputStream in = new URL(CASCADE_URL).openStream()) {
            Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("[Camera] 模型下载完成：" + path);
        return path;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getUnavailableReason() {
        return reason;
    }

    /** 检测结果 */
    public static class DetectionResult {
        public final boolean hasMotion;  // 画面有运动（人在）
        public final boolean hasFace;    // 检测到正脸（面朝屏幕）

        public DetectionResult(boolean hasMotion, boolean hasFace) {
            this.hasMotion = hasMotion;
            this.hasFace = hasFace;
        }
    }

    /**
     * 一次抓帧，同时完成运动检测和正脸检测。
     */
    public DetectionResult detect() {
        if (!available) {
            return new DetectionResult(true, true); // 降级
        }
        try {
            Frame frame = grabber.grab();
            if (frame == null) {
                return new DetectionResult(false, false);
            }
            Mat mat = converter.convert(frame);
            if (mat == null || mat.empty()) {
                return new DetectionResult(false, false);
            }

            // 转灰度（运动检测和正脸检测共用）
            Mat gray = new Mat();
            cvtColor(mat, gray, COLOR_BGR2GRAY);

            // ---- 运动检测 ----
            boolean rawMotion;
            if (prevGray == null) {
                rawMotion = true; // 第一帧
            } else {
                Mat diff = new Mat();
                absdiff(gray, prevGray, diff);
                Mat blurred = new Mat();
                GaussianBlur(diff, blurred, new Size(5, 5), 0);
                Mat binary = new Mat();
                threshold(blurred, binary, PIXEL_DIFF_THRESHOLD, 255, THRESH_BINARY);
                double motionPixels = countNonZero(binary);
                rawMotion = motionPixels > MOTION_PIXEL_THRESHOLD;
                diff.release();
                blurred.release();
                binary.release();
            }
            // 运动容忍：连续多次无运动才判定为不在场
            boolean hasMotion;
            if (rawMotion) {
                noMotionCount = 0;
                hasMotion = true;
            } else {
                noMotionCount++;
                hasMotion = noMotionCount < NO_MOTION_TOLERANCE;
            }

            // ---- 正脸检测 ----
            RectVector faces = new RectVector();
            classifier.detectMultiScale(gray, faces, 1.1, 2, 0, new Size(30, 30), new Size());
            boolean hasFace = faces.size() > 0;

            // 释放资源（gray 保留为下一帧基准）
            if (prevGray != null) {
                prevGray.release();
            }
            prevGray = gray;
            faces.close();
            mat.release();

            return new DetectionResult(hasMotion, hasFace);
        } catch (Exception e) {
            return new DetectionResult(false, false);
        }
    }

    @Override
    public void close() {
        try {
            if (prevGray != null) {
                prevGray.release();
            }
        } catch (Exception ignored) {
        }
        try {
            if (classifier != null) {
                classifier.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (converter != null) {
                converter.close();
            }
        } catch (Exception ignored) {
        }
        try {
            if (grabber != null) {
                grabber.stop();
            }
        } catch (Exception ignored) {
        }
    }
}
