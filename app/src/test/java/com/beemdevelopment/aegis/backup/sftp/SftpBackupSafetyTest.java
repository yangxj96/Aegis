package com.beemdevelopment.aegis.backup.sftp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.beemdevelopment.aegis.ui.tasks.PasswordSlotDecryptTask;
import com.beemdevelopment.aegis.vault.VaultFile;
import com.beemdevelopment.aegis.vault.VaultFileCredentials;
import com.beemdevelopment.aegis.vault.Vault;
import com.beemdevelopment.aegis.vault.VaultManager;
import com.beemdevelopment.aegis.vault.VaultRepository;
import com.beemdevelopment.aegis.vault.slots.PasswordSlot;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class SftpBackupSafetyTest {
    private static final String DEVICE = "0123456789abcdef0123456789abcdef";
    private static final String OTHER = "fedcba9876543210fedcba9876543210";

    @Test
    public void retentionKeepsLastCompleteVersionOfOnlyThisDevice() {
        String first = SftpBackupStorage.createFilename(DEVICE, Instant.parse("2026-10-12T08:00:00Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
        String second = SftpBackupStorage.createFilename(DEVICE, Instant.parse("2026-10-13T08:00:00Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"));
        String foreign = SftpBackupStorage.createFilename(OTHER, Instant.parse("2026-10-11T08:00:00Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000003"));

        assertEquals(List.of(first), SftpBackupStorage.namesToDelete(
                Arrays.asList(second, foreign, first, first + ".partial", "notes.json"), DEVICE, 1));
        assertTrue(SftpBackupStorage.namesToDelete(List.of(second), DEVICE, 1).isEmpty());
        assertEquals(List.of(second), SftpBackupStorage.namesToDelete(
                Arrays.asList(first, second), DEVICE, 1, first));
        assertFalse(SftpBackupStorage.isBackupFilename(first + ".partial"));
        assertThrows(IllegalArgumentException.class,
                () -> SftpBackupStorage.namesToDelete(List.of(first), DEVICE, 0));
    }

    @Test
    public void snapshotIsEncryptedAndDecryptableWithExistingAegisPassword() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String resource = "/com/beemdevelopment/aegis/importers/aegis_encrypted.json";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input);
            VaultRepository.writeToFile(context, input);
        }
        File snapshot = null;
        try {
            snapshot = SftpBackupManager.snapshot(context);
            VaultFile exported = VaultFile.fromBytes(Files.readAllBytes(snapshot.toPath()));
            assertTrue(exported.isEncrypted());
            List<PasswordSlot> slots = exported.getHeader().getSlots().findAll(PasswordSlot.class);
            assertFalse(slots.isEmpty());
            PasswordSlotDecryptTask.Result result = PasswordSlotDecryptTask.decrypt(slots, "test".toCharArray());
            assertNotNull(result);
            VaultFileCredentials credentials = new VaultFileCredentials(result.getKey(), exported.getHeader().getSlots());
            assertNotNull(exported.getContent(credentials).getJSONArray("entries"));
        } finally {
            if (snapshot != null) {
                snapshot.delete();
            }
            VaultRepository.deleteFile(context);
        }
    }

    @Test
    public void plainVaultCannotBecomeRemoteSnapshot() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        VaultFile plain = new VaultFile();
        plain.setContent(new JSONObject());
        try {
            VaultRepository.writeToFile(context, new ByteArrayInputStream(plain.toBytes()));
            SftpBackupException error = assertThrows(SftpBackupException.class,
                    () -> SftpBackupManager.snapshot(context));
            assertEquals(SftpBackupException.Reason.VAULT_UNENCRYPTED, error.getReason());
        } finally {
            VaultRepository.deleteFile(context);
        }
    }

    @Test
    public void startupCleansOnlyInterruptedUploadSnapshots() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File snapshot = File.createTempFile("aegis-sftp-snapshot-", ".json", context.getNoBackupFilesDir());
        File restore = File.createTempFile("aegis-sftp-restore-", ".json", context.getNoBackupFilesDir());
        try {
            SftpBackupManager.cleanupInterruptedSnapshots(context);
            assertFalse(snapshot.exists());
            assertTrue(restore.exists());
        } finally {
            snapshot.delete();
            restore.delete();
        }
    }

    @Test
    public void failedUploadsRemovePartialAndPreservePreviousCompleteVersion() throws Exception {
        File snapshot = File.createTempFile("sftp-test-", ".json");
        try {
            try (FileOutputStream output = new FileOutputStream(snapshot)) {
                output.write(new byte[]{1, 2, 3, 4});
            }
            SftpConfig config = new SftpConfig("example.org", 22, "user", "/aegis", SftpAuthType.PASSWORD);
            for (int failure : new int[]{ChannelSftp.SSH_FX_FAILURE,
                    ChannelSftp.SSH_FX_PERMISSION_DENIED}) {
                FakeSftp channel = new FakeSftp(failure);
                channel.files.put("/aegis/previous.json", new byte[]{9});

                SftpBackupException error = assertThrows(SftpBackupException.class,
                        () -> SftpBackupStorage.upload(channel, config, snapshot, DEVICE, 10));
                assertEquals(SftpBackupException.Reason.REMOTE_WRITE, error.getReason());
                assertEquals(1, channel.files.size());
                assertTrue(channel.files.containsKey("/aegis/previous.json"));
                assertFalse(channel.renamed);
            }
        } finally {
            snapshot.delete();
        }
    }

    @Test
    public void successfulUploadPublishesOnlyAfterFullSizeIsVerified() throws Exception {
        File snapshot = File.createTempFile("sftp-test-", ".json");
        try {
            try (FileOutputStream output = new FileOutputStream(snapshot)) {
                output.write(new byte[]{1, 2, 3, 4});
            }
            FakeSftp channel = new FakeSftp(false);
            SftpConfig config = new SftpConfig("example.org", 22, "user", "/aegis", SftpAuthType.PASSWORD);
            SftpBackupStorage.UploadResult result = SftpBackupStorage.upload(channel, config, snapshot, DEVICE, 10);

            assertTrue(channel.renamed);
            assertTrue(channel.files.containsKey("/aegis/" + result.getFilename()));
            assertEquals(1, channel.files.size());
            assertFalse(result.isCleanupFailed());
        } finally {
            snapshot.delete();
        }
    }

    @Test
    public void cancelledUploadRemovesPartialWithoutPublishing() throws Exception {
        File snapshot = File.createTempFile("sftp-test-", ".json");
        try {
            Files.write(snapshot.toPath(), new byte[]{1, 2, 3, 4});
            FakeSftp channel = new FakeSftp(false);
            channel.files.put("/aegis/previous.json", new byte[]{9});
            SftpConfig config = new SftpConfig("example.org", 22, "user", "/aegis", SftpAuthType.PASSWORD);
            AtomicInteger checks = new AtomicInteger();

            SftpBackupException error = assertThrows(SftpBackupException.class,
                    () -> SftpBackupStorage.upload(channel, config, snapshot, DEVICE, 10,
                            () -> checks.incrementAndGet() == 1));
            assertEquals(SftpBackupException.Reason.CANCELLED, error.getReason());
            assertEquals(1, channel.files.size());
            assertTrue(channel.files.containsKey("/aegis/previous.json"));
            assertFalse(channel.renamed);
        } finally {
            snapshot.delete();
        }
    }

    @Test
    public void downloadRejectsTraversalOversizeAndIncompleteTransfer() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SftpConfig config = new SftpConfig("example.org", 22, "user", "/aegis", SftpAuthType.PASSWORD);
        FakeSftp channel = new FakeSftp(false);
        String name = SftpBackupStorage.createFilename(DEVICE, Instant.parse("2026-10-14T06:00:00Z"),
                UUID.fromString("00000000-0000-0000-0000-000000000004"));
        assertEquals(SftpBackupException.Reason.REMOTE_FILE_INVALID,
                assertThrows(SftpBackupException.class,
                        () -> SftpBackupStorage.download(channel, config, "../" + name,
                                context.getNoBackupFilesDir())).getReason());

        channel.files.put("/aegis/" + name, new byte[]{1, 2, 3});
        channel.reportedSize = SftpBackupStorage.MAX_RESTORE_BYTES + 1;
        assertEquals(SftpBackupException.Reason.REMOTE_FILE_TOO_LARGE,
                assertThrows(SftpBackupException.class,
                        () -> SftpBackupStorage.download(channel, config, name,
                                context.getNoBackupFilesDir())).getReason());
        channel.reportedSize = 4;
        File[] before = context.getNoBackupFilesDir().listFiles(
                (directory, filename) -> filename.startsWith("aegis-sftp-restore-"));
        assertEquals(SftpBackupException.Reason.SIZE_MISMATCH,
                assertThrows(SftpBackupException.class,
                        () -> SftpBackupStorage.download(channel, config, name,
                                context.getNoBackupFilesDir())).getReason());
        File[] after = context.getNoBackupFilesDir().listFiles(
                (directory, filename) -> filename.startsWith("aegis-sftp-restore-"));
        assertNotNull(before);
        assertNotNull(after);
        assertEquals(before.length, after.length);
    }

    @Test
    public void fullRestoreKeepsVerifiedLocalRollbackAndCanUndo() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        try (InputStream input = getClass().getResourceAsStream(
                "/com/beemdevelopment/aegis/importers/aegis_encrypted.json")) {
            assertNotNull(input);
            VaultRepository.writeToFile(context, input);
        }
        File candidate = null;
        try {
            VaultFile original = VaultRepository.readVaultFile(context);
            PasswordSlotDecryptTask.Result key = PasswordSlotDecryptTask.decrypt(
                    original.getHeader().getSlots().findAll(PasswordSlot.class), "test".toCharArray());
            assertNotNull(key);
            VaultFileCredentials credentials = new VaultFileCredentials(key.getKey(), original.getHeader().getSlots());
            VaultManager manager = new VaultManager(context, null);
            manager.loadFrom(original, credentials);
            int originalCount = manager.getVault().getEntries().size();
            assertTrue(originalCount > 0);

            VaultFile empty = new VaultFile();
            empty.setContent(new Vault().toJson(), credentials.exportable());
            candidate = File.createTempFile("aegis-restore-test-", ".json", context.getNoBackupFilesDir());
            try (FileOutputStream output = new FileOutputStream(candidate)) {
                output.write(empty.toBytes());
            }
            File selected = candidate;
            assertEquals(SftpBackupException.Reason.BACKUP_PASSWORD_INCORRECT,
                    assertThrows(SftpBackupException.class,
                            () -> SftpRestoreManager.prepare(context, selected, "wrong".toCharArray())).getReason());
            assertEquals(originalCount, manager.getVault().getEntries().size());

            SftpRestoreManager.PreparedRestore prepared = SftpRestoreManager.prepare(context, candidate,
                    "test".toCharArray());
            try {
                SftpRestoreManager.commit(context, manager, prepared);
            } catch (SftpBackupException e) {
                if (!System.getProperty("os.name").startsWith("Windows")) {
                    throw e;
                }
                // Robolectric's AtomicFile cannot replace an existing file on Windows.
                assertEquals(SftpBackupException.Reason.LOCAL_STORAGE, e.getReason());
                assertTrue(new File(context.getFilesDir(), VaultRepository.FILENAME + ".new").isFile());
                assertEquals(originalCount, manager.getVault().getEntries().size());
                assertEquals(originalCount, VaultRepository.fromFile(context,
                        VaultRepository.readVaultFile(context), credentials).getEntries().size());
                assertTrue(SftpRestoreManager.hasRollback(context));
                assertEquals(originalCount, SftpRestoreManager.prepareRollback(context,
                        "test".toCharArray()).getEntryCount());
                return;
            }
            assertEquals(0, manager.getVault().getEntries().size());
            assertTrue(SftpRestoreManager.hasRollback(context));
            SftpRestoreManager.PreparedRestore rollback = SftpRestoreManager.prepareRollback(context,
                    "test".toCharArray());
            assertEquals(originalCount, rollback.getEntryCount());
            SftpRestoreManager.commit(context, manager, rollback);
            assertEquals(originalCount, manager.getVault().getEntries().size());
        } finally {
            if (candidate != null) {
                candidate.delete();
            }
            VaultRepository.deleteFile(context);
        }
    }

    @Test
    public void firstInstallRestoreWritesAndLoadsCompleteEncryptedVault() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        VaultRepository.deleteFile(context);
        File candidate = File.createTempFile("aegis-new-device-", ".json", context.getNoBackupFilesDir());
        try {
            try (InputStream input = getClass().getResourceAsStream(
                    "/com/beemdevelopment/aegis/importers/aegis_encrypted.json");
                 FileOutputStream output = new FileOutputStream(candidate)) {
                assertNotNull(input);
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
            SftpRestoreManager.PreparedRestore prepared = SftpRestoreManager.prepare(context, candidate,
                    "test".toCharArray());
            VaultManager manager = new VaultManager(context, null);
            SftpRestoreManager.commit(context, manager, prepared);
            assertEquals(prepared.getEntryCount(), manager.getVault().getEntries().size());
            assertTrue(manager.getVault().isEncryptionEnabled());
            assertTrue(VaultRepository.readVaultFile(context).isEncrypted());
        } finally {
            candidate.delete();
            VaultRepository.deleteFile(context);
        }
    }

    @Test
    public void restoreRejectsPlainAndTamperedVaultBeforeWriting() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        VaultRepository.deleteFile(context);
        File candidate = File.createTempFile("aegis-invalid-", ".json", context.getNoBackupFilesDir());
        try {
            VaultFile plain = new VaultFile();
            plain.setContent(new Vault().toJson());
            try (FileOutputStream output = new FileOutputStream(candidate)) {
                output.write(plain.toBytes());
            }
            assertEquals(SftpBackupException.Reason.REMOTE_FILE_INVALID,
                    assertThrows(SftpBackupException.class,
                            () -> SftpRestoreManager.prepare(context, candidate,
                                    "test".toCharArray())).getReason());

            try (InputStream input = getClass().getResourceAsStream(
                    "/com/beemdevelopment/aegis/importers/aegis_encrypted.json")) {
                assertNotNull(input);
                byte[] bytes = input.readAllBytes();
                JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                String encrypted = json.getString("db");
                json.put("db", (encrypted.charAt(0) == 'A' ? "B" : "A") + encrypted.substring(1));
                try (FileOutputStream output = new FileOutputStream(candidate, false)) {
                    output.write(json.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            assertEquals(SftpBackupException.Reason.REMOTE_FILE_INVALID,
                    assertThrows(SftpBackupException.class,
                            () -> SftpRestoreManager.prepare(context, candidate,
                                    "test".toCharArray())).getReason());
            assertFalse(VaultRepository.fileExists(context));
        } finally {
            candidate.delete();
        }
    }

    private static final class FakeSftp extends ChannelSftp {
        final Map<String, byte[]> files = new HashMap<>();
        final int putFailureCode;
        boolean renamed;
        long reportedSize = -1;

        FakeSftp(boolean failPut) {
            this(failPut ? ChannelSftp.SSH_FX_FAILURE : -1);
        }

        FakeSftp(int putFailureCode) {
            this.putFailureCode = putFailureCode;
        }

        @Override
        public SftpATTRS lstat(String path) throws SftpException {
            if ("/aegis/".equals(path)) {
                return attrs(040000, 0);
            }
            byte[] contents = files.get(path);
            if (contents == null) {
                throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "missing");
            }
            return attrs(0100000, reportedSize >= 0 ? reportedSize : contents.length);
        }

        private SftpATTRS attrs(int type, long size) {
            SftpATTRS attrs = new SftpATTRS();
            attrs.setSIZE(size);
            attrs.setPERMISSIONS(0);
            try {
                Field permissions = SftpATTRS.class.getDeclaredField("permissions");
                permissions.setAccessible(true);
                permissions.setInt(attrs, type);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            return attrs;
        }

        @Override
        public void put(InputStream input, String path, com.jcraft.jsch.SftpProgressMonitor monitor, int mode)
                throws SftpException {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    files.put(path, output.toByteArray());
                    if (monitor != null && !monitor.count(count)) {
                        throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "cancelled");
                    }
                }
                files.put(path, output.toByteArray());
            } catch (IOException e) {
                throw new SftpException(ChannelSftp.SSH_FX_FAILURE, "read failure");
            }
            if (putFailureCode >= 0) {
                throw new SftpException(putFailureCode, "write refused");
            }
        }

        @Override
        public InputStream get(String path) throws SftpException {
            byte[] contents = files.get(path);
            if (contents == null) {
                throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "missing");
            }
            return new ByteArrayInputStream(contents);
        }

        @Override
        public void rename(String oldPath, String newPath) throws SftpException {
            renamed = true;
            byte[] contents = files.remove(oldPath);
            if (contents == null) {
                throw new SftpException(ChannelSftp.SSH_FX_NO_SUCH_FILE, "missing");
            }
            files.put(newPath, contents);
        }

        @Override
        public void rm(String path) {
            files.remove(path);
        }

        @Override
        public Vector<ChannelSftp.LsEntry> ls(String path) {
            return new Vector<>();
        }
    }
}
