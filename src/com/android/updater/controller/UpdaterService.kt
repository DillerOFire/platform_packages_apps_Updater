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
package com.android.updater.controller

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.android.updater.misc.Utils
import com.android.updater.model.UpdateStatus
import com.android.updater.model.UpdateStatus.Persistent
import java.io.IOException

class UpdaterService : Service() {
    private val mBinder: IBinder = LocalBinder()
    private var mHasClients = false
    private var mBroadcastReceiver: BroadcastReceiver? = null
    var updaterController: UpdaterController? = null
        private set

    override fun onCreate() {
        super.onCreate()
        updaterController = UpdaterController.Companion.getInstance(this)
        mBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val downloadId = intent.getStringExtra(UpdaterController.Companion.EXTRA_DOWNLOAD_ID)
                if (UpdaterController.Companion.ACTION_UPDATE_STATUS == intent.action) {
                    val update = updaterController!!.getUpdate(downloadId)
                    val extras = Bundle()
                    extras.putString(UpdaterController.Companion.EXTRA_DOWNLOAD_ID, downloadId)
                }
            }
        }
        val intentFilter = IntentFilter()
        intentFilter.addAction(UpdaterController.Companion.ACTION_UPDATE_STATUS)
        intentFilter.addAction(UpdaterController.Companion.ACTION_UPDATE_REMOVED)
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver!!, intentFilter)
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver!!)
        super.onDestroy()
    }

    inner class LocalBinder : Binder() {
        val service: UpdaterService
            get() = this@UpdaterService
    }

    override fun onBind(intent: Intent): IBinder? {
        mHasClients = true
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        mHasClients = false
        tryStopSelf()
        return false
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.d(TAG, "Starting service")
        if (intent == null || intent.action == null) {
            if (ABUpdateInstaller.Companion.isInstallingUpdate(this)) {
                // The service is being restarted.
                val installer: ABUpdateInstaller = ABUpdateInstaller.Companion.getInstance(this,
                        updaterController)
                installer.reconnect()
            }
        } else if (ACTION_DOWNLOAD_CONTROL == intent.action) {
            val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID).orEmpty()
            val action = intent.getIntExtra(EXTRA_DOWNLOAD_CONTROL, -1)
            if (action == DOWNLOAD_RESUME) {
                updaterController!!.resumeDownload(downloadId)
            } else if (action == DOWNLOAD_PAUSE) {
                updaterController!!.pauseDownload(downloadId)
            } else {
                Log.e(TAG, "Unknown download action")
            }
        } else if (ACTION_INSTALL_UPDATE == intent.action) {
            val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID).orEmpty()
            updaterController!!.getUpdate(downloadId)?.let { update ->
                require(update.persistentStatus == Persistent.VERIFIED) { update.downloadId + " is not verified" }
                try {
                    if (Utils.isABUpdate(update.file)) {
                        val installer: ABUpdateInstaller = ABUpdateInstaller.Companion.getInstance(this,
                                updaterController)
                        installer.install(downloadId)
                    } else {
                        val installer: UpdateInstaller = UpdateInstaller.Companion.getInstance(this,
                                updaterController!!)
                        installer.install(downloadId)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Could not install update", e)
                    updaterController!!.getActualUpdate(downloadId).status = UpdateStatus.INSTALLATION_FAILED
                    updaterController!!.notifyUpdateChange(downloadId)
                }
            }
        } else if (ACTION_INSTALL_STOP == intent.action) {
            if (UpdateInstaller.Companion.isInstalling("")) {
                val installer: UpdateInstaller = UpdateInstaller.Companion.getInstance(this,
                        updaterController!!)
                installer.cancel()
            } else if (ABUpdateInstaller.Companion.isInstallingUpdate(this)) {
                val installer: ABUpdateInstaller = ABUpdateInstaller.Companion.getInstance(this,
                        updaterController)
                installer.reconnect()
                installer.cancel()
            }
        } else if (ACTION_INSTALL_SUSPEND == intent.action) {
            if (ABUpdateInstaller.Companion.isInstallingUpdate(this)) {
                val installer: ABUpdateInstaller = ABUpdateInstaller.Companion.getInstance(this,
                        updaterController)
                installer.reconnect()
                installer.suspend()
            }
        } else if (ACTION_INSTALL_RESUME == intent.action) {
            if (ABUpdateInstaller.Companion.isInstallingUpdateSuspended(this)) {
                val installer: ABUpdateInstaller = ABUpdateInstaller.Companion.getInstance(this,
                        updaterController)
                installer.reconnect()
                installer.resume()
            }
        }
        return if (ABUpdateInstaller.Companion.isInstallingUpdate(this)) START_STICKY else START_NOT_STICKY
    }

    private fun tryStopSelf() {
        if (!mHasClients && !updaterController!!.hasActiveDownloads() &&
                !updaterController!!.isInstallingUpdate) {
            Log.d(TAG, "Service no longer needed, stopping")
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "UpdaterService"
        const val ACTION_DOWNLOAD_CONTROL = "action_download_control"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_DOWNLOAD_CONTROL = "extra_download_control"
        const val ACTION_INSTALL_UPDATE = "action_install_update"
        const val ACTION_INSTALL_STOP = "action_install_stop"
        const val ACTION_INSTALL_SUSPEND = "action_install_suspend"
        const val ACTION_INSTALL_RESUME = "action_install_resume"
        const val DOWNLOAD_RESUME = 0
        const val DOWNLOAD_PAUSE = 1
    }
}