package com.beemdevelopment.aegis.backup.sftp;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.io.IOException;
import java.util.Objects;

/** Durable upload revision and result state. No vault contents or credentials are stored here. */
public final class SftpAutoBackupState {
    public enum Phase { DISABLED, NEVER, PENDING, RUNNING, RETRYING, BLOCKED, SUCCESS, CLEANUP_WARNING }

    public static final class Status {
        private final Phase _phase;
        private final SftpBackupException.Reason _reason;
        private final long _time;
        private final String _filename;

        private Status(Phase phase, SftpBackupException.Reason reason, long time, String filename) {
            _phase = phase;
            _reason = reason;
            _time = time;
            _filename = filename;
        }

        public Phase getPhase() { return _phase; }
        public SftpBackupException.Reason getReason() { return _reason; }
        public long getTime() { return _time; }
        public String getFilename() { return _filename; }
    }

    public static final String KEY_ENABLED = "pref_sftp_auto_upload";
    private static final String DESIRED = "sftp_auto_desired_revision";
    private static final String UPLOADED = "sftp_auto_uploaded_revision";
    private static final String UPLOADED_TARGET = "sftp_auto_uploaded_target";
    private static final String DEVICE = "sftp_auto_device_id";
    private static final String PHASE = "sftp_auto_phase";
    private static final String REASON = "sftp_auto_reason";
    private static final String TIME = "sftp_auto_time";
    private static final String FILENAME = "sftp_auto_filename";

    private SftpAutoBackupState() {
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static synchronized long markDirty(Context context) {
        SharedPreferences preferences = prefs(context);
        long old = preferences.getLong(DESIRED, 0);
        long next = old == Long.MAX_VALUE ? 1 : old + 1;
        SharedPreferences.Editor edit = preferences.edit()
                .putLong(DESIRED, next)
                .putString(PHASE, Phase.PENDING.name())
                .remove(REASON)
                .remove(FILENAME);
        if (old == Long.MAX_VALUE) {
            edit.putLong(UPLOADED, 0).remove(UPLOADED_TARGET);
        }
        try {
            edit.putString(DEVICE, new SftpSettingsRepository(context).getDeviceId());
        } catch (IOException e) {
            edit.remove(DEVICE);
        }
        if (!edit.commit()) {
            throw new IllegalStateException("Could not persist pending SFTP backup");
        }
        return next;
    }

    public static long desiredRevision(Context context) {
        return prefs(context).getLong(DESIRED, 0);
    }

    public static boolean deviceMatches(Context context) {
        try {
            return Objects.equals(prefs(context).getString(DEVICE, null),
                    new SftpSettingsRepository(context).getDeviceId());
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isUpToDate(Context context, String target) {
        SharedPreferences preferences = prefs(context);
        return target != null && preferences.getLong(DESIRED, 0) > 0
                && preferences.getLong(UPLOADED, 0) >= preferences.getLong(DESIRED, 0)
                && target.equals(preferences.getString(UPLOADED_TARGET, null));
    }

    public static synchronized void markRunning(Context context) {
        writePhase(context, Phase.RUNNING, null);
    }

    public static synchronized void markRetrying(Context context, SftpBackupException.Reason reason) {
        writePhase(context, Phase.RETRYING, reason);
    }

    public static synchronized void markBlocked(Context context, SftpBackupException.Reason reason) {
        writePhase(context, Phase.BLOCKED, reason);
    }

    private static void writePhase(Context context, Phase phase, SftpBackupException.Reason reason) {
        prefs(context).edit()
                .putString(PHASE, phase.name())
                .putString(REASON, reason == null ? null : reason.name())
                .putLong(TIME, System.currentTimeMillis())
                .commit();
    }

    public static synchronized void markSuccess(Context context, long revision, String target,
                                                String filename, boolean cleanupFailed) {
        SharedPreferences preferences = prefs(context);
        long desired = preferences.getLong(DESIRED, 0);
        Phase phase = desired > revision || !Objects.equals(target, SftpBackupManager.currentTarget(context))
                ? Phase.PENDING : cleanupFailed ? Phase.CLEANUP_WARNING : Phase.SUCCESS;
        preferences.edit()
                .putLong(UPLOADED, revision)
                .putString(UPLOADED_TARGET, target)
                .putString(FILENAME, filename)
                .putString(PHASE, phase.name())
                .remove(REASON)
                .putLong(TIME, System.currentTimeMillis())
                .commit();
    }

    public static Status getStatus(Context context) {
        if (!isEnabled(context)) {
            return new Status(Phase.DISABLED, null, 0, null);
        }
        SharedPreferences preferences = prefs(context);
        long desired = preferences.getLong(DESIRED, 0);
        if (desired == 0) {
            return new Status(Phase.NEVER, null, 0, null);
        }
        Phase phase;
        try {
            phase = Phase.valueOf(preferences.getString(PHASE, Phase.PENDING.name()));
        } catch (IllegalArgumentException e) {
            phase = Phase.PENDING;
        }
        if (phase == Phase.RUNNING && !SftpAutoBackupWorker.isRunning()) {
            phase = Phase.PENDING;
        }
        String target = SftpBackupManager.currentTarget(context);
        boolean current = isUpToDate(context, target);
        if (current && phase != Phase.CLEANUP_WARNING) {
            phase = Phase.SUCCESS;
        } else if (!current && (phase == Phase.SUCCESS || phase == Phase.CLEANUP_WARNING)) {
            phase = Phase.PENDING;
        }
        SftpBackupException.Reason reason = null;
        try {
            String value = preferences.getString(REASON, null);
            if (value != null) {
                reason = SftpBackupException.Reason.valueOf(value);
            }
        } catch (IllegalArgumentException ignored) {
            reason = SftpBackupException.Reason.LOCAL_STORAGE;
        }
        return new Status(phase, reason, preferences.getLong(TIME, 0),
                current ? preferences.getString(FILENAME, null) : null);
    }
}
