package com.beemdevelopment.aegis.ui;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.sftp.SftpBackupException;

/** Maps SFTP failures to the localized message shown in the UI. */
public final class SftpErrorMessages {
    private SftpErrorMessages() {
    }

    public static int getMessageResource(SftpBackupException.Reason reason) {
        if (reason == null) {
            return R.string.sftp_error_local_storage;
        }
        switch (reason) {
            case NOT_CONFIGURED: return R.string.sftp_error_not_configured;
            case CREDENTIALS_MISSING: return R.string.sftp_error_credentials_missing;
            case PIN_MISSING: return R.string.sftp_error_pin_missing;
            case HOST_KEY_CHANGED: return R.string.sftp_error_host_key_changed;
            case AUTHENTICATION_FAILED: return R.string.sftp_error_authentication_failed;
            case CONNECTION_FAILED: return R.string.sftp_error_connection_failed;
            case REMOTE_DIRECTORY: return R.string.sftp_error_remote_directory;
            case REMOTE_WRITE: return R.string.sftp_error_remote_write;
            case REMOTE_READ: return R.string.sftp_error_remote_read;
            case REMOTE_FILE_INVALID: return R.string.sftp_error_remote_file_invalid;
            case REMOTE_FILE_TOO_LARGE: return R.string.sftp_error_remote_file_too_large;
            case BACKUP_PASSWORD_INCORRECT: return R.string.sftp_error_backup_password_incorrect;
            case ROLLBACK_UNAVAILABLE: return R.string.sftp_error_rollback_unavailable;
            case SIZE_MISMATCH: return R.string.sftp_error_size_mismatch;
            case VAULT_UNENCRYPTED: return R.string.sftp_error_vault_unencrypted;
            case PASSWORD_SLOT_MISSING: return R.string.sftp_error_password_slot_missing;
            case UPLOAD_BUSY: return R.string.sftp_upload_busy;
            case CANCELLED: return R.string.sftp_error_cancelled;
            default: return R.string.sftp_error_local_storage;
        }
    }
}
