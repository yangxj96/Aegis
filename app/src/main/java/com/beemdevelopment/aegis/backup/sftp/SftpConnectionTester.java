package com.beemdevelopment.aegis.backup.sftp;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.stream.Collectors;

public final class SftpConnectionTester {
    public enum Status {
        CONNECTED,
        HOST_KEY_UNKNOWN,
        HOST_KEY_CHANGED,
        INVALID_PRIVATE_KEY,
        AUTHENTICATION_FAILED,
        TIMEOUT,
        NETWORK_ERROR,
        SFTP_UNAVAILABLE
    }

    public static final class Result {
        private final Status _status;
        private final SftpHostKey _candidate;

        private Result(Status status, SftpHostKey candidate) {
            _status = status;
            _candidate = candidate;
        }

        public Status getStatus() {
            return _status;
        }

        public SftpHostKey getCandidate() {
            return _candidate;
        }
    }

    private static final int TIMEOUT_MS = 10000;

    private SftpConnectionTester() {
    }

    public static Result test(SftpConfig config, SftpCredentials credentials, SftpHostKey pinned) {
        try (Connection ignored = open(config, credentials, pinned)) {
            return new Result(Status.CONNECTED, null);
        } catch (ConnectionFailure e) {
            return new Result(e.status, e.candidate);
        }
    }

    static Connection open(SftpConfig config, SftpCredentials credentials, SftpHostKey pinned)
            throws ConnectionFailure {
        if (credentials.getType() != config.getAuthType()) {
            throw new ConnectionFailure(Status.AUTHENTICATION_FAILED, null);
        }
        JSch jsch = new JSch();
        PinRepository repository = new PinRepository(config.getHost(), config.getPort(), pinned);
        jsch.setHostKeyRepository(repository);
        Session session = null;
        Channel channel = null;
        boolean handedOff = false;
        try {
            if (config.getAuthType() == SftpAuthType.PRIVATE_KEY) {
                try {
                    jsch.addIdentity("aegis-sftp", credentials.getSecret(), null, credentials.getPassphrase());
                } catch (JSchException e) {
                    throw new ConnectionFailure(Status.INVALID_PRIVATE_KEY, null);
                }
            }
            session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications",
                    config.getAuthType() == SftpAuthType.PASSWORD ? "password" : "publickey");
            // The project's Bouncy Castle version has no ML-KEM implementation.
            session.setConfig("kex", Arrays.stream(JSch.getConfig("kex").split(","))
                    .filter(name -> !name.toLowerCase(java.util.Locale.ROOT).contains("mlkem"))
                    .collect(Collectors.joining(",")));
            session.setTimeout(TIMEOUT_MS);
            if (config.getAuthType() == SftpAuthType.PASSWORD) {
                session.setPassword(credentials.getSecret());
            }
            try {
                session.connect(TIMEOUT_MS);
            } catch (JSchException e) {
                if (repository.getCandidate() != null) {
                    throw new ConnectionFailure(pinned == null ? Status.HOST_KEY_UNKNOWN : Status.HOST_KEY_CHANGED,
                            repository.getCandidate());
                }
                throw new ConnectionFailure(classify(e), null);
            }
            channel = session.openChannel("sftp");
            channel.connect(TIMEOUT_MS);
            if (!(channel instanceof ChannelSftp)) {
                throw new ConnectionFailure(Status.SFTP_UNAVAILABLE, null);
            }
            Connection connection = new Connection(jsch, session, (ChannelSftp) channel);
            handedOff = true;
            return connection;
        } catch (JSchException e) {
            throw new ConnectionFailure(session != null && session.isConnected()
                    ? Status.SFTP_UNAVAILABLE : classify(e), null);
        } finally {
            if (!handedOff) {
                if (channel != null) {
                    channel.disconnect();
                }
                if (session != null) {
                    session.disconnect();
                }
                try {
                    jsch.removeAllIdentity();
                } catch (JSchException ignored) {
                    // The JSch instance is discarded after this operation.
                }
            }
        }
    }

    static final class Connection implements AutoCloseable {
        private final JSch jsch;
        private final Session session;
        private final ChannelSftp channel;

        Connection(JSch jsch, Session session, ChannelSftp channel) {
            this.jsch = jsch;
            this.session = session;
            this.channel = channel;
        }

        ChannelSftp getChannel() {
            return channel;
        }

        @Override
        public void close() {
            channel.disconnect();
            session.disconnect();
            try {
                jsch.removeAllIdentity();
            } catch (JSchException ignored) {
                // All SSH objects are discarded after this operation.
            }
        }
    }

    static final class ConnectionFailure extends Exception {
        final Status status;
        final SftpHostKey candidate;

        ConnectionFailure(Status status, SftpHostKey candidate) {
            this.status = status;
            this.candidate = candidate;
        }
    }

    private static Status classify(JSchException error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return Status.TIMEOUT;
            }
            cause = cause.getCause();
        }
        String message = error.getMessage();
        if (message != null) {
            if (message.contains("Auth fail") || message.contains("USERAUTH fail")) {
                return Status.AUTHENTICATION_FAILED;
            }
            if (message.contains("timeout") || message.contains("timed out")) {
                return Status.TIMEOUT;
            }
        }
        return Status.NETWORK_ERROR;
    }

    static final class PinRepository implements HostKeyRepository {
        private final String _host;
        private final int _port;
        private final SftpHostKey _pinned;
        private SftpHostKey _candidate;

        PinRepository(String host, int port, SftpHostKey pinned) {
            _host = host;
            _port = port;
            _pinned = pinned;
        }

        @Override
        public int check(String host, byte[] key) {
            if (!matchesHost(host)) {
                return CHANGED;
            }
            try {
                if (_pinned != null && _pinned.matches(key)) {
                    return OK;
                }
                _candidate = new SftpHostKey(key);
                return _pinned == null ? NOT_INCLUDED : CHANGED;
            } catch (IllegalArgumentException e) {
                return CHANGED;
            }
        }

        private boolean matchesHost(String host) {
            return host.equals(_host) || host.equals("[" + _host + "]:" + _port);
        }

        SftpHostKey getCandidate() {
            return _candidate;
        }

        @Override
        public void add(HostKey hostkey, UserInfo ui) {
            // Unknown keys are only saved after the user verifies their fingerprint.
        }

        @Override
        public void remove(String host, String type) {
        }

        @Override
        public void remove(String host, String type, byte[] key) {
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return "Aegis SFTP pinned host key";
        }

        @Override
        public HostKey[] getHostKey() {
            return new HostKey[0];
        }

        @Override
        public HostKey[] getHostKey(String host, String type) {
            return new HostKey[0];
        }
    }
}
