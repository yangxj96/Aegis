package com.beemdevelopment.aegis.backup.sftp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;

public final class SftpHostKey {
    private final byte[] _encoded;
    private final String _algorithm;
    private final String _fingerprint;

    public SftpHostKey(byte[] encoded) {
        if (encoded == null || encoded.length < 8 || encoded.length > 65536) {
            throw new IllegalArgumentException("Invalid SSH host key");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encoded);
        int nameLength = buffer.getInt();
        if (nameLength < 1 || nameLength > 128 || nameLength > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid SSH host key algorithm");
        }
        byte[] name = new byte[nameLength];
        buffer.get(name);
        _algorithm = new String(name, StandardCharsets.US_ASCII);
        if (!_algorithm.matches("[A-Za-z0-9@._+-]+")) {
            throw new IllegalArgumentException("Invalid SSH host key algorithm");
        }
        _encoded = encoded.clone();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(_encoded);
            _fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] getEncoded() {
        return _encoded.clone();
    }

    public String getAlgorithm() {
        return _algorithm;
    }

    public String getFingerprint() {
        return _fingerprint;
    }

    public boolean matches(byte[] encoded) {
        return encoded != null && MessageDigest.isEqual(_encoded, encoded);
    }
}
