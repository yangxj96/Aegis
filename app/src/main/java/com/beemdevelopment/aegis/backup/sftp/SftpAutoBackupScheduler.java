package com.beemdevelopment.aegis.backup.sftp;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.beemdevelopment.aegis.vault.VaultRepository;

import java.util.concurrent.TimeUnit;

/** Queues durable work after a successful local save. Each worker reads the newest vault. */
public final class SftpAutoBackupScheduler {
    public static final String WORK_NAME = "aegis_sftp_auto_backup";

    private SftpAutoBackupScheduler() {
    }

    public static void setEnabled(Context context, boolean enabled) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(SftpAutoBackupState.KEY_ENABLED, enabled).commit();
        if (enabled) {
            onVaultSaved(context);
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        }
    }

    public static void onVaultSaved(Context context) {
        if (SftpAutoBackupState.isEnabled(context) && VaultRepository.fileExists(context)) {
            SftpAutoBackupState.markDirty(context);
            enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE);
        }
    }

    public static void onSettingsChanged(Context context) {
        onVaultSaved(context);
    }

    public static void reconcile(Context context) {
        if (!SftpAutoBackupState.isEnabled(context) || !VaultRepository.fileExists(context)) {
            return;
        }
        if (!SftpAutoBackupState.deviceMatches(context)
                || SftpAutoBackupState.desiredRevision(context) == 0) {
            SftpAutoBackupState.markDirty(context);
        }
        if (!SftpAutoBackupState.isUpToDate(context, SftpBackupManager.currentTarget(context))) {
            enqueue(context, ExistingWorkPolicy.KEEP);
        }
    }

    private static void enqueue(Context context, ExistingWorkPolicy policy) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SftpAutoBackupWorker.class)
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request);
    }
}
