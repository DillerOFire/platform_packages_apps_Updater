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

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.os.PowerManager
import android.os.RecoverySystem
import android.os.SystemClock
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.android.updater.UpdatesDbHelper
import com.android.updater.download.DownloadClient
import com.android.updater.download.DownloadClient.DownloadCallback
import com.android.updater.misc.Utils
import com.android.updater.model.Update
import com.android.updater.model.UpdateInfo
import com.android.updater.model.UpdateStatus
import com.android.updater.model.UpdateStatus.Persistent
import java.io.File
import java.io.IOException

class UpdaterController private constructor(context: Context) {
    private val TAG = "UpdaterController"
    private val mContext: Context
    private val mBroadcastManager: LocalBroadcastManager
    private val mUpdatesDbHelper: UpdatesDbHelper
    private val mWakeLock: PowerManager.WakeLock
    private val mDownloadRoot: File?
    private var mActiveDownloads = 0
    private val mVerifyingUpdates: MutableSet<String> = HashSet()

    private class DownloadEntry(val mUpdate: Update) {
        var mDownloadClient: DownloadClient? = null
    }

    private val mDownloads: MutableMap<String?, DownloadEntry> = HashMap()

    init {
        mBroadcastManager = LocalBroadcastManager.getInstance(context)
        mUpdatesDbHelper = UpdatesDbHelper(context)
        mDownloadRoot = Utils.getDownloadPath(context)
        val powerManager = context.getSystemService(PowerManager::class.java)
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:wakelock")
        mWakeLock.setReferenceCounted(false)
        mContext = context.applicationContext
        Utils.cleanupDownloadsDir(context)
        for (update in mUpdatesDbHelper.updates) {
            addUpdate(update, false)
        }
    }

    fun notifyUpdateChange(downloadId: String?) {
        val intent = Intent()
        intent.action = ACTION_UPDATE_STATUS
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        mBroadcastManager.sendBroadcast(intent)
    }

    fun notifyUpdateDelete(downloadId: String?) {
        val intent = Intent()
        intent.action = ACTION_UPDATE_REMOVED
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        mBroadcastManager.sendBroadcast(intent)
    }

    fun notifyDownloadProgress(downloadId: String?) {
        val intent = Intent()
        intent.action = ACTION_DOWNLOAD_PROGRESS
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        mBroadcastManager.sendBroadcast(intent)
    }

    fun notifyInstallProgress(downloadId: String?) {
        val intent = Intent()
        intent.action = ACTION_INSTALL_PROGRESS
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId)
        mBroadcastManager.sendBroadcast(intent)
    }

    private fun tryReleaseWakelock() {
        if (!hasActiveDownloads()) {
            mWakeLock.release()
        }
    }

    private fun addDownloadClient(entry: DownloadEntry, downloadClient: DownloadClient?) {
        if (entry.mDownloadClient != null) {
            return
        }
        entry.mDownloadClient = downloadClient
        mActiveDownloads++
    }

    private fun removeDownloadClient(entry: DownloadEntry) {
        if (entry.mDownloadClient == null) {
            return
        }
        entry.mDownloadClient = null
        mActiveDownloads--
    }

    private fun getDownloadCallback(downloadId: String): DownloadCallback {
        return object : DownloadCallback {
            override fun onResponse(headers: DownloadClient.Headers) {
                val entry = mDownloads[downloadId] ?: return
                val update = entry.mUpdate
                val contentLength = headers["Content-Length"]
                if (contentLength != null) {
                    try {
                        val size = contentLength.toLong()
                        if (update.fileSize < size) {
                            update.fileSize = size
                        }
                    } catch (e: NumberFormatException) {
                        Log.e(TAG, "Could not get content-length")
                    }
                }
                update.status = UpdateStatus.DOWNLOADING
                update.persistentStatus = Persistent.INCOMPLETE
                Thread {
                    mUpdatesDbHelper.addUpdateWithOnConflict(update,
                            SQLiteDatabase.CONFLICT_REPLACE)
                }.start()
                notifyUpdateChange(downloadId)
            }

            override fun onSuccess() {
                Log.d(TAG, "Download complete")
                val entry = mDownloads[downloadId]
                if (entry != null) {
                    val update = entry.mUpdate
                    update.status = UpdateStatus.VERIFYING
                    removeDownloadClient(entry)
                    verifyUpdateAsync(downloadId)
                    notifyUpdateChange(downloadId)
                    tryReleaseWakelock()
                }
            }

            override fun onFailure(cancelled: Boolean) {
                if (cancelled) {
                    Log.d(TAG, "Download cancelled")
                    // Already notified
                } else {
                    val entry = mDownloads[downloadId]
                    if (entry != null) {
                        val update = entry.mUpdate
                        Log.e(TAG, "Download failed")
                        removeDownloadClient(entry)
                        update.status = UpdateStatus.PAUSED_ERROR
                        notifyUpdateChange(downloadId)
                    }
                }
                tryReleaseWakelock()
            }
        }
    }

    private fun getProgressListener(downloadId: String): DownloadClient.ProgressListener {
        return object : DownloadClient.ProgressListener {
            private var mLastUpdate: Long = 0
            private var mProgress = 0
            override fun update(bytesRead: Long, contentLength: Long, speed: Long, eta: Long) {
                var contentLength = contentLength
                val entry = mDownloads[downloadId] ?: return
                val update = entry.mUpdate
                if (contentLength <= 0) {
                    contentLength = if (update.fileSize <= 0) {
                        return
                    } else {
                        update.fileSize
                    }
                }
                if (contentLength <= 0) {
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val progress = Math.round(bytesRead * 100f / contentLength)
                if (progress != mProgress || now - mLastUpdate > MAX_REPORT_INTERVAL_MS) {
                    mProgress = progress
                    mLastUpdate = now
                    update.progress = progress
                    update.eta = eta
                    update.speed = speed
                    notifyDownloadProgress(downloadId)
                }
            }
        }
    }

    @SuppressLint("SetWorldReadable")
    private fun verifyUpdateAsync(downloadId: String) {
        mVerifyingUpdates.add(downloadId)
        Thread {
            val entry = mDownloads[downloadId]
            if (entry != null) {
                val update = entry.mUpdate
                val file = update.file
                if (file!!.exists() && verifyPackage(file)) {
                    file.setReadable(true, false)
                    update.persistentStatus = Persistent.VERIFIED
                    mUpdatesDbHelper.changeUpdateStatus(update)
                    update.status = UpdateStatus.VERIFIED
                } else {
                    update.persistentStatus = Persistent.UNKNOWN
                    mUpdatesDbHelper.removeUpdate(downloadId)
                    update.progress = 0
                    update.status = UpdateStatus.VERIFICATION_FAILED
                }
                mVerifyingUpdates.remove(downloadId)
                notifyUpdateChange(downloadId)
            }
        }.start()
    }

    private fun verifyPackage(file: File?): Boolean {
        return try {
            RecoverySystem.verifyPackage(file, null, null)
            Log.e(TAG, "Verification successful")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Verification failed", e)
            if (file!!.exists()) {
                file.delete()
            } else {
                // The download was probably stopped. Exit silently
                Log.e(TAG, "Error while verifying the file", e)
            }
            false
        }
    }

    private fun fixUpdateStatus(update: Update): Boolean {
        when (update.persistentStatus) {
            Persistent.VERIFIED, Persistent.INCOMPLETE -> if (update.file == null || !update.file!!.exists()) {
                update.status = UpdateStatus.UNKNOWN
                return false
            } else if (update.fileSize > 0) {
                update.status = UpdateStatus.PAUSED
                val progress = Math.round(
                        update.file!!.length() * 100f / update.fileSize)
                update.progress = progress
            }
        }
        return true
    }

    fun setUpdatesAvailableOnline(downloadIds: List<String?>, purgeList: Boolean) {
        val toRemove: MutableList<String> = ArrayList()
        for (entry in mDownloads.values) {
            val online = downloadIds.contains(entry.mUpdate.downloadId)
            entry.mUpdate.availableOnline = online
            if (!online && purgeList && entry.mUpdate.persistentStatus == Persistent.UNKNOWN) {
                entry.mUpdate.downloadId?.let { toRemove.add(it) }
            }
        }
        for (downloadId in toRemove) {
            Log.d(TAG, "$downloadId no longer available online, removing")
            mDownloads.remove(downloadId)
            notifyUpdateDelete(downloadId)
        }
    }

    fun addUpdate(update: UpdateInfo): Boolean {
        return addUpdate(update, true)
    }

    private fun addUpdate(updateInfo: UpdateInfo, availableOnline: Boolean): Boolean {
        Log.d(TAG, "Adding download: " + updateInfo.downloadId)
        if (mDownloads.containsKey(updateInfo.downloadId)) {
            Log.d(TAG, "Download (" + updateInfo.downloadId + ") already added")
            val entry = mDownloads[updateInfo.downloadId]
            if (entry != null) {
                val updateAdded = entry.mUpdate
                updateAdded.availableOnline = availableOnline && updateAdded.availableOnline
                updateAdded.downloadUrl = updateInfo.downloadUrl
            }
            return false
        }
        val update = Update(updateInfo)
        if (!fixUpdateStatus(update) && !availableOnline) {
            update.persistentStatus = Persistent.UNKNOWN
            deleteUpdateAsync(update)
            Log.d(TAG, update.downloadId + " had an invalid status and is not online")
            return false
        }
        update.availableOnline = availableOnline
        mDownloads[update.downloadId] = DownloadEntry(update)
        return true
    }

    @SuppressLint("WakelockTimeout")
    fun startDownload(downloadId: String) {
        Log.d(TAG, "Starting $downloadId")
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return
        }
        val entry = mDownloads[downloadId]
        if (entry == null) {
            Log.e(TAG, "Could not get download entry")
            return
        }
        val update = entry.mUpdate
        var destination: File? = File(mDownloadRoot.toString() + update.name)
        if (destination!!.exists()) {
            destination = Utils.appendSequentialNumber(destination)
            Log.d(TAG, "Changing name with " + destination.absolutePath)
        }
        update.file = destination
        val downloadClient: DownloadClient?
        try {
            downloadClient = DownloadClient.Builder()
                    .setUrl(update.downloadUrl)
                    .setDestination(update.file)
                    .setDownloadCallback(getDownloadCallback(downloadId))
                    .setProgressListener(getProgressListener(downloadId))
                    .setUseDuplicateLinks(true)
                    .build()
        } catch (exception: IOException) {
            Log.e(TAG, "Could not build download client")
            update.status = UpdateStatus.PAUSED_ERROR
            notifyUpdateChange(downloadId)
            return
        }
        addDownloadClient(entry, downloadClient)
        update.status = UpdateStatus.STARTING
        notifyUpdateChange(downloadId)
        downloadClient.start()
        mWakeLock.acquire()
    }

    @SuppressLint("WakelockTimeout")
    fun resumeDownload(downloadId: String) {
        Log.d(TAG, "Resuming $downloadId")
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return
        }
        val entry = mDownloads[downloadId]
        if (entry == null) {
            Log.e(TAG, "Could not get download entry")
            return
        }
        val update = entry.mUpdate
        val file = update.file
        if (file == null || !file.exists()) {
            Log.e(TAG, "The destination file of $downloadId doesn't exist, can't resume")
            update.status = UpdateStatus.PAUSED_ERROR
            notifyUpdateChange(downloadId)
            return
        }
        if (file.exists() && update.fileSize > 0 && file.length() >= update.fileSize) {
            Log.d(TAG, "File already downloaded, starting verification")
            update.status = UpdateStatus.VERIFYING
            verifyUpdateAsync(downloadId)
            notifyUpdateChange(downloadId)
        } else {
            val downloadClient: DownloadClient?
            try {
                downloadClient = DownloadClient.Builder()
                        .setUrl(update.downloadUrl)
                        .setDestination(update.file)
                        .setDownloadCallback(getDownloadCallback(downloadId))
                        .setProgressListener(getProgressListener(downloadId))
                        .setUseDuplicateLinks(true)
                        .build()
            } catch (exception: IOException) {
                Log.e(TAG, "Could not build download client")
                update.status = UpdateStatus.PAUSED_ERROR
                notifyUpdateChange(downloadId)
                return
            }
            addDownloadClient(entry, downloadClient)
            update.status = UpdateStatus.STARTING
            notifyUpdateChange(downloadId)
            downloadClient.resume()
            mWakeLock.acquire()
        }
    }

    fun pauseDownload(downloadId: String) {
        Log.d(TAG, "Pausing $downloadId")
        if (!isDownloading(downloadId)) {
            return
        }
        val entry = mDownloads[downloadId]
        if (entry != null) {
            entry.mDownloadClient!!.cancel()
            removeDownloadClient(entry)
            entry.mUpdate.status = UpdateStatus.PAUSED
            entry.mUpdate.eta = 0
            entry.mUpdate.speed = 0
            notifyUpdateChange(downloadId)
        }
    }

    private fun deleteUpdateAsync(update: Update) {
        Thread {
            val file = update.file
            if (file != null && file.exists() && !file.delete()) {
                Log.e(TAG, "Could not delete " + file.absolutePath)
            }
            update.downloadId?.let { mUpdatesDbHelper.removeUpdate(it) }
        }.start()
    }

    fun deleteUpdate(downloadId: String) {
        Log.d(TAG, "Cancelling $downloadId")
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId)) {
            return
        }
        val entry = mDownloads[downloadId]
        if (entry != null) {
            val update = entry.mUpdate
            update.status = UpdateStatus.DELETED
            update.progress = 0
            update.persistentStatus = Persistent.UNKNOWN
            deleteUpdateAsync(update)
            if (!update.availableOnline) {
                Log.d(TAG, "Download no longer available online, removing")
                mDownloads.remove(downloadId)
                notifyUpdateDelete(downloadId)
            } else {
                notifyUpdateChange(downloadId)
            }
        }
    }

    val updates: List<UpdateInfo>
        get() {
            val updates: MutableList<UpdateInfo> = ArrayList()
            for (entry in mDownloads.values) {
                updates.add(entry.mUpdate)
            }
            return updates
        }

    fun getUpdate(downloadId: String?): UpdateInfo? {
        val entry = mDownloads[downloadId]
        return entry?.mUpdate
    }

    fun getActualUpdate(downloadId: String): Update {
        val entry = mDownloads[downloadId]
        return entry!!.mUpdate
    }

    fun isDownloading(downloadId: String?): Boolean {
        return mDownloads.containsKey(downloadId) &&
                mDownloads[downloadId]!!.mDownloadClient != null
    }

    fun hasActiveDownloads(): Boolean {
        return mActiveDownloads > 0
    }

    val isVerifyingUpdate: Boolean
        get() = mVerifyingUpdates.size > 0

    fun isVerifyingUpdate(downloadId: String): Boolean {
        return mVerifyingUpdates.contains(downloadId)
    }

    val isInstallingUpdate: Boolean
        get() = UpdateInstaller.Companion.isInstalling("noID") ||
                ABUpdateInstaller.Companion.isInstallingUpdate(mContext)

    fun isInstallingUpdate(downloadId: String?): Boolean {
        return downloadId?.let { UpdateInstaller.isInstalling(it) } == true ||
                downloadId?.let { ABUpdateInstaller.isInstallingUpdate(mContext, it) } == true
    }

    val isInstallingABUpdate: Boolean
        get() = ABUpdateInstaller.isInstallingUpdate(mContext)

    fun isWaitingForReboot(downloadId: String?): Boolean {
        return ABUpdateInstaller.isWaitingForReboot(mContext, downloadId)
    }

    companion object {
        const val ACTION_DOWNLOAD_PROGRESS = "action_download_progress"
        const val ACTION_INSTALL_PROGRESS = "action_install_progress"
        const val ACTION_UPDATE_REMOVED = "action_update_removed"
        const val ACTION_UPDATE_STATUS = "action_update_status_change"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        private var sUpdaterController: UpdaterController? = null
        private const val MAX_REPORT_INTERVAL_MS = 100
        @Synchronized
        fun getInstance(context: Context): UpdaterController? {
            if (sUpdaterController == null) {
                sUpdaterController = UpdaterController(context)
            }
            return sUpdaterController
        }
    }
}