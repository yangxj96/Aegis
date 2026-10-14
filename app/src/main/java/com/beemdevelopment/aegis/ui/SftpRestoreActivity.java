package com.beemdevelopment.aegis.ui;

import android.os.Bundle;
import android.view.MenuItem;

import com.beemdevelopment.aegis.R;
import com.beemdevelopment.aegis.helpers.ViewHelper;
import com.beemdevelopment.aegis.ui.fragments.preferences.SftpPreferencesFragment;

import dagger.hilt.android.AndroidEntryPoint;

/** Hosts SFTP connection settings before a vault exists on a new device. */
@AndroidEntryPoint
public class SftpRestoreActivity extends AegisActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preferences);
        setSupportActionBar(findViewById(R.id.toolbar));
        ViewHelper.setupAppBarInsets(findViewById(R.id.app_bar_layout));
        setTitle(R.string.sftp_restore_from_server);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.content, new SftpPreferencesFragment())
                    .commit();
        }
    }

    public void finishWithRestore() {
        _prefs.setIntroDone(true);
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            getOnBackPressedDispatcher().onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
