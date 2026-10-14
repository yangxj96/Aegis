package com.beemdevelopment.aegis.ui.fragments.preferences;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.work.WorkManager;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.sftp.SftpBackupException;
import com.beemdevelopment.aegis.backup.sftp.SftpBackupManager;
import com.beemdevelopment.aegis.backup.sftp.SftpBackupStorage;
import com.beemdevelopment.aegis.backup.sftp.SftpAutoBackupScheduler;
import com.beemdevelopment.aegis.backup.sftp.SftpAutoBackupState;
import com.beemdevelopment.aegis.backup.sftp.SftpAuthType;
import com.beemdevelopment.aegis.backup.sftp.SftpConfig;
import com.beemdevelopment.aegis.backup.sftp.SftpConnectionTester;
import com.beemdevelopment.aegis.backup.sftp.SftpCredentialStore;
import com.beemdevelopment.aegis.backup.sftp.SftpCredentials;
import com.beemdevelopment.aegis.backup.sftp.SftpHostKey;
import com.beemdevelopment.aegis.backup.sftp.SftpSettingsRepository;
import com.beemdevelopment.aegis.backup.sftp.SftpRestoreManager;
import com.beemdevelopment.aegis.ui.SftpRestoreActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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
    private Preference _uploadPreference;
    private Preference _uploadStatusPreference;
    private Preference _autoStatusPreference;
    private Preference _versionsPreference;
    private Preference _rollbackPreference;
    private File _downloadedRestore;
    private boolean _restoreBusy;
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
            notifyAutoSettingsChanged();
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
                            notifyAutoSettingsChanged();
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
                        notifyAutoSettingsChanged();
                    })
                    .setNegativeButton(android.R.string.cancel, null).create());
            return true;
        });
        _resultPreference = requirePreference("sftp_test_result");
        requirePreference("sftp_test").setOnPreferenceClickListener(preference -> {
            runTest();
            return true;
        });
        ListPreference retention = requirePreference(SftpSettingsRepository.KEY_RETENTION);
        retention.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        retention.setOnPreferenceChangeListener((preference, value) -> {
            _mainHandler.post(this::notifyAutoSettingsChanged);
            return true;
        });
        SwitchPreferenceCompat autoUpload = requirePreference(SftpAutoBackupState.KEY_ENABLED);
        autoUpload.setOnPreferenceChangeListener((preference, value) -> {
            SftpAutoBackupScheduler.setEnabled(requireContext().getApplicationContext(), (boolean) value);
            _mainHandler.post(this::updateAutoStatus);
            return true;
        });
        _autoStatusPreference = requirePreference("sftp_auto_status");
        _autoStatusPreference.setOnPreferenceClickListener(preference -> {
            showAutoDetails();
            return true;
        });
        _uploadPreference = requirePreference("sftp_upload");
        _uploadPreference.setOnPreferenceClickListener(preference -> {
            startUpload();
            return true;
        });
        _uploadStatusPreference = requirePreference("sftp_upload_status");
        _uploadStatusPreference.setOnPreferenceClickListener(preference -> {
            showUploadDetails();
            return true;
        });
        _versionsPreference = requirePreference("sftp_versions");
        _versionsPreference.setSummary(R.string.sftp_versions_read_only);
        _versionsPreference.setOnPreferenceClickListener(preference -> {
            loadVersions();
            return true;
        });
        _rollbackPreference = requirePreference("sftp_restore_rollback");
        _rollbackPreference.setOnPreferenceClickListener(preference -> {
            if (!_restoreBusy) {
                promptRestorePassword(null, getString(R.string.sftp_restore_rollback), true);
            }
            return true;
        });
        boolean introductory = requireActivity() instanceof SftpRestoreActivity;
        retention.setVisible(!introductory);
        autoUpload.setVisible(!introductory);
        _autoStatusPreference.setVisible(!introductory);
        _uploadPreference.setVisible(!introductory);
        _uploadStatusPreference.setVisible(!introductory);
        updateRollbackVisibility();
        updateAuthVisibility();
        updatePinSummary();
        refreshCredentialStatus();
        updateUploadStatus(SftpBackupManager.getStatus(requireContext()));
        updateAutoStatus();
        updateRollbackVisibility();
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!(requireActivity() instanceof SftpRestoreActivity)) {
            WorkManager.getInstance(requireContext())
                    .getWorkInfosForUniqueWorkLiveData(SftpAutoBackupScheduler.WORK_NAME)
                    .observe(getViewLifecycleOwner(), ignored -> updateAutoStatus());
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUploadStatus(SftpBackupManager.getStatus(requireContext()));
        updateAutoStatus();
    }

    @Override
    public void onDestroy() {
        ++_testGeneration;
        ++_credentialStatusGeneration;
        _executor.shutdownNow();
        if (_downloadedRestore != null) {
            _downloadedRestore.delete();
            _downloadedRestore = null;
        }
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
            _mainHandler.post(this::notifyAutoSettingsChanged);
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
                        notifyAutoSettingsChanged();
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
                        notifyAutoSettingsChanged();
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
                                notifyAutoSettingsChanged();
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

    private void startUpload() {
        if (!_vaultManager.isVaultLoaded()) {
            Toast.makeText(requireContext(), R.string.sftp_error_local_storage, Toast.LENGTH_LONG).show();
            return;
        }
        boolean started = SftpBackupManager.startUpload(requireContext(), status -> {
            if (isAdded()) {
                updateUploadStatus(SftpBackupManager.getStatus(requireContext()));
            }
        });
        if (started) {
            updateUploadStatus(SftpBackupManager.getStatus(requireContext()));
        } else {
            Toast.makeText(requireContext(), R.string.sftp_upload_busy, Toast.LENGTH_SHORT).show();
        }
    }

    private void notifyAutoSettingsChanged() {
        if (!isAdded() || requireActivity() instanceof SftpRestoreActivity) {
            return;
        }
        SftpAutoBackupScheduler.onSettingsChanged(requireContext().getApplicationContext());
        updateAutoStatus();
    }

    private void updateAutoStatus() {
        if (_autoStatusPreference == null || !isAdded()) {
            return;
        }
        SftpAutoBackupState.Status status = SftpAutoBackupState.getStatus(requireContext());
        String time = status.getTime() > 0
                ? DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(new Date(status.getTime())) : "";
        switch (status.getPhase()) {
            case DISABLED:
                _autoStatusPreference.setSummary(R.string.sftp_auto_disabled);
                break;
            case NEVER:
                _autoStatusPreference.setSummary(R.string.sftp_auto_never);
                break;
            case PENDING:
                _autoStatusPreference.setSummary(R.string.sftp_auto_pending);
                break;
            case RUNNING:
                _autoStatusPreference.setSummary(R.string.sftp_auto_running);
                break;
            case RETRYING:
                _autoStatusPreference.setSummary(R.string.sftp_auto_retrying);
                break;
            case BLOCKED:
                _autoStatusPreference.setSummary(R.string.sftp_auto_blocked);
                break;
            case SUCCESS:
                _autoStatusPreference.setSummary(getString(R.string.sftp_auto_success, time));
                break;
            case CLEANUP_WARNING:
                _autoStatusPreference.setSummary(getString(R.string.sftp_auto_cleanup_warning, time));
                break;
        }
        _autoStatusPreference.setSelectable(status.getReason() != null || status.getFilename() != null);
    }

    private void showAutoDetails() {
        SftpAutoBackupState.Status status = SftpAutoBackupState.getStatus(requireContext());
        String message;
        if (status.getReason() != null) {
            message = getString(errorMessage(status.getReason()));
        } else if (status.getFilename() != null) {
            message = getString(R.string.sftp_upload_file, status.getFilename());
        } else {
            return;
        }
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_auto_status)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null).create());
    }

    private void updateUploadStatus(SftpBackupManager.Status status) {
        _uploadPreference.setEnabled(!SftpBackupManager.isUploadActive());
        String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(status.getTime()));
        switch (status.getState()) {
            case NEVER:
                _uploadStatusPreference.setSummary(R.string.sftp_upload_never);
                break;
            case RUNNING:
                _uploadStatusPreference.setSummary(R.string.sftp_upload_running);
                break;
            case SUCCESS:
                _uploadStatusPreference.setSummary(getString(R.string.sftp_upload_success, time));
                break;
            case SUCCESS_CLEANUP_FAILED:
                _uploadStatusPreference.setSummary(getString(R.string.sftp_upload_cleanup_warning, time));
                break;
            case FAILED:
                _uploadStatusPreference.setSummary(getString(R.string.sftp_upload_failed, time));
                break;
            case INTERRUPTED:
                _uploadStatusPreference.setSummary(R.string.sftp_upload_interrupted);
                break;
        }
        _uploadStatusPreference.setSelectable(status.getState() != SftpBackupManager.State.NEVER
                && status.getState() != SftpBackupManager.State.RUNNING);
    }

    private void showUploadDetails() {
        SftpBackupManager.Status status = SftpBackupManager.getStatus(requireContext());
        String message;
        if (status.getState() == SftpBackupManager.State.FAILED) {
            message = getString(errorMessage(status.getReason()));
        } else if (status.getState() == SftpBackupManager.State.INTERRUPTED) {
            message = getString(R.string.sftp_upload_interrupted);
        } else if (status.getFilename() != null) {
            message = getString(R.string.sftp_upload_file, status.getFilename());
            if (status.getState() == SftpBackupManager.State.SUCCESS_CLEANUP_FAILED) {
                message += "\n\n" + getString(R.string.sftp_upload_cleanup_warning,
                        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(new Date(status.getTime())));
            }
        } else {
            return;
        }
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_upload_status)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null).create());
    }

    private void loadVersions() {
        _versionsPreference.setSummary(R.string.sftp_versions_loading);
        int generation = _testGeneration;
        android.content.Context context = requireContext().getApplicationContext();
        _executor.execute(() -> {
            List<SftpBackupStorage.Version> versions = null;
            SftpBackupException.Reason reason = null;
            try {
                versions = SftpBackupManager.listVersions(context);
            } catch (SftpBackupException e) {
                reason = e.getReason();
            } catch (RuntimeException e) {
                reason = SftpBackupException.Reason.LOCAL_STORAGE;
            }
            List<SftpBackupStorage.Version> result = versions;
            SftpBackupException.Reason failure = reason;
            _mainHandler.post(() -> {
                if (!isAdded() || generation != _testGeneration) {
                    return;
                }
                if (failure != null) {
                    _versionsPreference.setSummary(errorMessage(failure));
                    return;
                }
                _versionsPreference.setSummary(getString(R.string.sftp_versions_count, result.size()));
                if (result.isEmpty()) {
                    showDialog(new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.sftp_versions)
                            .setMessage(R.string.sftp_versions_empty)
                            .setPositiveButton(android.R.string.ok, null).create());
                } else {
                    int count = result.size();
                    CharSequence[] labels = new CharSequence[count];
                    for (int i = 0; i < count; i++) {
                        SftpBackupStorage.Version version = result.get(i);
                        labels[i] = version.getName() + "  (" + version.getSize() + " B)";
                    }
                    showDialog(new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.sftp_versions)
                            .setItems(labels, (dialog, which) -> startDownload(result.get(which).getName()))
                            .setNegativeButton(android.R.string.cancel, null).create());
                }
            });
        });
    }

    private void startDownload(String filename) {
        if (_restoreBusy) {
            return;
        }
        _restoreBusy = true;
        _versionsPreference.setEnabled(false);
        _versionsPreference.setSummary(R.string.sftp_restore_downloading);
        android.content.Context context = requireContext().getApplicationContext();
        _executor.execute(() -> {
            File file = null;
            SftpBackupException.Reason reason = null;
            try {
                file = SftpBackupManager.downloadVersion(context, filename);
            } catch (SftpBackupException e) {
                reason = e.getReason();
            } catch (RuntimeException e) {
                reason = SftpBackupException.Reason.LOCAL_STORAGE;
            }
            File downloaded = file;
            SftpBackupException.Reason failure = reason;
            _mainHandler.post(() -> {
                if (!isAdded()) {
                    if (downloaded != null) {
                        downloaded.delete();
                    }
                    return;
                }
                if (failure != null) {
                    finishRestore(errorMessage(failure));
                    return;
                }
                _downloadedRestore = downloaded;
                promptRestorePassword(downloaded, filename, false);
            });
        });
    }

    private void promptRestorePassword(File file, String filename, boolean rollback) {
        if (rollback) {
            _restoreBusy = true;
            _versionsPreference.setEnabled(false);
        }
        EditText input = passwordInput();
        boolean[] submitted = {false};
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(rollback ? R.string.sftp_restore_previous_password
                        : R.string.sftp_restore_backup_password)
                .setView(input)
                .setPositiveButton(R.string.sftp_restore_verify, (dialog, which) -> {
                    submitted[0] = true;
                    char[] password = new char[input.length()];
                    input.getText().getChars(0, input.length(), password, 0);
                    input.getText().clear();
                    validateRestore(file, filename, rollback, password);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener(dialog -> {
                    input.getText().clear();
                    if (!submitted[0]) {
                        finishRestore(R.string.sftp_versions_read_only);
                    }
                }).create());
    }

    private void validateRestore(File file, String filename, boolean rollback, char[] password) {
        _versionsPreference.setSummary(R.string.sftp_restore_validating);
        android.content.Context context = requireContext().getApplicationContext();
        _executor.execute(() -> {
            SftpRestoreManager.PreparedRestore prepared = null;
            SftpBackupException.Reason reason = null;
            try {
                prepared = rollback ? SftpRestoreManager.prepareRollback(context, password)
                        : SftpRestoreManager.prepare(context, file, password);
            } catch (SftpBackupException e) {
                reason = e.getReason();
            } catch (RuntimeException e) {
                reason = SftpBackupException.Reason.REMOTE_FILE_INVALID;
            } finally {
                Arrays.fill(password, '\0');
                if (!rollback) {
                    file.delete();
                }
            }
            SftpRestoreManager.PreparedRestore valid = prepared;
            SftpBackupException.Reason failure = reason;
            _mainHandler.post(() -> {
                _downloadedRestore = null;
                if (!isAdded()) {
                    return;
                }
                if (failure != null) {
                    finishRestore(errorMessage(failure));
                    return;
                }
                showRestoreConfirmation(filename, valid, rollback);
            });
        });
    }

    private void showRestoreConfirmation(String filename, SftpRestoreManager.PreparedRestore prepared,
                                         boolean rollback) {
        boolean introductory = requireActivity() instanceof SftpRestoreActivity;
        int message = introductory ? R.string.sftp_restore_confirm_new
                : rollback ? R.string.sftp_restore_confirm_rollback : R.string.sftp_restore_confirm_replace;
        showDialog(new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sftp_restore_confirm_title)
                .setMessage(getString(message, filename, prepared.getEntryCount()))
                .setPositiveButton(R.string.sftp_restore_confirm_action,
                        (dialog, which) -> commitRestore(prepared, introductory, rollback))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finishRestore(R.string.sftp_versions_read_only))
                .setCancelable(false).create());
    }

    private void commitRestore(SftpRestoreManager.PreparedRestore prepared, boolean introductory,
                               boolean rollback) {
        _versionsPreference.setSummary(R.string.sftp_restore_replacing);
        android.content.Context context = requireContext().getApplicationContext();
        _executor.execute(() -> {
            SftpBackupException.Reason reason = null;
            try {
                SftpRestoreManager.commit(context, _vaultManager, prepared);
                if (introductory) {
                    new com.beemdevelopment.aegis.Preferences(context).setIntroDone(true);
                }
            } catch (SftpBackupException e) {
                reason = e.getReason();
            } catch (RuntimeException e) {
                reason = SftpBackupException.Reason.LOCAL_STORAGE;
            }
            SftpBackupException.Reason failure = reason;
            _mainHandler.post(() -> {
                if (!isAdded()) {
                    return;
                }
                if (failure != null) {
                    finishRestore(errorMessage(failure));
                    return;
                }
                finishRestore(R.string.sftp_restore_success);
                if (introductory) {
                    ((SftpRestoreActivity) requireActivity()).finishWithRestore();
                } else {
                    showDialog(new MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.sftp_restore_success)
                            .setMessage(rollback ? R.string.sftp_restore_rollback_success_hint
                                    : R.string.sftp_restore_unlock_hint)
                            .setPositiveButton(android.R.string.ok, (dialog, which) -> requireActivity().finish())
                            .setCancelable(false).create());
                }
            });
        });
    }

    private void finishRestore(int summary) {
        _restoreBusy = false;
        _versionsPreference.setEnabled(true);
        _versionsPreference.setSummary(summary);
        updateRollbackVisibility();
    }

    private void updateRollbackVisibility() {
        if (_rollbackPreference != null) {
            _rollbackPreference.setVisible(_vaultManager.isVaultLoaded()
                    && SftpRestoreManager.hasRollback(requireContext()));
        }
    }

    private int errorMessage(SftpBackupException.Reason reason) {
        if (reason == null) {
            return R.string.sftp_error_local_storage;
        }
        switch (reason) {
            case NOT_CONFIGURED: return R.string.sftp_error_not_configured;
            case CREDENTIALS_MISSING: return R.string.sftp_error_credentials_missing;
            case PIN_MISSING: return R.string.sftp_error_pin_missing;
            case HOST_KEY_CHANGED: return R.string.sftp_error_host_key_changed;
            case AUTHENTICATION_FAILED: return R.string.sftp_error_authentication_failed;
            case CONNECTION_FAILED: return R.string.sftp_error_connection_failed;
            case REMOTE_DIRECTORY: return R.string.sftp_error_remote_directory;
            case REMOTE_WRITE: return R.string.sftp_error_remote_write;
            case REMOTE_READ: return R.string.sftp_error_remote_read;
            case REMOTE_FILE_INVALID: return R.string.sftp_error_remote_file_invalid;
            case REMOTE_FILE_TOO_LARGE: return R.string.sftp_error_remote_file_too_large;
            case BACKUP_PASSWORD_INCORRECT: return R.string.sftp_error_backup_password_incorrect;
            case ROLLBACK_UNAVAILABLE: return R.string.sftp_error_rollback_unavailable;
            case SIZE_MISMATCH: return R.string.sftp_error_size_mismatch;
            case VAULT_UNENCRYPTED: return R.string.sftp_error_vault_unencrypted;
            case PASSWORD_SLOT_MISSING: return R.string.sftp_error_password_slot_missing;
            case UPLOAD_BUSY: return R.string.sftp_upload_busy;
            case CANCELLED: return R.string.sftp_error_cancelled;
            default: return R.string.sftp_error_local_storage;
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
