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

import android.content.Context
import android.os.RecoverySystem
import android.os.SystemClock
import android.os.SystemProperties
import android.util.Log
import androidx.preference.PreferenceManager
import com.android.updater.misc.Constants
import com.android.updater.misc.FileUtils
import com.android.updater.misc.FileUtils.ProgressCallBack
import com.android.updater.misc.Utils
import com.android.updater.model.UpdateInfo
import com.android.updater.model.UpdateStatus
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

internal class UpdateInstaller private constructor(context: Context, controller: UpdaterController) {
    private var mPrepareUpdateThread: Thread? = null

    @Volatile
    private var mCanCancel = false
    private val mContext: Context
    private val mUpdaterController: UpdaterController

    init {
        mContext = context.applicationContext
        mUpdaterController = controller
    }

    fun install(downloadId: String) {
        if (isInstalling) {
            Log.e(TAG, "Already installing an update")
            return
        }
        mUpdaterController.getUpdate(downloadId)?.let { update ->

            val preferences = PreferenceManager.getDefaultSharedPreferences(mContext)
            val buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
            val lastBuildTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP,
                    buildTimestamp)
            val isReinstalling = buildTimestamp == lastBuildTimestamp
            preferences.edit()
                    .putLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, buildTimestamp)
                    .putLong(Constants.PREF_INSTALL_NEW_TIMESTAMP, update.timestamp)
                    .putString(Constants.PREF_INSTALL_PACKAGE_PATH, update.file!!.absolutePath)
                    .putBoolean(Constants.PREF_INSTALL_AGAIN, isReinstalling)
                    .putBoolean(Constants.PREF_INSTALL_NOTIFIED, false)
                    .apply()
            if (Utils.isEncrypted(mContext, update.file)) {
                // uncrypt rewrites the file so that it can be read without mounting
                // the filesystem, so create a copy of it.
                prepareForUncryptAndInstall(update)
            } else {
                installPackage(update.file, downloadId)
            }
        }
    }

    private fun installPackage(update: File?, downloadId: String) {
        try {
            RecoverySystem.installPackage(mContext, update)
        } catch (e: IOException) {
            Log.e(TAG, "Could not install update", e)
            mUpdaterController.getActualUpdate(downloadId).status = UpdateStatus.INSTALLATION_FAILED
            mUpdaterController.notifyUpdateChange(downloadId)
        }
    }

    @Synchronized
    private fun prepareForUncryptAndInstall(update: UpdateInfo) {
        val uncryptFilePath = update.file!!.absolutePath + Constants.UNCRYPT_FILE_EXT
        val uncryptFile = File(uncryptFilePath)
        val copyUpdateRunnable: Runnable = object : Runnable {
            private var mLastUpdate: Long = -1
            val mProgressCallBack = object : ProgressCallBack {
                override fun update(progress: Int) {
                    val now = SystemClock.elapsedRealtime()
                    if (mLastUpdate < 0 || now - mLastUpdate > 500) {
                        mUpdaterController.getActualUpdate(update.downloadId).installProgress = progress
                        mUpdaterController.notifyInstallProgress(update.downloadId)
                        mLastUpdate = now
                    }
                }
            }

            override fun run() {
                try {
                    mCanCancel = true
                    FileUtils.copyFile(update.file, uncryptFile, mProgressCallBack)
                    try {
                        val perms: MutableSet<PosixFilePermission> = HashSet()
                        perms.add(PosixFilePermission.OWNER_READ)
                        perms.add(PosixFilePermission.OWNER_WRITE)
                        perms.add(PosixFilePermission.OTHERS_READ)
                        perms.add(PosixFilePermission.GROUP_READ)
                        Files.setPosixFilePermissions(uncryptFile.toPath(), perms)
                    } catch (exception: IOException) {
                    }
                    mCanCancel = false
                    if (mPrepareUpdateThread!!.isInterrupted) {
                        mUpdaterController.getActualUpdate(update.downloadId).status = UpdateStatus.INSTALLATION_CANCELLED
                        mUpdaterController.getActualUpdate(update.downloadId).installProgress = 0
                        uncryptFile.delete()
                    } else {
                        installPackage(uncryptFile, update.downloadId)
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Could not copy update", e)
                    uncryptFile.delete()
                    mUpdaterController.getActualUpdate(update.downloadId).status = UpdateStatus.INSTALLATION_FAILED
                } finally {
                    synchronized(this@UpdateInstaller) {
                        mCanCancel = false
                        mPrepareUpdateThread = null
                        sInstallingUpdate = null
                    }
                    mUpdaterController.notifyUpdateChange(update.downloadId)
                }
            }
        }
        mPrepareUpdateThread = Thread(copyUpdateRunnable)
        mPrepareUpdateThread!!.start()
        sInstallingUpdate = update.downloadId
        mCanCancel = false
        mUpdaterController.getActualUpdate(update.downloadId).status = UpdateStatus.INSTALLING
        mUpdaterController.notifyUpdateChange(update.downloadId)
    }

    @Synchronized
    fun cancel() {
        if (!mCanCancel) {
            Log.d(TAG, "Nothing to cancel")
            return
        }
        mPrepareUpdateThread!!.interrupt()
    }

    companion object {
        private const val TAG = "UpdateInstaller"
        private var sInstance: UpdateInstaller? = null
        private var sInstallingUpdate: String? = null

        @Synchronized
        fun getInstance(context: Context,
                        updaterController: UpdaterController): UpdateInstaller {
            if (sInstance == null) {
                sInstance = UpdateInstaller(context, updaterController)
            }
            return sInstance!!
        }

        @get:Synchronized
        val isInstalling: Boolean
            get() = sInstallingUpdate != null

        @Synchronized
        fun isInstalling(downloadId: String): Boolean {
            return sInstallingUpdate != null && sInstallingUpdate == downloadId
        }
    }
}