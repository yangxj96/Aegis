package com.beemdevelopment.aegis.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.backup.sftp.SftpBackupException;
import com.beemdevelopment.aegis.backup.sftp.SftpBackupManager;
import com.beemdevelopment.aegis.backup.sftp.SftpBackupStorage;
import com.beemdevelopment.aegis.helpers.ViewHelper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lists remote encrypted backups and returns the selected filename after explicit confirmation. */
public class SftpVersionsActivity extends AegisActivity {
    public static final String EXTRA_FILENAME = "com.beemdevelopment.aegis.extra.SFTP_BACKUP_FILENAME";

    private final Handler _mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService _executor = Executors.newSingleThreadExecutor();
    private final List<SftpBackupStorage.Version> _versions = new ArrayList<>();
    private VersionAdapter _adapter;
    private RecyclerView _versionsView;
    private View _statusPanel;
    private ProgressBar _progress;
    private TextView _statusText;
    private MaterialButton _retryButton;
    private TextView _countText;
    private MenuItem _refreshMenuItem;
    private int _loadGeneration;
    private boolean _loading;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sftp_versions);
        setSupportActionBar(findViewById(R.id.toolbar));
        ViewHelper.setupAppBarInsets(findViewById(R.id.app_bar_layout));
        setTitle(R.string.sftp_versions);

        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
            bar.setDisplayShowHomeEnabled(true);
        }

        _versionsView = findViewById(R.id.list_sftp_versions);
        _versionsView.setLayoutManager(new LinearLayoutManager(this));
        _adapter = new VersionAdapter();
        _versionsView.setAdapter(_adapter);
        _statusPanel = findViewById(R.id.sftp_versions_status_panel);
        _progress = findViewById(R.id.sftp_versions_progress);
        _statusText = findViewById(R.id.sftp_versions_status_text);
        _retryButton = findViewById(R.id.sftp_versions_retry);
        _countText = findViewById(R.id.sftp_versions_count);
        _retryButton.setOnClickListener(view -> loadVersions());

        loadVersions();
    }

    @Override
    protected void onDestroy() {
        ++_loadGeneration;
        _executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_sftp_versions, menu);
        _refreshMenuItem = menu.findItem(R.id.action_refresh_sftp_versions);
        _refreshMenuItem.setEnabled(!_loading);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_refresh_sftp_versions) {
            loadVersions();
            return true;
        }
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadVersions() {
        _loading = true;
        if (_refreshMenuItem != null) {
            _refreshMenuItem.setEnabled(false);
        }
        int generation = ++_loadGeneration;
        _versionsView.setVisibility(View.GONE);
        _countText.setVisibility(View.GONE);
        _statusPanel.setVisibility(View.VISIBLE);
        _progress.setVisibility(View.VISIBLE);
        _statusText.setText(R.string.sftp_versions_loading);
        _retryButton.setVisibility(View.GONE);

        android.content.Context context = getApplicationContext();
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
            _mainHandler.post(() -> showVersions(generation, result, failure));
        });
    }

    private void showVersions(int generation, List<SftpBackupStorage.Version> versions,
                              SftpBackupException.Reason reason) {
        if (isFinishing() || isDestroyed() || generation != _loadGeneration) {
            return;
        }
        _loading = false;
        if (_refreshMenuItem != null) {
            _refreshMenuItem.setEnabled(true);
        }
        _progress.setVisibility(View.GONE);
        _statusPanel.setVisibility(View.VISIBLE);
        if (reason != null) {
            _statusText.setText(SftpErrorMessages.getMessageResource(reason));
            _retryButton.setVisibility(View.VISIBLE);
            return;
        }

        _versions.clear();
        _versions.addAll(versions);
        _adapter.notifyDataSetChanged();
        if (_versions.isEmpty()) {
            _statusText.setText(R.string.sftp_versions_empty);
            _retryButton.setVisibility(View.GONE);
            return;
        }

        _countText.setText(getString(R.string.sftp_versions_count, _versions.size()));
        _countText.setVisibility(View.VISIBLE);
        _versionsView.setVisibility(View.VISIBLE);
        _statusPanel.setVisibility(View.GONE);
    }

    private void confirmVersion(SftpBackupStorage.Version version) {
        String message = getString(R.string.sftp_version_download_confirm,
                SftpVersionFormatter.formatTimestamp(this, version.getName()),
                SftpVersionFormatter.formatSize(this, version.getSize()));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sftp_version_download_title)
                .setMessage(message)
                .setPositiveButton(R.string.sftp_version_download_action,
                        (dialog, which) -> returnSelectedVersion(version))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void returnSelectedVersion(SftpBackupStorage.Version version) {
        Intent result = new Intent();
        result.putExtra(EXTRA_FILENAME, version.getName());
        setResult(RESULT_OK, result);
        finish();
    }

    private final class VersionAdapter extends RecyclerView.Adapter<VersionViewHolder> {
        @NonNull
        @Override
        public VersionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_sftp_version, parent, false);
            return new VersionViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VersionViewHolder holder, int position) {
            SftpBackupStorage.Version version = _versions.get(position);
            holder.time.setText(SftpVersionFormatter.formatTimestamp(
                    SftpVersionsActivity.this, version.getName()));
            holder.size.setText(getString(R.string.sftp_version_size,
                    SftpVersionFormatter.formatSize(SftpVersionsActivity.this, version.getSize())));
            holder.itemView.setOnClickListener(view -> confirmVersion(version));
        }

        @Override
        public int getItemCount() {
            return _versions.size();
        }
    }

    private static final class VersionViewHolder extends RecyclerView.ViewHolder {
        private final TextView time;
        private final TextView size;

        private VersionViewHolder(@NonNull View view) {
            super(view);
            time = view.findViewById(R.id.text_sftp_version_time);
            size = view.findViewById(R.id.text_sftp_version_size);
        }
    }
}
