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
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.text.TextUtils
import android.util.Log
import androidx.preference.PreferenceManager
import com.android.updater.misc.Constants
import com.android.updater.misc.Utils
import com.android.updater.model.UpdateStatus
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.zip.ZipFile
import kotlin.math.roundToInt

internal class ABUpdateInstaller private constructor(context: Context, private val mUpdaterController: UpdaterController) {
    private val mContext: Context
    private var mDownloadId: String = "noID"
    private val mUpdateEngine: UpdateEngine
    private var mBound = false
    private var mFinalizing = false
    private var mProgress = 0
    private val mUpdateEngineCallback: UpdateEngineCallback = object : UpdateEngineCallback() {
        override fun onStatusUpdate(status: Int, percent: Float) {
            val update = mUpdaterController.getActualUpdate(mDownloadId)
            if (update == null) {
                // We read the id from a preference, the update could no longer exist
                installationDone(status == UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT)
                return
            }
            when (status) {
                UpdateEngine.UpdateStatusConstants.DOWNLOADING, UpdateEngine.UpdateStatusConstants.FINALIZING -> {
                    if (update.status != UpdateStatus.INSTALLING) {
                        update.status = UpdateStatus.INSTALLING
                        mUpdaterController.notifyUpdateChange(mDownloadId)
                    }
                    mProgress = (percent * 100).roundToInt()
                    mUpdaterController.getActualUpdate(mDownloadId)!!.installProgress = mProgress
                    mFinalizing = status == UpdateEngine.UpdateStatusConstants.FINALIZING
                    mUpdaterController.getActualUpdate(mDownloadId)!!.finalizing = mFinalizing
                    mUpdaterController.notifyInstallProgress(mDownloadId)
                }

                UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT -> {
                    installationDone(true)
                    update.installProgress = 0
                    update.status = UpdateStatus.INSTALLED
                    mUpdaterController.notifyUpdateChange(mDownloadId)
                }

                UpdateEngine.UpdateStatusConstants.IDLE -> {

                    // The service was restarted because we thought we were installing an
                    // update, but we aren't, so clear everything.
                    installationDone(false)
                }
            }
        }

        override fun onPayloadApplicationComplete(errorCode: Int) {
            if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                installationDone(false)
                val update = mUpdaterController.getActualUpdate(mDownloadId)
                update!!.installProgress = 0
                update!!.status = UpdateStatus.INSTALLATION_FAILED
                mUpdaterController.notifyUpdateChange(mDownloadId)
            }
        }
    }

    init {
        mContext = context.applicationContext
        mUpdateEngine = UpdateEngine()
    }

    fun install(downloadId: String) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update")
            return
        }
        mDownloadId = downloadId
        val file = mUpdaterController.getActualUpdate(mDownloadId)!!.file
        if (!file!!.exists()) {
            Log.e(TAG, "The given update doesn't exist")
            mUpdaterController.getActualUpdate(downloadId)!!.status = UpdateStatus.INSTALLATION_FAILED
            mUpdaterController.notifyUpdateChange(downloadId)
            return
        }
        val offset: Long
        var headerKeyValuePairs: Array<String?>
        try {
            val zipFile = ZipFile(file)
            offset = Utils.getZipEntryOffset(zipFile, Constants.AB_PAYLOAD_BIN_PATH)
            val payloadPropEntry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH)
            zipFile.getInputStream(payloadPropEntry).use { `is` ->
                InputStreamReader(`is`).use { isr ->
                    BufferedReader(isr).use { br ->
                        val lines: MutableList<String> = ArrayList()
                        var line: String
                        while (br.readLine().also { line = it } != null) {
                            lines.add(line)
                        }
                        headerKeyValuePairs = lines.toTypedArray()
                    }
                }
            }
            zipFile.close()
        } catch (e: IOException) {
            Log.e(TAG, "Could not prepare $file", e)
            mUpdaterController.getActualUpdate(mDownloadId)!!.status = UpdateStatus.INSTALLATION_FAILED
            mUpdaterController.notifyUpdateChange(mDownloadId)
            return
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Could not prepare $file", e)
            mUpdaterController.getActualUpdate(mDownloadId)!!.status = UpdateStatus.INSTALLATION_FAILED
            mUpdaterController.notifyUpdateChange(mDownloadId)
            return
        }
        if (!mBound) {
            mBound = mUpdateEngine.bind(mUpdateEngineCallback)
            if (!mBound) {
                Log.e(TAG, "Could not bind")
                mUpdaterController.getActualUpdate(downloadId)!!.status = UpdateStatus.INSTALLATION_FAILED
                mUpdaterController.notifyUpdateChange(downloadId)
                return
            }
        }
        val zipFileUri = "file://" + file.absolutePath
        mUpdateEngine.applyPayload(zipFileUri, offset, 0, headerKeyValuePairs)
        mUpdaterController.getActualUpdate(mDownloadId)!!.status = UpdateStatus.INSTALLING
        mUpdaterController.notifyUpdateChange(mDownloadId)
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_AB_ID, mDownloadId)
                .apply()
    }

    fun reconnect() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "reconnect: Not installing any update")
            return
        }
        if (mBound) {
            return
        }
        mDownloadId = PreferenceManager.getDefaultSharedPreferences(mContext)
                .getString(PREF_INSTALLING_AB_ID, null).orEmpty()

        // We will get a status notification as soon as we are connected
        mBound = mUpdateEngine.bind(mUpdateEngineCallback)
        if (!mBound) {
            Log.e(TAG, "Could not bind")
        }
    }

    private fun installationDone(needsReboot: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        val id = if (needsReboot) prefs.getString(PREF_INSTALLING_AB_ID, null) else null
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(Constants.PREF_NEEDS_REBOOT_ID, id)
                .remove(PREF_INSTALLING_AB_ID)
                .apply()
    }

    fun cancel() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update")
            return
        }
        if (!mBound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }
        mUpdateEngine.cancel()
        installationDone(false)
        mUpdaterController.getActualUpdate(mDownloadId)!!.status = UpdateStatus.INSTALLATION_CANCELLED
        mUpdaterController.notifyUpdateChange(mDownloadId)
    }

    fun suspend() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update")
            return
        }
        if (!mBound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }
        mUpdateEngine.suspend()
        mUpdaterController.getActualUpdate(mDownloadId)!!.status = UpdateStatus.INSTALLATION_SUSPENDED
        mUpdaterController.notifyUpdateChange(mDownloadId)
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_SUSPENDED_AB_ID, mDownloadId)
                .apply()
    }

    fun resume() {
        if (!isInstallingUpdateSuspended(mContext)) {
            Log.e(TAG, "cancel: No update is suspended")
            return
        }
        if (!mBound) {
            Log.e(TAG, "Not connected to update engine")
            return
        }
        mUpdateEngine.resume()
        mUpdaterController.getActualUpdate(mDownloadId)!!.status = UpdateStatus.INSTALLING
        mUpdaterController.notifyUpdateChange(mDownloadId)
        mUpdaterController.getActualUpdate(mDownloadId)!!.installProgress = mProgress
        mUpdaterController.getActualUpdate(mDownloadId)!!.finalizing = mFinalizing
        mUpdaterController.notifyInstallProgress(mDownloadId)
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .remove(PREF_INSTALLING_SUSPENDED_AB_ID)
                .apply()
    }

    companion object {
        private const val TAG = "ABUpdateInstaller"
        private const val PREF_INSTALLING_AB_ID = "installing_ab_id"
        private const val PREF_INSTALLING_SUSPENDED_AB_ID = "installing_suspended_ab_id"
        private var sInstance: ABUpdateInstaller? = null
        @Synchronized
        fun isInstallingUpdate(context: Context?): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context!!)
            return pref.getString(PREF_INSTALLING_AB_ID, null) != null ||
                    pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null
        }

        @Synchronized
        fun isInstallingUpdate(context: Context?, downloadId: String): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context!!)
            return downloadId == pref.getString(PREF_INSTALLING_AB_ID, null) ||
                    TextUtils.equals(pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null), downloadId)
        }

        @Synchronized
        fun isInstallingUpdateSuspended(context: Context?): Boolean {
            val pref = PreferenceManager.getDefaultSharedPreferences(context!!)
            return pref.getString(PREF_INSTALLING_SUSPENDED_AB_ID, null) != null
        }

        @Synchronized
        fun isWaitingForReboot(context: Context?, downloadId: String?): Boolean {
            val waitingId = PreferenceManager.getDefaultSharedPreferences(context!!)
                    .getString(Constants.PREF_NEEDS_REBOOT_ID, null)
            return TextUtils.equals(waitingId, downloadId)
        }

        @Synchronized
        fun getInstance(context: Context,
                        updaterController: UpdaterController?): ABUpdateInstaller {
            if (sInstance == null) {
                sInstance = ABUpdateInstaller(context, updaterController!!)
            }
            return sInstance!!
        }
    }
}