package com.beemdevelopment.aegis.backup.sftp;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.beemdevelopment.aegis.Preferences;

import java.util.concurrent.atomic.AtomicBoolean;

/** A persisted, network-constrained upload of the latest saved encrypted vault. */
public final class SftpAutoBackupWorker extends Worker {
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    public SftpAutoBackupWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        RUNNING.set(true);
        try {
            return runUpload();
        } finally {
            RUNNING.set(false);
        }
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    private Result runUpload() {
        Context context = getApplicationContext();
        if (!SftpAutoBackupState.isEnabled(context)) {
            return Result.success();
        }
        String target = SftpBackupManager.currentTarget(context);
        if (SftpAutoBackupState.isUpToDate(context, target)) {
            return Result.success();
        }
        long revision = SftpAutoBackupState.desiredRevision(context);
        SftpAutoBackupState.markRunning(context);
        try {
            SftpBackupManager.AutoUploadResult uploaded = SftpBackupManager.uploadAutomatically(context,
                    () -> !isStopped() && SftpAutoBackupState.isEnabled(context));
            if (isStopped() || !SftpAutoBackupState.isEnabled(context)) {
                return Result.retry();
            }
            SftpAutoBackupState.markSuccess(context, revision, uploaded.getTarget(),
                    uploaded.getFilename(), uploaded.isCleanupFailed());
            if (SftpAutoBackupState.isUpToDate(context, SftpBackupManager.currentTarget(context))) {
                new Preferences(context).setIsBackupReminderNeeded(false);
            }
            return Result.success();
        } catch (SftpBackupException e) {
            if (isStopped() || !SftpAutoBackupState.isEnabled(context)) {
                return Result.retry();
            }
            if (isRetryable(e.getReason())) {
                SftpAutoBackupState.markRetrying(context, e.getReason());
                return Result.retry();
            }
            SftpAutoBackupState.markBlocked(context, e.getReason());
            return Result.success();
        } catch (RuntimeException e) {
            SftpAutoBackupState.markRetrying(context, SftpBackupException.Reason.LOCAL_STORAGE);
            return Result.retry();
        }
    }

    static boolean isRetryable(SftpBackupException.Reason reason) {
        switch (reason) {
            case CONNECTION_FAILED:
            case REMOTE_DIRECTORY:
            case REMOTE_WRITE:
            case SIZE_MISMATCH:
            case LOCAL_STORAGE:
            case UPLOAD_BUSY:
            case CANCELLED:
                return true;
            default:
                return false;
        }
    }
}
