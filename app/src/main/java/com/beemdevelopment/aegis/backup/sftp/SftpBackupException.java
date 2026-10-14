package com.beemdevelopment.aegis.backup.sftp;

public final class SftpBackupException extends Exception {
    public enum Reason {
        NOT_CONFIGURED,
        CREDENTIALS_MISSING,
        PIN_MISSING,
        HOST_KEY_CHANGED,
        AUTHENTICATION_FAILED,
        CONNECTION_FAILED,
        REMOTE_DIRECTORY,
        REMOTE_WRITE,
        UPLOAD_BUSY,
        CANCELLED,
        REMOTE_READ,
        REMOTE_FILE_INVALID,
        REMOTE_FILE_TOO_LARGE,
        BACKUP_PASSWORD_INCORRECT,
        ROLLBACK_UNAVAILABLE,
        SIZE_MISMATCH,
        LOCAL_STORAGE,
        VAULT_UNENCRYPTED,
        PASSWORD_SLOT_MISSING
    }

    private final Reason _reason;

    public SftpBackupException(Reason reason) {
        super(reason.name());
        _reason = reason;
    }

    public SftpBackupException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        _reason = reason;
    }

    public Reason getReason() {
        return _reason;
    }
}
