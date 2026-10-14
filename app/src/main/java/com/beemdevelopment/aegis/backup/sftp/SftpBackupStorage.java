package com.beemdevelopment.aegis.backup.sftp;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.Vector;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Transfers only encrypted snapshots supplied by SftpBackupManager. */
public final class SftpBackupStorage {
    public static final long MAX_RESTORE_BYTES = 32L * 1024 * 1024;
    private static final String PREFIX = "aegis-backup-";
    private static final Pattern NAME = Pattern.compile(
            "^aegis-backup-(\\d{8}T\\d{6}Z)-([0-9a-f]{32})-([0-9a-f]{32})\\.json$");
    private static final DateTimeFormatter UTC_NAME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    public static final class Version {
        private final String _name;
        private final long _size;

        Version(String name, long size) {
            _name = name;
            _size = size;
        }

        public String getName() {
            return _name;
        }

        public long getSize() {
            return _size;
        }
    }

    public static final class UploadResult {
        private final String _filename;
        private final boolean _cleanupFailed;

        UploadResult(String filename, boolean cleanupFailed) {
            _filename = filename;
            _cleanupFailed = cleanupFailed;
        }

        public String getFilename() {
            return _filename;
        }

        public boolean isCleanupFailed() {
            return _cleanupFailed;
        }
    }

    private SftpBackupStorage() {
    }

    public static String createFilename(String deviceId, Instant now, UUID random) {
        if (!deviceId.matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid SFTP device ID");
        }
        return PREFIX + UTC_NAME.format(now) + "-" + deviceId + "-"
                + random.toString().replace("-", "") + ".json";
    }

    public static boolean isBackupFilename(String name) {
        return NAME.matcher(name).matches();
    }

    public static boolean isOwnBackupFilename(String name, String deviceId) {
        Matcher matcher = NAME.matcher(name);
        return matcher.matches() && matcher.group(2).equals(deviceId);
    }

    public static List<String> namesToDelete(List<String> filenames, String deviceId, int keep) {
        return namesToDelete(filenames, deviceId, keep, null);
    }

    static List<String> namesToDelete(List<String> filenames, String deviceId, int keep,
                                      String justUploaded) {
        if (keep < 1) {
            throw new IllegalArgumentException("At least one version must be kept");
        }
        List<String> owned = new ArrayList<>();
        for (String name : filenames) {
            if (isOwnBackupFilename(name, deviceId)) {
                owned.add(name);
            }
        }
        owned.sort(String::compareTo);
        int deleteCount = Math.max(0, owned.size() - keep);
        if (justUploaded != null) {
            owned.remove(justUploaded);
        }
        return new ArrayList<>(owned.subList(0, Math.min(deleteCount, owned.size())));
    }

    static UploadResult upload(ChannelSftp channel, SftpConfig config, File snapshot,
                               String deviceId, int keep) throws SftpBackupException {
        return upload(channel, config, snapshot, deviceId, keep, () -> true);
    }

    static UploadResult upload(ChannelSftp channel, SftpConfig config, File snapshot,
                               String deviceId, int keep, BooleanSupplier keepRunning)
            throws SftpBackupException {
        if (!keepRunning.getAsBoolean()) {
            throw new SftpBackupException(SftpBackupException.Reason.CANCELLED);
        }
        String directory = directory(config);
        verifyDirectory(channel, directory);
        String filename = createFilename(deviceId, Instant.now(), UUID.randomUUID());
        String complete = directory + filename;
        String partial = complete + ".partial";
        boolean committed = false;
        boolean partialCreated = false;
        try {
            // The final name includes a random value. Never overwrite any existing object.
            if (exists(channel, complete) || exists(channel, partial)) {
                throw new SftpBackupException(SftpBackupException.Reason.REMOTE_WRITE);
            }
            try (FileInputStream input = new FileInputStream(snapshot)) {
                partialCreated = true;
                channel.put(input, partial, new SftpProgressMonitor() {
                    @Override public void init(int operation, String source, String destination, long max) { }
                    @Override public boolean count(long bytes) { return keepRunning.getAsBoolean(); }
                    @Override public void end() { }
                }, ChannelSftp.OVERWRITE);
            }
            if (!keepRunning.getAsBoolean()) {
                throw new SftpBackupException(SftpBackupException.Reason.CANCELLED);
            }
            SftpATTRS written = channel.lstat(partial);
            if (!written.isReg() || written.getSize() != snapshot.length()) {
                throw new SftpBackupException(SftpBackupException.Reason.SIZE_MISMATCH);
            }
            if (!keepRunning.getAsBoolean()) {
                throw new SftpBackupException(SftpBackupException.Reason.CANCELLED);
            }
            channel.rename(partial, complete);
            committed = true;
        } catch (IOException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        } catch (SftpException e) {
            throw new SftpBackupException(keepRunning.getAsBoolean()
                    ? SftpBackupException.Reason.REMOTE_WRITE
                    : SftpBackupException.Reason.CANCELLED, e);
        } finally {
            if (!committed && partialCreated) {
                try {
                    channel.rm(partial);
                } catch (SftpException ignored) {
                    // A leftover partial name is never treated as a completed backup.
                }
            }
        }

        boolean cleanupFailed = false;
        try {
            for (String name : namesToDelete(listNames(channel, directory), deviceId, keep, filename)) {
                channel.rm(directory + name);
            }
        } catch (SftpException e) {
            cleanupFailed = true;
        }
        return new UploadResult(filename, cleanupFailed);
    }

    static List<Version> list(ChannelSftp channel, SftpConfig config) throws SftpBackupException {
        String directory = directory(config);
        verifyDirectory(channel, directory);
        try {
            List<Version> versions = new ArrayList<>();
            for (ChannelSftp.LsEntry entry : entries(channel, directory)) {
                if (isBackupFilename(entry.getFilename()) && entry.getAttrs().isReg()) {
                    versions.add(new Version(entry.getFilename(), entry.getAttrs().getSize()));
                }
            }
            versions.sort(Comparator.comparing(Version::getName).reversed());
            return versions;
        } catch (SftpException e) {
            throw new SftpBackupException(SftpBackupException.Reason.REMOTE_DIRECTORY, e);
        }
    }

    static File download(ChannelSftp channel, SftpConfig config, String filename, File privateDirectory)
            throws SftpBackupException {
        if (!isBackupFilename(filename)) {
            throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_INVALID);
        }
        String directory = directory(config);
        verifyDirectory(channel, directory);
        String path = directory + filename;
        File temporary = null;
        boolean complete = false;
        try {
            SftpATTRS attrs = channel.lstat(path);
            if (!attrs.isReg() || attrs.getSize() <= 0) {
                throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_INVALID);
            }
            if (attrs.getSize() > MAX_RESTORE_BYTES) {
                throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_TOO_LARGE);
            }
            temporary = File.createTempFile("aegis-sftp-restore-", ".json", privateDirectory);
            long length = 0;
            try (InputStream input = channel.get(path);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    length += count;
                    if (length > MAX_RESTORE_BYTES) {
                        throw new SftpBackupException(SftpBackupException.Reason.REMOTE_FILE_TOO_LARGE);
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (length != attrs.getSize()) {
                throw new SftpBackupException(SftpBackupException.Reason.SIZE_MISMATCH);
            }
            complete = true;
            return temporary;
        } catch (SftpException e) {
            throw new SftpBackupException(SftpBackupException.Reason.REMOTE_READ, e);
        } catch (IOException e) {
            throw new SftpBackupException(SftpBackupException.Reason.LOCAL_STORAGE, e);
        } finally {
            if (!complete && temporary != null) {
                temporary.delete();
            }
        }
    }

    private static String directory(SftpConfig config) {
        String value = config.getDirectory();
        return value.endsWith("/") ? value : value + "/";
    }

    private static void verifyDirectory(ChannelSftp channel, String directory) throws SftpBackupException {
        try {
            if (!channel.lstat(directory).isDir()) {
                throw new SftpBackupException(SftpBackupException.Reason.REMOTE_DIRECTORY);
            }
        } catch (SftpException e) {
            throw new SftpBackupException(SftpBackupException.Reason.REMOTE_DIRECTORY, e);
        }
    }

    private static boolean exists(ChannelSftp channel, String path) throws SftpException {
        try {
            channel.lstat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                return false;
            }
            throw e;
        }
    }

    private static List<String> listNames(ChannelSftp channel, String directory)
            throws SftpException {
        List<String> names = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : entries(channel, directory)) {
            if (entry.getAttrs().isReg() && isBackupFilename(entry.getFilename())) {
                names.add(entry.getFilename());
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Vector<ChannelSftp.LsEntry> entries(ChannelSftp channel, String directory)
            throws SftpException {
        return channel.ls(directory);
    }
}
