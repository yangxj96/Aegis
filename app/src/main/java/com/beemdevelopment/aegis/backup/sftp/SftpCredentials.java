package com.beemdevelopment.aegis.backup.sftp;

import java.util.Arrays;

public final class SftpCredentials implements AutoCloseable {
    private final SftpAuthType _type;
    private final byte[] _secret;
    private final byte[] _passphrase;

    SftpCredentials(SftpAuthType type, byte[] secret, byte[] passphrase) {
        _type = type;
        _secret = secret;
        _passphrase = passphrase;
    }

    public SftpAuthType getType() {
        return _type;
    }

    byte[] getSecret() {
        return _secret;
    }

    byte[] getPassphrase() {
        return _passphrase;
    }

    @Override
    public void close() {
        Arrays.fill(_secret, (byte) 0);
        if (_passphrase != null) {
            Arrays.fill(_passphrase, (byte) 0);
        }
    }
}
