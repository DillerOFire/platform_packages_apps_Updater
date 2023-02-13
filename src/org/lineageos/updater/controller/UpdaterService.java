/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater.controller;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.model.UpdateInfo;
import org.lineageos.updater.model.UpdateStatus;

import java.io.IOException;

public class UpdaterService extends Service {

    private static final String TAG = "UpdaterService";

    public static final String ACTION_DOWNLOAD_CONTROL = "action_download_control";
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";
    public static final String EXTRA_DOWNLOAD_CONTROL = "extra_download_control";
    public static final String ACTION_INSTALL_UPDATE = "action_install_update";
    public static final String ACTION_INSTALL_STOP = "action_install_stop";

    public static final String ACTION_INSTALL_SUSPEND = "action_install_suspend";
    public static final String ACTION_INSTALL_RESUME = "action_install_resume";

    public static final int DOWNLOAD_RESUME = 0;
    public static final int DOWNLOAD_PAUSE = 1;

    private final IBinder mBinder = new LocalBinder();
    private boolean mHasClients;

    private BroadcastReceiver mBroadcastReceiver;

    private UpdaterController mUpdaterController;

    @Override
    public void onCreate() {
        super.onCreate();

        mUpdaterController = UpdaterController.getInstance(this);

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    UpdateInfo update = mUpdaterController.getUpdate(downloadId);
                    Bundle extras = new Bundle();
                    extras.putString(UpdaterController.EXTRA_DOWNLOAD_ID, downloadId);
                }
            }
        };
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);

    }

    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        super.onDestroy();
    }

    public class LocalBinder extends Binder {
        public UpdaterService getService() {
            return UpdaterService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        mHasClients = true;
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        mHasClients = false;
        tryStopSelf();
        return false;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Starting service");

        if (intent == null || intent.getAction() == null) {
            if (ABUpdateInstaller.isInstallingUpdate(this)) {
                // The service is being restarted.
                ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this,
                        mUpdaterController);
                installer.reconnect();
            }
        } else if (ACTION_DOWNLOAD_CONTROL.equals(intent.getAction())) {
            String downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID);
            int action = intent.getIntExtra(EXTRA_DOWNLOAD_CONTROL, -1);
            if (action == DOWNLOAD_RESUME) {
                mUpdaterController.resumeDownload(downloadId);
            } else if (action == DOWNLOAD_PAUSE) {
                mUpdaterController.pauseDownload(downloadId);
            } else {
                Log.e(TAG, "Unknown download action");
            }
        } else if (ACTION_INSTALL_UPDATE.equals(intent.getAction())) {
            String downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID);
            UpdateInfo update = mUpdaterController.getUpdate(downloadId);
            if (update.getPersistentStatus() != UpdateStatus.Persistent.VERIFIED) {
                throw new IllegalArgumentException(update.getDownloadId() + " is not verified");
            }
            try {
                if (Utils.isABUpdate(update.getFile())) {
                    ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this,
                            mUpdaterController);
                    installer.install(downloadId);
                } else {
                    UpdateInstaller installer = UpdateInstaller.getInstance(this,
                            mUpdaterController);
                    installer.install(downloadId);
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not install update", e);
                mUpdaterController.getActualUpdate(downloadId)
                        .setStatus(UpdateStatus.INSTALLATION_FAILED);
                mUpdaterController.notifyUpdateChange(downloadId);
            }
        } else if (ACTION_INSTALL_STOP.equals(intent.getAction())) {
            if (UpdateInstaller.isInstalling()) {
                UpdateInstaller installer = UpdateInstaller.getInstance(this,
                        mUpdaterController);
                installer.cancel();
            } else if (ABUpdateInstaller.isInstallingUpdate(this)) {
                ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this,
                        mUpdaterController);
                installer.reconnect();
                installer.cancel();
            }
        } else if (ACTION_INSTALL_SUSPEND.equals(intent.getAction())) {
            if (ABUpdateInstaller.isInstallingUpdate(this)) {
                ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this,
                        mUpdaterController);
                installer.reconnect();
                installer.suspend();
            }
        } else if (ACTION_INSTALL_RESUME.equals(intent.getAction())) {
            if (ABUpdateInstaller.isInstallingUpdateSuspended(this)) {
                ABUpdateInstaller installer = ABUpdateInstaller.getInstance(this,
                        mUpdaterController);
                installer.reconnect();
                installer.resume();
            }
        }
        return ABUpdateInstaller.isInstallingUpdate(this) ? START_STICKY : START_NOT_STICKY;
    }

    public UpdaterController getUpdaterController() {
        return mUpdaterController;
    }

    private void tryStopSelf() {
        if (!mHasClients && !mUpdaterController.hasActiveDownloads() &&
                !mUpdaterController.isInstallingUpdate()) {
            Log.d(TAG, "Service no longer needed, stopping");
            stopSelf();
        }
    }
}
