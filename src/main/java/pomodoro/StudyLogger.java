package pomodoro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 学习记录日志（Markdown 格式）
 * 启动时从 MD 文件加载历史记录到内存，每次会话结束追加并重写整个文件。
 * 文件按日期分组，每行一条记录，每天带"今日累计"列和"今日合计"。
 */
public class StudyLogger {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final Path logFile;
    private final List<Record> records = new ArrayList<>();

    /** 一条学习记录 */
    public static class Record {
        public final LocalDate date;
        public final LocalTime start;
        public final LocalTime end;
        public final Duration duration;

        public Record(LocalDate date, LocalTime start, LocalTime end, Duration duration) {
            this.date = date;
            this.start = start;
            this.end = end;
            this.duration = duration;
        }
    }

    public StudyLogger(String filePath) {
        this.logFile = Paths.get(filePath);
        load();
    }

    private void load() {
        if (!Files.exists(logFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(logFile)) {
                line = line.trim();
                if (!line.startsWith("|") || line.contains("---") || line.contains("开始")) {
                    continue;
                }
                String[] parts = line.split("\\|");
                if (parts.length < 5) {
                    continue;
                }
                try {
                    LocalDate d = LocalDate.parse(parts[1].trim(), DATE_FMT);
                    LocalTime s = LocalTime.parse(parts[2].trim(), TIME_FMT);
                    LocalTime e = LocalTime.parse(parts[3].trim(), TIME_FMT);
                    Duration dur = parseDuration(parts[4].trim());
                    records.add(new Record(d, s, e, dur));
                } catch (Exception ignored) {
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** 追加一条记录并持久化 */
    public synchronized void append(Record rec) {
        records.add(rec);
        save();
    }

    /** 获取指定日期的学习总时长 */
    public Duration getDayTotal(LocalDate date) {
        long seconds = records.stream()
                .filter(r -> r.date.equals(date))
                .mapToLong(r -> r.duration.getSeconds())
                .sum();
        return Duration.ofSeconds(seconds);
    }

    private void save() {
        try {
            ensureFile();
            List<String> lines = new ArrayList<>();
            lines.add("# 学习记录");
            lines.add("");
            lines.add("> 由番茄钟学习计时器自动生成，请勿手动编辑");
            lines.add("");

            // 按日期分组，最新日期在前（TreeMap 倒序）
            Map<LocalDate, List<Record>> byDate = new TreeMap<>(Comparator.reverseOrder());
            for (Record r : records) {
                byDate.computeIfAbsent(r.date, k -> new ArrayList<>()).add(r);
            }

            for (Map.Entry<LocalDate, List<Record>> entry : byDate.entrySet()) {
                LocalDate date = entry.getKey();
                List<Record> dayRecords = entry.getValue();
                long dayTotal = dayRecords.stream()
                        .mapToLong(r -> r.duration.getSeconds())
                        .sum();

                lines.add("## " + date.format(DATE_FMT));
                lines.add("");
                lines.add("| 日期 | 开始 | 结束 | 单次时长 | 今日累计 |");
                lines.add("|------|------|------|----------|----------|");
                long running = 0;
                for (Record r : dayRecords) {
                    running += r.duration.getSeconds();
                    lines.add("| " + r.date.format(DATE_FMT)
                            + " | " + r.start.format(TIME_FMT)
                            + " | " + r.end.format(TIME_FMT)
                            + " | " + formatDuration(r.duration)
                            + " | " + formatDuration(Duration.ofSeconds(running))
                            + " |");
                }
                lines.add("");
                lines.add("**今日合计：" + formatDuration(Duration.ofSeconds(dayTotal)) + "**");
                lines.add("");
                lines.add("---");
                lines.add("");
            }

            Files.write(logFile, lines);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void ensureFile() throws IOException {
        Path parent = logFile.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(logFile)) {
            Files.createFile(logFile);
        }
    }

    /** 格式化时长为 H:MM:SS 或 MM:SS */
    public static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) {
            return String.format("%d:%02d:%02d", h, m, s);
        }
        return String.format("%02d:%02d", m, s);
    }

    private static Duration parseDuration(String s) {
        String[] parts = s.split(":");
        try {
            if (parts.length == 2) {
                return Duration.ofMinutes(Long.parseLong(parts[0]))
                        .plusSeconds(Long.parseLong(parts[1]));
            } else if (parts.length == 3) {
                return Duration.ofHours(Long.parseLong(parts[0]))
                        .plusMinutes(Long.parseLong(parts[1]))
                        .plusSeconds(Long.parseLong(parts[2]));
            }
        } catch (NumberFormatException ignored) {
        }
        return Duration.ZERO;
    }
}
