package com.beemdevelopment.aegis.backup.sftp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jcraft.jsch.HostKeyRepository;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class SftpConnectionSecurityTest {
    private static byte[] hostKey(byte firstByte) {
        byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        key[0] = firstByte;
        return ByteBuffer.allocate(4 + algorithm.length + 4 + key.length)
                .putInt(algorithm.length).put(algorithm).putInt(key.length).put(key).array();
    }

    @Test
    public void firstContactRequiresExplicitTrust() {
        byte[] key = hostKey((byte) 0);
        SftpConnectionTester.PinRepository repository =
                new SftpConnectionTester.PinRepository("example.org", 2222, null);
        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("[example.org]:2222", key));
        assertNotNull(repository.getCandidate());
        assertEquals("ssh-ed25519", repository.getCandidate().getAlgorithm());
        assertEquals("SHA256:ZkAslGjFiUHdGf/WUL8rQvkib4PTvQatUV0OUQSncCA",
                repository.getCandidate().getFingerprint());
    }

    @Test
    public void pinnedKeyRejectsReplacementAndWrongHost() {
        byte[] key = hostKey((byte) 0);
        SftpConnectionTester.PinRepository repository =
                new SftpConnectionTester.PinRepository("example.org", 22, new SftpHostKey(key));
        assertEquals(HostKeyRepository.OK, repository.check("example.org", key));
        assertEquals(HostKeyRepository.CHANGED, repository.check("example.org", hostKey((byte) 1)));
        assertEquals(HostKeyRepository.CHANGED, repository.check("attacker.org", key));
    }

    @Test
    public void rejectsUnsafeRemotePathsAndPorts() {
        assertThrows(IllegalArgumentException.class,
                () -> new SftpConfig("example.org", 22, "user", "/aegis/../other", SftpAuthType.PASSWORD));
        assertThrows(IllegalArgumentException.class,
                () -> new SftpConfig("example.org", 0, "user", "/aegis", SftpAuthType.PASSWORD));
        assertThrows(IllegalArgumentException.class,
                () -> new SftpConfig("example.org/foo", 22, "user", "/aegis", SftpAuthType.PASSWORD));
        assertTrue(new SftpConfig("example.org", 22, "user", "/aegis", SftpAuthType.PRIVATE_KEY)
                .getEndpointId().contains("example.org"));
    }
}
