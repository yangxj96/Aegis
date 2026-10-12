package com.beemdevelopment.aegis.ui.fragments.preferences;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.sftp.SftpAuthType;
import com.beemdevelopment.aegis.backup.sftp.SftpConfig;
import com.beemdevelopment.aegis.backup.sftp.SftpConnectionTester;
import com.beemdevelopment.aegis.backup.sftp.SftpCredentialStore;
import com.beemdevelopment.aegis.backup.sftp.SftpCredentials;
import com.beemdevelopment.aegis.backup.sftp.SftpHostKey;
import com.beemdevelopment.aegis.backup.sftp.SftpSettingsRepository;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SftpPreferencesFragment extends PreferencesFragment {
    private static final int MAX_KEY_BYTES = 128 * 1024;
    private final Handler _mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService _executor = Executors.newSingleThreadExecutor();
    private SftpSettingsRepository _settings;
    private SftpCredentialStore _credentials;
    private Preference _pinPreference;
    private Preference _resultPreference;
    private Preference _passwordPreference;
    private Preference _privateKeyPreference;
    private int _testGeneration;
    private int _credentialStatusGeneration;

    private final ActivityResultLauncher<Intent> _keyPicker =
            registerForActivityResult(new StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        importPrivateKey(uri);
                    }
                }
            });

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        _settings = new SftpSettingsRepository(requireContext());
        _credentials = new SftpCredentialStore(requireContext());
        addPreferencesFromResource(R.xml.preferences_sftp);

        configureText(SftpSettingsRepository.KEY_HOST, InputType.TYPE_CLASS_TEXT);
        configureText(SftpSettingsRepository.KEY_PORT, InputType.TYPE_CLASS_NUMBER);
        configureText(SftpSettingsRepository.KEY_USERNAME, InputType.TYPE_CLASS_TEXT);
        configureText(SftpSettingsRepository.KEY_DIRECTORY, InputType.TYPE_CLASS_TEXT);
        ListPreference auth = requirePreference(SftpSettingsRepository.KEY_AUTH_TYPE);
        auth.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        auth.setOnPreferenceChangeListener((preference, value) -> {
            ++_testGeneration;
            preference.getSharedPreferences().edit()
                    .putString(SftpSettingsRepository.KEY_AUTH_TYPE, (String) value).apply();
            updateAuthVisibility();
            refreshCredentialStatus();
            return true;
        });

        _passwordPreference = requirePreference("sftp_password");
        _passwordPreference.setOnPreferenceClickListener(preference -> {
            promptPassword();
            return true;
        });
        _privateKeyPreference = requirePreference("sftp_private_key");
        _privateKeyPreference.setOnPreferenceClickListener(preference -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            _keyPicker.launch(intent);
            return true;
        });
        requirePreference("sftp_clear_credentials").setOnPreferenceClickListener(preference -> {
            showDialog(new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sftp_clear_credentials)
                    .setMessage(R.string.sftp_confirm_clear_credentials)
                    .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                        ++_testGeneration;
                        try {
                            _credentials.clear();
                            setResult(R.string.sftp_credentials_removed);
                            refreshCredentialStatus();
                        } catch (GeneralSecurityException | IOException e) {
                            setResult(R.string.sftp_storage_error);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null).create());
            return true;
        });

        _pinPreference = requirePreference("sftp_pin");
        requirePreference("sftp_reset_pin").setOnPreferenceClickListener(preference -> {
            showDialog(new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.sftp_reset_pin)
                    .setMessage(R.string.sftp_confirm_reset_pin)
                    .setPositiveButton(R.string.sftp_reset_pin, (dialog, which) -> {
                        ++_testGeneration;
                        _settings.clearPin();
                        updatePinSummary();
                        setResult(R.string.sftp_pin_removed);
                    })
                    .setNegativeButton(android.R.string.cancel, null).create());
            return true;
        });
        _resultPreference = requirePreference("sftp_test_result");
        requirePreference("sftp_test").setOnPreferenceClickListener(preference -> {
            runTest();
            return true;
        });
        updateAuthVisibility();
        updatePinSummary();
        refreshCredentialStatus();
    }

    @Override
    public void onDestroy() {
        ++_testGeneration;
        ++_credentialStatusGeneration;
        _executor.shutdownNow();
        super.onDestroy();
    }

    private void configureText(String key, int inputType) {
        EditTextPreference preference = requirePreference(key);
        preference.setSummaryProvider(EditTextPreference.SimpleSummaryProvider.getInstance());
        preference.setOnBindEditTextListener(edit -> edit.setInputType(inputType));
        preference.setOnPreferenceChangeListener((ignored, value) -> {
            String text = ((String) value).trim();
            try {
                switch (key) {
                    case SftpSettingsRepository.KEY_HOST:
                        new SftpConfig(text, 22, "user", "/aegis", SftpAuthType.PASSWORD);
                        break;
                    case SftpSettingsRepository.KEY_PORT:
                        new SftpConfig("localhost", Integer.parseInt(text), "user", "/aegis", SftpAuthType.PASSWORD);
                        break;
                    case SftpSettingsRepository.KEY_USERNAME:
                        new SftpConfig("localhost", 22, text, "/aegis", SftpAuthType.PASSWORD);
                        break;
                    case SftpSettingsRepository.KEY_DIRECTORY:
                        new SftpConfig("localhost", 22, "user", text, SftpAuthType.PASSWORD);
                        break;
                    default:
                        return false;
                }
            } catch (IllegalArgumentException e) {
                Toast.makeText(requireContext(), R.string.sftp_invalid_setting, Toast.LENGTH_SHORT).show();
                return false;
            }
            ++_testGeneration;
            _mainHandler.post(this::updatePinSummary);
            return true;
        });
    }

    private void updateAuthVisibility() {
        String type = requirePreference(SftpSettingsRepository.KEY_AUTH_TYPE)
                .getSharedPreferences().getString(SftpSettingsRepository.KEY_AUTH_TYPE, "PASSWORD");
        _passwordPreference.setVisible("PASSWORD".equals(type));
        _privateKeyPreference.setVisible("PRIVATE_KEY".equals(type));
    }

    private void refreshCredentialStatus() {
        int generation = ++_credentialStatusGeneration;
        _passwordPreference.setSummary(R.string.sftp_credentials_checking);
        _privateKeyPreference.setSummary(R.string.sftp_credentials_checking);
        _executor.execute(() -> {
            SftpAuthType savedType = null;
            boolean storageError = false;
            try (SftpCredentials saved = _credentials.load()) {
                if (saved != null) {
                    savedType = saved.getType();
                }
            } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
                storageError = true;
            }
            SftpAuthType finalSavedType = savedType;
            boolean finalStorageError = storageError;
            _mainHandler.post(() -> {
                if (!isAdded() || generation != _credentialStatusGeneration) {
                    return;
                }
                if (finalStorageError) {
                    _passwordPreference.setSummary(R.string.sftp_credentials_unavailable);
                    _privateKeyPreference.setSummary(R.string.sftp_credentials_unavailable);
                } else {
                    updateCredentialSummaries(finalSavedType);
                }
            });
        });
    }

    private void updateCredentialSummaries(SftpAuthType savedType) {
        _passwordPreference.setSummary(savedType == SftpAuthType.PASSWORD
                ? R.string.sftp_credentials_present : R.string.sftp_credentials_absent);
        _privateKeyPreference.setSummary(savedType == SftpAuthType.PRIVATE_KEY
                ? R.string.sftp_credentials_present : R.string.sftp_credentials_absent);
    }

    private void showCredentialSaveError(Preference preference, int message) {
        preference.setSummary(message);
        setResult(message);
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
    }

    private void promptPassword() {
        EditText input = passwordInput();
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_set_password)
                .setView(input)
                .setPositiveButton(R.string.save, (d, which) -> {
                    byte[] password = input.getText().toString().getBytes(StandardCharsets.UTF_8);
                    input.getText().clear();
                    try {
                        _credentials.savePassword(password);
                        try (SftpCredentials saved = _credentials.load()) {
                            if (saved == null || saved.getType() != SftpAuthType.PASSWORD) {
                                throw new IOException("SFTP password was not persisted");
                            }
                        }
                        ++_testGeneration;
                        ++_credentialStatusGeneration;
                        updateCredentialSummaries(SftpAuthType.PASSWORD);
                        setResult(R.string.sftp_credentials_saved);
                        Toast.makeText(requireContext(), R.string.sftp_credentials_saved, Toast.LENGTH_SHORT).show();
                    } catch (IllegalArgumentException e) {
                        showCredentialSaveError(_passwordPreference, R.string.sftp_invalid_credential);
                    } catch (GeneralSecurityException | IOException e) {
                        showCredentialSaveError(_passwordPreference, R.string.sftp_storage_error);
                    } finally {
                        Arrays.fill(password, (byte) 0);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(d -> input.getText().clear()).create();
        showDialog(dialog);
    }

    private void importPrivateKey(Uri uri) {
        android.content.Context context = requireContext().getApplicationContext();
        _executor.execute(() -> {
            byte[] key = null;
            byte[] buffer = new byte[MAX_KEY_BYTES + 1];
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                if (stream == null) {
                    throw new IOException("No private key stream");
                }
                int length = 0;
                int count;
                while (length < buffer.length && (count = stream.read(buffer, length, buffer.length - length)) != -1) {
                    length += count;
                }
                if (length > MAX_KEY_BYTES) {
                    postResult(R.string.sftp_key_too_large);
                    return;
                }
                key = Arrays.copyOf(buffer, length);
                byte[] importedKey = key;
                _mainHandler.post(() -> {
                    if (isAdded()) {
                        promptPassphrase(importedKey);
                    } else {
                        Arrays.fill(importedKey, (byte) 0);
                    }
                });
            } catch (IOException | RuntimeException e) {
                if (key != null) {
                    Arrays.fill(key, (byte) 0);
                }
                postResult(R.string.sftp_invalid_private_key);
            } finally {
                Arrays.fill(buffer, (byte) 0);
            }
        });
    }

    private void promptPassphrase(byte[] key) {
        EditText input = passwordInput();
        AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_key_passphrase)
                .setView(input)
                .setPositiveButton(R.string.save, (d, which) -> {
                    byte[] phrase = input.getText().toString().getBytes(StandardCharsets.UTF_8);
                    input.getText().clear();
                    try {
                        _credentials.savePrivateKey(key, phrase.length == 0 ? null : phrase);
                        try (SftpCredentials saved = _credentials.load()) {
                            if (saved == null || saved.getType() != SftpAuthType.PRIVATE_KEY) {
                                throw new IOException("SFTP private key was not persisted");
                            }
                        }
                        ++_testGeneration;
                        ++_credentialStatusGeneration;
                        updateCredentialSummaries(SftpAuthType.PRIVATE_KEY);
                        setResult(R.string.sftp_credentials_saved);
                        Toast.makeText(requireContext(), R.string.sftp_credentials_saved, Toast.LENGTH_SHORT).show();
                    } catch (IllegalArgumentException e) {
                        showCredentialSaveError(_privateKeyPreference, R.string.sftp_invalid_credential);
                    } catch (GeneralSecurityException | IOException e) {
                        showCredentialSaveError(_privateKeyPreference, R.string.sftp_storage_error);
                    } finally {
                        Arrays.fill(key, (byte) 0);
                        Arrays.fill(phrase, (byte) 0);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, which) -> Arrays.fill(key, (byte) 0))
                .setOnDismissListener(d -> {
                    Arrays.fill(key, (byte) 0);
                    input.getText().clear();
                })
                .create();
        showDialog(dialog);
    }

    private EditText passwordInput() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        return input;
    }

    private void runTest() {
        final SftpConfig config;
        try {
            config = _settings.getConfig();
        } catch (IllegalArgumentException e) {
            setResult(R.string.sftp_not_configured);
            return;
        }
        int generation = ++_testGeneration;
        setResult(R.string.sftp_testing);
        _executor.execute(() -> {
            SftpConnectionTester.Result result;
            try (SftpCredentials credentials = _credentials.load()) {
                if (credentials == null || credentials.getType() != config.getAuthType()) {
                    postResult(generation, config.getAuthType() == SftpAuthType.PASSWORD
                            ? R.string.sftp_no_password_credentials : R.string.sftp_no_key_credentials);
                    _mainHandler.post(() -> {
                        if (isAdded() && generation == _testGeneration) {
                            refreshCredentialStatus();
                        }
                    });
                    return;
                }
                SftpHostKey pin = _settings.getPinnedKey(config);
                result = SftpConnectionTester.test(config, credentials, pin);
            } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
                postResult(generation, R.string.sftp_storage_error);
                return;
            }
            SftpConnectionTester.Result finalResult = result;
            _mainHandler.post(() -> {
                if (isAdded() && generation == _testGeneration) {
                    showTestResult(config, finalResult);
                }
            });
        });
    }

    private void showTestResult(SftpConfig config, SftpConnectionTester.Result result) {
        switch (result.getStatus()) {
            case CONNECTED:
                setResult(R.string.sftp_connected);
                break;
            case HOST_KEY_UNKNOWN:
                SftpHostKey candidate = result.getCandidate();
                setResult(getString(R.string.sftp_host_key_unknown,
                        candidate.getAlgorithm(), candidate.getFingerprint()));
                showDialog(new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.sftp_pinned_key)
                        .setMessage(getString(R.string.sftp_host_key_unknown,
                                candidate.getAlgorithm(), candidate.getFingerprint()))
                        .setPositiveButton(R.string.sftp_trust_key, (dialog, which) -> {
                            try {
                                _settings.pin(config, candidate);
                                updatePinSummary();
                                runTest();
                            } catch (IOException e) {
                                setResult(R.string.sftp_storage_error);
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null).create());
                break;
            case HOST_KEY_CHANGED:
                SftpHostKey changed = result.getCandidate();
                setResult(getString(R.string.sftp_host_key_changed,
                        changed.getAlgorithm(), changed.getFingerprint()));
                break;
            case INVALID_PRIVATE_KEY:
                setResult(R.string.sftp_invalid_private_key);
                break;
            case AUTHENTICATION_FAILED:
                setResult(R.string.sftp_auth_failed);
                break;
            case TIMEOUT:
                setResult(R.string.sftp_timeout);
                break;
            case SFTP_UNAVAILABLE:
                setResult(R.string.sftp_subsystem_unavailable);
                break;
            case NETWORK_ERROR:
                setResult(R.string.sftp_network_error);
                break;
        }
    }

    private void updatePinSummary() {
        try {
            SftpHostKey pin = _settings.getPinnedKey(_settings.getConfig());
            _pinPreference.setSummary(pin == null ? getString(R.string.sftp_no_pin)
                    : pin.getAlgorithm() + " " + pin.getFingerprint());
        } catch (IOException | IllegalArgumentException e) {
            _pinPreference.setSummary(R.string.sftp_no_pin);
        }
    }

    private void setResult(int resource) {
        setResult(getString(resource));
    }

    private void setResult(String message) {
        _resultPreference.setSummary(message);
    }

    private void postResult(int resource) {
        _mainHandler.post(() -> {
            if (isAdded()) {
                setResult(resource);
            }
        });
    }

    private void postResult(int generation, int resource) {
        _mainHandler.post(() -> {
            if (isAdded() && generation == _testGeneration) {
                setResult(resource);
            }
        });
    }

    private void showDialog(AlertDialog dialog) {
        dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        dialog.show();
    }
}
