package common;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeUtil {

    private static final DateTimeFormatter FORMATTER
            = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public static String formatTime(long millis) {
        if (millis <= 0) {
            return "N/A";
        }
        return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(FORMATTER);
    }
}
