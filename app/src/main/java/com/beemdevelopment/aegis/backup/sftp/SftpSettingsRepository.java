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

public final class SftpSettingsRepository {
    public static final String KEY_HOST = "pref_sftp_host";
    public static final String KEY_PORT = "pref_sftp_port";
    public static final String KEY_USERNAME = "pref_sftp_username";
    public static final String KEY_DIRECTORY = "pref_sftp_directory";
    public static final String KEY_AUTH_TYPE = "pref_sftp_auth_type";

    private static final int PIN_FORMAT_VERSION = 1;
    private final SharedPreferences _preferences;
    private final AtomicFile _pinFile;

    public SftpSettingsRepository(Context context) {
        _preferences = PreferenceManager.getDefaultSharedPreferences(context);
        _pinFile = new AtomicFile(new File(context.getNoBackupFilesDir(), "sftp_host_key_pin"));
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
}
