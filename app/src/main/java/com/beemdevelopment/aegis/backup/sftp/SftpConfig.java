package com.beemdevelopment.aegis.backup.sftp;

import java.util.Objects;

public final class SftpConfig {
    private final String _host;
    private final int _port;
    private final String _username;
    private final String _directory;
    private final SftpAuthType _authType;

    public SftpConfig(String host, int port, String username, String directory, SftpAuthType authType) {
        _host = Objects.requireNonNull(host).trim();
        _username = Objects.requireNonNull(username).trim();
        _directory = Objects.requireNonNull(directory).trim();
        _authType = Objects.requireNonNull(authType);
        _port = port;

        if (_host.isEmpty() || _host.matches(".*[\\s/\\\\].*") || _host.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid SFTP host");
        }
        if (_port < 1 || _port > 65535) {
            throw new IllegalArgumentException("Invalid SFTP port");
        }
        if (_username.isEmpty() || _username.indexOf('\r') >= 0
                || _username.indexOf('\n') >= 0 || _username.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid SFTP username");
        }
        if (!_directory.startsWith("/") || _directory.contains("\\") || _directory.contains("\0")) {
            throw new IllegalArgumentException("SFTP directory must be an absolute path");
        }
        for (String segment : _directory.split("/")) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new IllegalArgumentException("SFTP directory cannot contain dot segments");
            }
        }
    }

    public String getHost() {
        return _host;
    }

    public int getPort() {
        return _port;
    }

    public String getUsername() {
        return _username;
    }

    public String getDirectory() {
        return _directory;
    }

    public SftpAuthType getAuthType() {
        return _authType;
    }

    public String getEndpointId() {
        return _host.toLowerCase(java.util.Locale.ROOT) + ":" + _port;
    }
}
