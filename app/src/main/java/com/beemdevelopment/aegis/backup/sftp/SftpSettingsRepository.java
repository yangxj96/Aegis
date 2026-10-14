package com.beemdevelopment.aegis.backup.sftp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import androidx.preference.PreferenceManager;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

public final class SftpSettingsRepository {
    public static final String KEY_HOST = "pref_sftp_host";
    public static final String KEY_PORT = "pref_sftp_port";
    public static final String KEY_USERNAME = "pref_sftp_username";
    public static final String KEY_DIRECTORY = "pref_sftp_directory";
    public static final String KEY_AUTH_TYPE = "pref_sftp_auth_type";
    public static final String KEY_RETENTION = "pref_sftp_retention";

    private static final int PIN_FORMAT_VERSION = 1;
    private final SharedPreferences _preferences;
    private final AtomicFile _pinFile;
    private final AtomicFile _deviceFile;

    public SftpSettingsRepository(Context context) {
        _preferences = PreferenceManager.getDefaultSharedPreferences(context);
        _pinFile = new AtomicFile(new File(context.getNoBackupFilesDir(), "sftp_host_key_pin"));
        _deviceFile = new AtomicFile(new File(context.getNoBackupFilesDir(), "sftp_device_id"));
    }

    public SftpConfig getConfig() {
        String portText = _preferences.getString(KEY_PORT, "22");
        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid SFTP port", e);
        }
        SftpAuthType authType = SftpAuthType.valueOf(
                _preferences.getString(KEY_AUTH_TYPE, SftpAuthType.PASSWORD.name()));
        return new SftpConfig(
                _preferences.getString(KEY_HOST, ""), port,
                _preferences.getString(KEY_USERNAME, ""),
                _preferences.getString(KEY_DIRECTORY, "/aegis"), authType);
    }

    public SftpHostKey getPinnedKey(SftpConfig config) throws IOException {
        try (InputStream stream = _pinFile.openRead(); DataInputStream input = new DataInputStream(stream)) {
            if (input.readInt() != PIN_FORMAT_VERSION) {
                throw new IOException("Unsupported SFTP host key pin format");
            }
            int endpointLength = input.readInt();
            if (endpointLength < 1 || endpointLength > 512) {
                throw new IOException("Invalid SFTP host key pin");
            }
            byte[] endpoint = new byte[endpointLength];
            input.readFully(endpoint);
            int keyLength = input.readInt();
            if (keyLength < 8 || keyLength > 65536) {
                throw new IOException("Invalid SFTP host key pin");
            }
            byte[] key = new byte[keyLength];
            input.readFully(key);
            if (input.read() != -1) {
                throw new IOException("Invalid SFTP host key pin");
            }
            if (!Arrays.equals(endpoint, config.getEndpointId().getBytes(StandardCharsets.UTF_8))) {
                return null;
            }
            return new SftpHostKey(key);
        } catch (FileNotFoundException e) {
            return null;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid SFTP host key pin", e);
        }
    }

    public void pin(SftpConfig config, SftpHostKey key) throws IOException {
        byte[] endpoint = config.getEndpointId().getBytes(StandardCharsets.UTF_8);
        byte[] encoded = key.getEncoded();
        if (endpoint.length > 512) {
            throw new IOException("SFTP host is too long");
        }
        FileOutputStream stream = null;
        try {
            stream = _pinFile.startWrite();
            DataOutputStream output = new DataOutputStream(stream);
            output.writeInt(PIN_FORMAT_VERSION);
            output.writeInt(endpoint.length);
            output.write(endpoint);
            output.writeInt(encoded.length);
            output.write(encoded);
            output.flush();
            _pinFile.finishWrite(stream);
        } catch (IOException | RuntimeException e) {
            if (stream != null) {
                _pinFile.failWrite(stream);
            }
            throw e;
        }
    }

    public void clearPin() {
        _pinFile.delete();
    }

    public int getRetentionCount() {
        int count;
        try {
            count = Integer.parseInt(_preferences.getString(KEY_RETENTION, "10"));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid SFTP retention count", e);
        }
        if (count < 1 || count > 100) {
            throw new IllegalArgumentException("Invalid SFTP retention count");
        }
        return count;
    }

    public synchronized String getDeviceId() throws IOException {
        try {
            byte[] bytes = _deviceFile.readFully();
            String id = new String(bytes, StandardCharsets.US_ASCII);
            if (!id.matches("[0-9a-f]{32}")) {
                throw new IOException("Invalid SFTP device ID");
            }
            return id;
        } catch (FileNotFoundException ignored) {
            String id = UUID.randomUUID().toString().replace("-", "");
            FileOutputStream stream = null;
            try {
                stream = _deviceFile.startWrite();
                stream.write(id.getBytes(StandardCharsets.US_ASCII));
                _deviceFile.finishWrite(stream);
            } catch (IOException e) {
                if (stream != null) {
                    _deviceFile.failWrite(stream);
                }
                throw e;
            }
            return id;
        }
    }
}
