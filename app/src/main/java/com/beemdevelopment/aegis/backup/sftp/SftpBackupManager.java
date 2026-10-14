package com.beemdevelopment.aegis.backup.sftp;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.VaultRepositoryException;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** SFTP transfer coordinator shared by manual and persisted automatic uploads. */
public final class SftpBackupManager {
    public enum State { NEVER, RUNNING, SUCCESS, SUCCESS_CLEANUP_FAILED, FAILED, INTERRUPTED }

    public static final class Status {
        private final State _state;
        private final SftpBackupException.Reason _reason;
        private final long _time;
        private final String _filename;

        Status(State state, SftpBackupException.Reason reason, long time, String filename) {
            _state = state;
            _reason = reason;
            _time = time;
            _filename = filename;
        }

        public State getState() { return _state; }
        public SftpBackupException.Reason getReason() { return _reason; }
        public long getTime() { return _time; }
        public String getFilename() { return _filename; }
    }

    public interface Callback {
        void onFinished(Status status);
    }

    public static final class AutoUploadResult {
        private final String _target;
        private final SftpBackupStorage.UploadResult _upload;

        private AutoUploadResult(String target, SftpBackupStorage.UploadResult upload) {
            _target = target;
            _upload = upload;
        }

        public String getTarget() { return _target; }
        public String getFilename() { return _upload.getFilename(); }
        public boolean isCleanupFailed() { return _upload.isCleanupFailed(); }
    }

    private static final String STATUS_STATE = "sftp_upload_state";
    private static final String STATUS_REASON = "sftp_upload_reason";
    private static final String STATUS_TIME = "sftp_upload_time";
    private static final String STATUS_FILENAME = "sftp_upload_filename";
    private static final String STATUS_TARGET = "sftp_upload_target";
    private static final String SNAPSHOT_PREFIX = "aegis-sftp-snapshot-";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();

    private SftpBackupManager() {
    }

    public static boolean isUploadActive() {
        return ACTIVE.get();
    }

    public static void cleanupInterruptedSnapshots(Context context) {
        File[] files = context.getNoBackupFilesDir().listFiles((directory, name) ->
                name.startsWith(SNAPSHOT_PREFIX) && name.endsWith(".json"));
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    public static boolean startUpload(Context context, Callback callback) {
        if (!ACTIVE.compareAndSet(false, true)) {
            return false;
        }
        Context appContext = context.getApplicationContext();
        String target = currentTarget(appContext);
        writeStatus(appContext, target, State.RUNNING, null, null);
        EXECUTOR.execute(() -> {
            State state;
            SftpBackupException.Reason reason = null;
            String filename = null;
            try {
                SftpBackupStorage.UploadResult result = upload(appContext, () -> true)._upload;
                state = result.isCleanupFailed() ? State.SUCCESS_CLEANUP_FAILED : State.SUCCESS;
                filename = result.getFilename();
            } catch (SftpBackupException e) {
                state = State.FAILED;
                reason = e.getReason();
            } catch (RuntimeException e) {
                state = State.FAILED;
                reason = SftpBackupException.Reason.LOCAL_STORAGE;
            }
            Status status = writeStatus(appContext, target, state, reason, filename);
            ACTIVE.set(false);
            new Handler(Looper.getMainLooper()).post(() -> callback.onFinished(status));
        });
        return true;
    }

    public static Status getStatus(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String savedTarget = prefs.getString(STATUS_TARGET, null);
        if (!java.util.Objects.equals(savedTarget, currentTarget(context))) {
            return new Status(State.NEVER, null, 0, null);
        }
        State state;
        try {
            state = State.valueOf(prefs.getString(STATUS_STATE, State.NEVER.name()));
        } catch (IllegalArgumentException e) {
            state = State.NEVER;
        }
        if (state == State.RUNNING && !ACTIVE.get()) {
            return writeStatus(context, savedTarget, State.INTERRUPTED, null, null);
        }
        SftpBackupException.Reason reason = null;
        try {
            String value = prefs.getString(STATUS_REASON, null);
            if (value != null) {
                reason = SftpBackupException.Reason.valueOf(value);
            }
        } catch (IllegalArgumentException ignored) {
            reason = SftpBackupException.Reason.LOCAL_STORAGE;
        }
        return new Status(state, reason, prefs.getLong(STATUS_TIME, 0), prefs.getString(STATUS_FILENAME, null));
    }

    private static Status writeStatus(Context context, String target, State state,
                                      SftpBackupException.Reason reason, String filename) {
        long time = System.currentTimeMillis();
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putString(STATUS_STATE, state.name())
                .putString(STATUS_REASON, reason == null ? null : reason.name())
                .putLong(STATUS_TIME, time)
                .putString(STATUS_FILENAME, filename)
                .putString(STATUS_TARGET, target)
                .commit();
        return new Status(state, reason, time, filename);
    }

    static String currentTarget(Context context) {
        try {
            SftpConfig config = new SftpSettingsRepository(context).getConfig();
            return targetFor(config);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String targetFor(SftpConfig config) {
        return config.getHost().length() + ":" + config.getHost()
                + config.getPort() + ":" + config.getUsername().length() + ":" + config.getUsername()
                + config.getDirectory().length() + ":" + config.getDirectory()
                + config.getAuthType().name();
    }

    public static AutoUploadResult uploadAutomatically(Context context, BooleanSupplier keepRunning)
            throws SftpBackupException {
        if (!ACTIVE.compareAndSet(false, true)) {
            throw new SftpBackupException(SftpBackupException.Reason.UPLOAD_BUSY);
        }
        try {
            return upload(context.getApplicationContext(), keepRunning);
        } finally {
            ACTIVE.set(false);
        }
    }

    private static AutoUploadResult upload(Context context, BooleanSupplier keepRunning)
            throws SftpBackupException {
        if (!keepRunning.getAsBoolean()) {
            throw new SftpBackupException(SftpBackupException.Reason.CANCELLED);
        }
        SftpSettingsRepository settings = new SftpSettingsRepository(context);
        SftpConfig config = config(settings);
        SftpHostKey pin = pin(settings, config);
        File snapshot = snapshot(context);
        try (SftpCredentials credentials = credentials(context, config)) {
            try (SftpConnectionTester.Connection connection = SftpConnectionTester.open(config, credentials, pin)) {
                SftpBackupStorage.UploadResult result = SftpBackupStorage.upload(connection.getChannel(),
                        config, snapshot, deviceId(settings), settings.getRetentionCount(), keepRunning);
                return new AutoUploadResult(targetFor(config), result);
            } catch (SftpConnectionTester.ConnectionFailure e) {
                throw connectionFailure(e);
            } catch (IllegalArgumentException e) {
                throw new SftpBackupException(SftpBackupException.Reason.NOT_CONFIGURED, e);
            }
        } finally {
            if (!snapshot.delete()) {
                // This encrypted, app-private snapshot is never exposed as a completed backup.
            }
        }
    }

    public static List<SftpBackupStorage.Version> listVersions(Context context) throws SftpBackupException {
        SftpSettingsRepository settings = new SftpSettingsRepository(context);
        SftpConfig config = config(settings);
        SftpHostKey pin = pin(settings, config);
        try (SftpCredentials credentials = credentials(context, config)) {
            try (SftpConnectionTester.Connection connection = SftpConnectionTester.open(config, credentials, pin)) {
                return SftpBackupStorage.list(connection.getChannel(), config);
            } catch (SftpConnectionTester.ConnectionFailure e) {
                throw connectionFailure(e);
            }
        }
    }

    public static File downloadVersion(Context context, String filename) throws SftpBackupException {
        SftpSettingsRepository settings = new SftpSettingsRepository(context);
        SftpConfig config = config(settings);
        SftpHostKey pin = pin(settings, config);
        try (SftpCredentials credentials = credentials(context, config)) {
            try (SftpConnectionTester.Connection connection = SftpConnectionTester.open(config, credentials, pin)) {
                return SftpBackupStorage.download(connection.getChannel(), config, filename,
                        context.getNoBackupFilesDir());
            } catch (SftpConnectionTester.ConnectionFailure e) {
                throw connectionFailure(e);
            }
        }
    }

    static File snapshot(Context context) throws SftpBackupException {
        VaultFile file;
        try {
            file = VaultRepository.readVaultFile(context);
        } catch (VaultRepositoryException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        }
        if (!file.isEncrypted()) {
            throw new SftpBackupException(SftpBackupException.Reason.VAULT_UNENCRYPTED);
        }
        VaultFile exported = file.exportable();
        if (exported.getHeader().getSlots() == null
                || exported.getHeader().getSlots().findAll(PasswordSlot.class).isEmpty()) {
            throw new SftpBackupException(SftpBackupException.Reason.PASSWORD_SLOT_MISSING);
        }
        try {
            File snapshot = File.createTempFile(SNAPSHOT_PREFIX, ".json", context.getNoBackupFilesDir());
            try (FileOutputStream output = new FileOutputStream(snapshot)) {
                output.write(exported.toBytes());
            } catch (IOException | RuntimeException e) {
                snapshot.delete();
                throw e;
            }
            return snapshot;
        } catch (IOException | RuntimeException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        }
    }

    private static SftpConfig config(SftpSettingsRepository settings) throws SftpBackupException {
        try {
            return settings.getConfig();
        } catch (IllegalArgumentException e) {
            throw new SftpBackupException(SftpBackupException.Reason.NOT_CONFIGURED, e);
        }
    }

    private static SftpHostKey pin(SftpSettingsRepository settings, SftpConfig config)
            throws SftpBackupException {
        try {
            SftpHostKey pin = settings.getPinnedKey(config);
            if (pin == null) {
                throw new SftpBackupException(SftpBackupException.Reason.PIN_MISSING);
            }
            return pin;
        } catch (IOException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        }
    }

    private static SftpCredentials credentials(Context context, SftpConfig config)
            throws SftpBackupException {
        try {
            SftpCredentials value = new SftpCredentialStore(context).load();
            if (value == null || value.getType() != config.getAuthType()) {
                if (value != null) {
                    value.close();
                }
                throw new SftpBackupException(SftpBackupException.Reason.CREDENTIALS_MISSING);
            }
            return value;
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        }
    }

    private static String deviceId(SftpSettingsRepository settings) throws SftpBackupException {
        try {
            return settings.getDeviceId();
        } catch (IOException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        }
    }

    private static SftpBackupException connectionFailure(SftpConnectionTester.ConnectionFailure e) {
        switch (e.status) {
            case HOST_KEY_UNKNOWN:
                return new SftpBackupException(SftpBackupException.Reason.PIN_MISSING, e);
            case HOST_KEY_CHANGED:
                return new SftpBackupException(SftpBackupException.Reason.HOST_KEY_CHANGED, e);
            case AUTHENTICATION_FAILED:
            case INVALID_PRIVATE_KEY:
                return new SftpBackupException(SftpBackupException.Reason.AUTHENTICATION_FAILED, e);
            default:
                return new SftpBackupException(SftpBackupException.Reason.CONNECTION_FAILED, e);
        }
    }
}
