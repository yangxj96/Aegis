package com.beemdevelopment.aegis.backup.sftp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class SftpAutoBackupStateTest {
    private Context _context;
    private String _target;

    @Before
    public void setUp() {
        _context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(_context).edit().clear()
                .putBoolean(SftpAutoBackupState.KEY_ENABLED, true)
                .putString(SftpSettingsRepository.KEY_HOST, "example.org")
                .putString(SftpSettingsRepository.KEY_USERNAME, "test")
                .commit();
        _target = SftpBackupManager.currentTarget(_context);
    }

    @Test
    public void editDuringUploadRemainsPendingUntilLatestRevisionSucceeds() {
        long first = SftpAutoBackupState.markDirty(_context);
        assertTrue(SftpAutoBackupState.deviceMatches(_context));
        SftpAutoBackupState.markRunning(_context);
        long second = SftpAutoBackupState.markDirty(_context);

        SftpAutoBackupState.markSuccess(_context, first, _target, "first.json", false);
        assertFalse(SftpAutoBackupState.isUpToDate(_context, _target));
        assertEquals(SftpAutoBackupState.Phase.PENDING,
                SftpAutoBackupState.getStatus(_context).getPhase());

        SftpAutoBackupState.markSuccess(_context, second, _target, "second.json", false);
        assertTrue(SftpAutoBackupState.isUpToDate(_context, _target));
        assertEquals(SftpAutoBackupState.Phase.SUCCESS,
                SftpAutoBackupState.getStatus(_context).getPhase());
    }

    @Test
    public void targetChangeDoesNotReportOldBackupAsCurrent() {
        long revision = SftpAutoBackupState.markDirty(_context);
        SftpAutoBackupState.markSuccess(_context, revision, _target, "old.json", false);
        PreferenceManager.getDefaultSharedPreferences(_context).edit()
                .putString(SftpSettingsRepository.KEY_DIRECTORY, "/new-directory").commit();

        assertFalse(SftpAutoBackupState.isUpToDate(_context,
                SftpBackupManager.currentTarget(_context)));
        assertEquals(SftpAutoBackupState.Phase.PENDING,
                SftpAutoBackupState.getStatus(_context).getPhase());
    }

    @Test
    public void retryableAndConfigurationErrorsHaveDifferentOutcomes() {
        assertTrue(SftpAutoBackupWorker.isRetryable(SftpBackupException.Reason.CONNECTION_FAILED));
        assertTrue(SftpAutoBackupWorker.isRetryable(SftpBackupException.Reason.REMOTE_WRITE));
        assertTrue(SftpAutoBackupWorker.isRetryable(SftpBackupException.Reason.CANCELLED));
        assertFalse(SftpAutoBackupWorker.isRetryable(SftpBackupException.Reason.HOST_KEY_CHANGED));
        assertFalse(SftpAutoBackupWorker.isRetryable(SftpBackupException.Reason.AUTHENTICATION_FAILED));
        assertFalse(SftpAutoBackupWorker.isRetryable(SftpBackupException.Reason.PIN_MISSING));
    }

    @Test
    public void interruptedWorkerIsShownAsPendingAfterProcessRestart() {
        SftpAutoBackupState.markDirty(_context);
        SftpAutoBackupState.markRunning(_context);
        assertEquals(SftpAutoBackupState.Phase.PENDING,
                SftpAutoBackupState.getStatus(_context).getPhase());
    }

    @Test
    public void failureAfterEarlierSuccessDoesNotLookSuccessful() {
        long first = SftpAutoBackupState.markDirty(_context);
        SftpAutoBackupState.markSuccess(_context, first, _target, "earlier.json", false);
        SftpAutoBackupState.markDirty(_context);
        SftpAutoBackupState.markBlocked(_context, SftpBackupException.Reason.VAULT_UNENCRYPTED);

        SftpAutoBackupState.Status status = SftpAutoBackupState.getStatus(_context);
        assertEquals(SftpAutoBackupState.Phase.BLOCKED, status.getPhase());
        assertEquals(SftpBackupException.Reason.VAULT_UNENCRYPTED, status.getReason());
    }
}
