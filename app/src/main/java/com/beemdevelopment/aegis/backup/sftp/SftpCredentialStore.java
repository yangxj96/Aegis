package com.beemdevelopment.aegis.backup.sftp;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SftpCredentialStore {
    private static final String KEY_ALIAS = "aegis_sftp_credentials_v1";
    private static final int VERSION = 1;
    private static final int MAX_SECRET = 128 * 1024;
    private static final int MAX_PASSPHRASE = 1024;
    private final AtomicFile _file;

    public SftpCredentialStore(Context context) {
        _file = new AtomicFile(new File(context.getNoBackupFilesDir(), "sftp_credentials"));
    }

    public void savePassword(byte[] password) throws GeneralSecurityException, IOException {
        save(SftpAuthType.PASSWORD, password, null);
    }

    public void savePrivateKey(byte[] key, byte[] passphrase) throws GeneralSecurityException, IOException {
        save(SftpAuthType.PRIVATE_KEY, key, passphrase);
    }

    private void save(SftpAuthType type, byte[] secret, byte[] passphrase)
            throws GeneralSecurityException, IOException {
        if (secret == null || secret.length == 0 || secret.length > MAX_SECRET
                || (type == SftpAuthType.PASSWORD && secret.length > MAX_PASSPHRASE)
                || (passphrase != null && passphrase.length > MAX_PASSPHRASE)) {
            throw new IllegalArgumentException("Invalid SFTP credential length");
        }
        byte[] plain;
        try (WipingByteArrayOutputStream bytes = new WipingByteArrayOutputStream();
             DataOutputStream data = new DataOutputStream(bytes)) {
            data.writeInt(VERSION);
            data.writeUTF(type.name());
            data.writeInt(secret.length);
            data.write(secret);
            data.writeInt(passphrase == null ? 0 : passphrase.length);
            if (passphrase != null) {
                data.write(passphrase);
            }
            plain = bytes.toByteArray();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != 12) {
                throw new GeneralSecurityException("Invalid SFTP credential IV");
            }
            byte[] encrypted = cipher.doFinal(plain);
            FileOutputStream stream = null;
            try {
                stream = _file.startWrite();
                DataOutputStream output = new DataOutputStream(stream);
                output.writeInt(VERSION);
                output.write(iv);
                output.writeInt(encrypted.length);
                output.write(encrypted);
                output.flush();
                _file.finishWrite(stream);
            } catch (IOException | RuntimeException e) {
                if (stream != null) {
                    _file.failWrite(stream);
                }
                throw e;
            } finally {
                Arrays.fill(encrypted, (byte) 0);
            }
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    public SftpCredentials load() throws GeneralSecurityException, IOException {
        byte[] encrypted;
        byte[] iv = new byte[12];
        try (InputStream stream = _file.openRead(); DataInputStream input = new DataInputStream(stream)) {
            if (input.readInt() != VERSION) {
                throw new IOException("Unsupported SFTP credential format");
            }
            input.readFully(iv);
            int length = input.readInt();
            if (length < 16 || length > MAX_SECRET + MAX_PASSPHRASE + 128) {
                throw new IOException("Invalid SFTP credential file");
            }
            encrypted = new byte[length];
            input.readFully(encrypted);
            if (input.read() != -1) {
                throw new IOException("Invalid SFTP credential file");
            }
        } catch (FileNotFoundException e) {
            return null;
        }

        byte[] plain = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), new GCMParameterSpec(128, iv));
            plain = cipher.doFinal(encrypted);
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(plain))) {
                if (input.readInt() != VERSION) {
                    throw new IOException("Unsupported SFTP credential format");
                }
                SftpAuthType type = SftpAuthType.valueOf(input.readUTF());
                int secretLength = input.readInt();
                if (secretLength < 1 || secretLength > MAX_SECRET
                        || (type == SftpAuthType.PASSWORD && secretLength > MAX_PASSPHRASE)) {
                    throw new IOException("Invalid SFTP credential file");
                }
                byte[] secret = new byte[secretLength];
                input.readFully(secret);
                int passphraseLength = input.readInt();
                if (passphraseLength < 0 || passphraseLength > MAX_PASSPHRASE) {
                    Arrays.fill(secret, (byte) 0);
                    throw new IOException("Invalid SFTP credential file");
                }
                byte[] passphrase = passphraseLength == 0 ? null : new byte[passphraseLength];
                try {
                    if (passphrase != null) {
                        input.readFully(passphrase);
                    }
                    if (input.read() != -1) {
                        throw new IOException("Invalid SFTP credential file");
                    }
                    return new SftpCredentials(type, secret, passphrase);
                } catch (IOException e) {
                    Arrays.fill(secret, (byte) 0);
                    if (passphrase != null) {
                        Arrays.fill(passphrase, (byte) 0);
                    }
                    throw e;
                }
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid SFTP credential file", e);
        } finally {
            Arrays.fill(encrypted, (byte) 0);
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }

    public void clear() throws GeneralSecurityException, IOException {
        _file.delete();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        store.deleteEntry(KEY_ALIAS);
    }

    private SecretKey getExistingKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key == null) {
            throw new GeneralSecurityException("SFTP credential key unavailable");
        }
        return key;
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(KEY_ALIAS, null);
        if (key != null) {
            return key;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private static final class WipingByteArrayOutputStream extends ByteArrayOutputStream {
        @Override
        public void close() throws IOException {
            Arrays.fill(buf, (byte) 0);
            super.close();
        }
    }
}
