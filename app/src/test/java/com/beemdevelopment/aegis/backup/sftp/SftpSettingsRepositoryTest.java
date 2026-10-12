package com.beemdevelopment.aegis.backup.sftp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@RunWith(RobolectricTestRunner.class)
public class SftpSettingsRepositoryTest {
    @Test
    public void pinIsScopedToEndpointAndCanBeRemoved() throws IOException {
        Context context = ApplicationProvider.getApplicationContext();
        SftpSettingsRepository repository = new SftpSettingsRepository(context);
        repository.clearPin();
        SftpConfig first = new SftpConfig("example.org", 22, "user", "/aegis", SftpAuthType.PASSWORD);
        SftpConfig other = new SftpConfig("example.org", 2222, "user", "/aegis", SftpAuthType.PASSWORD);
        byte[] name = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
        byte[] publicKey = ByteBuffer.allocate(4 + name.length + 4 + 32)
                .putInt(name.length).put(name).putInt(32).put(new byte[32]).array();

        assertNull(repository.getPinnedKey(first));
        repository.pin(first, new SftpHostKey(publicKey));
        assertArrayEquals(publicKey, new SftpSettingsRepository(context).getPinnedKey(first).getEncoded());
        assertNull(repository.getPinnedKey(other));
        repository.clearPin();
        assertNull(repository.getPinnedKey(first));
    }
}
