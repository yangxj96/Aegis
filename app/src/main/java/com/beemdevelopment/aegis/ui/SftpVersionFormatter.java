package com.beemdevelopment.aegis.ui;

import android.content.Context;
import android.text.format.Formatter;

import com.beemdevelopment.aegis.R;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;

/** Formats the timestamp and size shown for a remote SFTP backup. */
public final class SftpVersionFormatter {
    private static final String FILENAME_PREFIX = "aegis-backup-";
    private static final int TIMESTAMP_LENGTH = 16;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT);

    private SftpVersionFormatter() {
    }

    public static String formatTimestamp(Context context, String filename) {
        int start = FILENAME_PREFIX.length();
        int end = start + TIMESTAMP_LENGTH;
        try {
            LocalDateTime timestamp = LocalDateTime.parse(
                    filename.substring(start, end), TIMESTAMP_FORMAT);
            LocalDateTime localTimestamp = LocalDateTime.ofInstant(
                    timestamp.toInstant(ZoneOffset.UTC), java.time.ZoneId.systemDefault());
            return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(Locale.getDefault()).format(localTimestamp);
        } catch (DateTimeParseException | IndexOutOfBoundsException e) {
            return context.getString(R.string.sftp_version_time_unavailable);
        }
    }

    public static String formatSize(Context context, long size) {
        return Formatter.formatFileSize(context, size);
    }
}
