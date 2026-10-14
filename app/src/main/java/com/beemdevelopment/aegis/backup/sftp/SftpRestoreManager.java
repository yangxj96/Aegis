package com.beemdevelopment.aegis.backup.sftp;

import android.content.Context;

import androidx.core.util.AtomicFile;

import com.beemdevelopment.aegis.ui.tasks.PasswordSlotDecryptTask;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultFileCredentials;
import com.beemdevelopment.aegis.vault.VaultFileException;
import com.beemdevelopment.aegis.vault.VaultManager;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.VaultRepositoryException;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;
import com.beemdevelopment.aegis.vault.slots.BiometricSlot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Validates a complete encrypted vault before any local state is replaced. */
public final class SftpRestoreManager {
    private static final String ROLLBACK_FILENAME = "aegis-sftp-rollback.json";

    public static final class PreparedRestore {
        private final VaultFile _file;
        private final VaultFileCredentials _credentials;
        private final int _entries;

        private PreparedRestore(VaultFile file, VaultFileCredentials credentials, int entries) {
            _file = file;
            _credentials = credentials;
            _entries = entries;
        }

        public int getEntryCount() {
            return _entries;
        }
    }

    private SftpRestoreManager() {
    }

    public static PreparedRestore prepare(Context context, File file, char[] password)
            throws SftpBackupException {
        try {
            return prepareBytes(context, readBounded(file), password, true);
        } catch (IOException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        }
    }

    private static PreparedRestore prepareBytes(Context context, byte[] bytes, char[] password,
                                                boolean requirePortable)
            throws SftpBackupException {
        try {
            VaultFile file = VaultFile.fromBytes(bytes);
            if (!file.isEncrypted() || file.getHeader().getSlots() == null) {
                throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_INVALID);
            }
            if (requirePortable && file.getHeader().getSlots().has(BiometricSlot.class)) {
                throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_INVALID);
            }
            List<PasswordSlot> slots = file.getHeader().getSlots().findAll(PasswordSlot.class);
            if (slots.isEmpty()) {
                throw new SftpBackupException(SftpBackupException.Reason.PASSWORD_SLOT_MISSING);
            }
            PasswordSlotDecryptTask.Result decrypted = PasswordSlotDecryptTask.decrypt(slots, password);
            if (decrypted == null) {
                throw new SftpBackupException(SftpBackupException.Reason.BACKUP_PASSWORD_INCORRECT);
            }
            VaultFileCredentials credentials = new VaultFileCredentials(decrypted.getKey(), file.getHeader().getSlots());
            int entries = VaultRepository.fromFile(context, file, credentials).getEntries().size();
            return new PreparedRestore(file, credentials, entries);
        } catch (VaultFileException | VaultRepositoryException | RuntimeException e) {
            throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_INVALID, e);
        }
    }

    public static boolean hasRollback(Context context) {
        return rollbackFile(context).getBaseFile().isFile();
    }

    public static PreparedRestore prepareRollback(Context context, char[] password)
            throws SftpBackupException {
        try {
            return prepareBytes(context, rollbackFile(context).readFully(), password, false);
        } catch (IOException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        }
    }

    public static void commit(Context context, VaultManager manager, PreparedRestore prepared)
            throws SftpBackupException {
        synchronized (manager) {
            if (manager.isVaultLoaded()) {
                saveVerifiedRollback(context, manager);
            } else if (VaultRepository.fileExists(context)) {
                throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE);
            }
            try {
                manager.replaceFrom(prepared._file, prepared._credentials);
            } catch (VaultRepositoryException | RuntimeException e) {
                throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
            }
        }
    }

    private static void saveVerifiedRollback(Context context, VaultManager manager)
            throws SftpBackupException {
        try {
            manager.save();
            VaultFile original = VaultRepository.readVaultFile(context);
            VaultFileCredentials credentials = manager.getVault().getCredentials();
            if (!original.isEncrypted() || credentials == null
                    || original.getHeader().getSlots().findAll(PasswordSlot.class).isEmpty()) {
                throw new SftpBackupException(SftpBackupException.Reason.ROLLBACK_UNAVAILABLE);
            }
            VaultRepository.fromFile(context, original, credentials);
            byte[] bytes = original.toBytes();
            AtomicFile rollback = rollbackFile(context);
            FileOutputStream output = null;
            try {
                output = rollback.startWrite();
                output.write(bytes);
                output.getFD().sync();
                rollback.finishWrite(output);
            } catch (IOException e) {
                if (output != null) {
                    rollback.failWrite(output);
                }
                throw e;
            }
            byte[] persisted = rollback.readFully();
            if (!Arrays.equals(bytes, persisted)) {
                throw new IOException("Rollback copy verification failed");
            }
            VaultRepository.fromFile(context, VaultFile.fromBytes(persisted), credentials);
        } catch (VaultRepositoryException | VaultFileException | IOException | RuntimeException e) {
            throw new SftpBackupException(SftpBackupException.Reason.ROLLBACK_UNAVAILABLE, e);
        }
    }

    private static AtomicFile rollbackFile(Context context) {
        return new AtomicFile(new File(context.getNoBackupFilesDir(), ROLLBACK_FILENAME));
    }

    private static byte[] readBounded(File file) throws IOException, SftpBackupException {
        if (!file.isFile() || file.length() <= 0) {
            throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_INVALID);
        }
        if (file.length() > SftpBackupStorage.MAX_RESTORE_BYTES) {
            throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_TOO_LARGE);
        }
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > SftpBackupStorage.MAX_RESTORE_BYTES) {
                    throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_TOO_LARGE);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
