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
package com.android.updater.misc

import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemProperties
import android.os.storage.StorageManager
import android.util.Log
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.android.updater.R
import com.android.updater.UpdatesDbHelper
import com.android.updater.controller.UpdaterService
import com.android.updater.model.Update
import com.android.updater.model.UpdateBaseInfo
import com.android.updater.model.UpdateInfo
import com.android.updater.protos.OtaMetadata
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object Utils {
    private const val TAG = "Utils"
    fun getDownloadPath(context: Context): File {
        return File(context.getString(R.string.download_path))
    }

    fun parseProtoUpdate(build: OtaMetadata?): UpdateInfo {
        val update = Update()
        val postDeviceState = build!!.postcondition
        update.name = build.originalFilename
        update.downloadId = build.originalFilename
        update.type = build.type.number
        update.fileSize = build.sizeBytes
        update.downloadUrl = build.currentDownloadUrl
        update.changelogUrl = build.changelogUrl
        return update
    }

    fun isCompatible(update: UpdateBaseInfo): Boolean {
        if (update.version.compareTo(SystemProperties.get(Constants.PROP_BUILD_VERSION)) < 0) {
            Log.d(TAG, update.name + " is older than current Android version")
            return false
        }
        if (update.timestamp < SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)) {
            Log.d(TAG, update.name + " is older than/equal to the current build")
            return false
        }
        return true
    }

    fun canInstall(update: UpdateBaseInfo): Boolean {
        return update.timestamp >= SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0) &&
                update.version.equals(
                        SystemProperties.get(Constants.PROP_BUILD_VERSION), ignoreCase = true)
    }

    fun getServerURL(context: Context): String {
        var serverUrl = SystemProperties.get(Constants.PROP_UPDATER_URI)
        if (serverUrl.trim { it <= ' ' }.isEmpty()) {
            serverUrl = context.getString(R.string.updater_server_url)
        }
        return serverUrl
    }

    fun triggerUpdate(context: Context, downloadId: String?) {
        val intent = Intent(context, UpdaterService::class.java)
        intent.action = UpdaterService.Companion.ACTION_INSTALL_UPDATE
        intent.putExtra(UpdaterService.Companion.EXTRA_DOWNLOAD_ID, downloadId)
        context.startService(intent)
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val info = cm.activeNetworkInfo
        return !(info == null || !info.isConnected || !info.isAvailable)
    }

    fun isOnWifiOrEthernet(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val info = cm.activeNetworkInfo
        return info != null && (info.type == ConnectivityManager.TYPE_ETHERNET
                || info.type == ConnectivityManager.TYPE_WIFI)
    }

    /**
     * Get the offset to the compressed data of a file inside the given zip
     *
     * @param zipFile input zip file
     * @param entryPath full path of the entry
     * @return the offset of the compressed, or -1 if not found
     * @throws IllegalArgumentException if the given entry is not found
     */
    fun getZipEntryOffset(zipFile: ZipFile, entryPath: String?): Long {
        // Each entry has an header of (30 + n + m) bytes
        // 'n' is the length of the file name
        // 'm' is the length of the extra field
        val FIXED_HEADER_SIZE = 30
        val zipEntries = zipFile.entries()
        var offset: Long = 0
        while (zipEntries.hasMoreElements()) {
            val entry = zipEntries.nextElement()
            val n = entry.name.length
            val m = if (entry.extra == null) 0 else entry.extra.size
            val headerSize = FIXED_HEADER_SIZE + n + m
            offset += headerSize.toLong()
            if (entry.name == entryPath) {
                return offset
            }
            offset += entry.compressedSize
        }
        Log.e(TAG, "Entry $entryPath not found")
        throw IllegalArgumentException("The given entry was not found")
    }

    fun removeUncryptFiles(downloadPath: File) {
        val uncryptFiles = downloadPath.listFiles { dir: File?, name: String -> name.endsWith(Constants.UNCRYPT_FILE_EXT) }
                ?: return
        for (file in uncryptFiles) {
            file.delete()
        }
    }

    /**
     * Cleanup the download directory, which is assumed to be a privileged location
     * the user can't access and that might have stale files. This can happen if
     * the data of the application are wiped.
     *
     */
    fun cleanupDownloadsDir(context: Context) {
        val downloadPath = getDownloadPath(context)
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        removeUncryptFiles(downloadPath)
        val buildTimestamp = SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
        val prevTimestamp = preferences.getLong(Constants.PREF_INSTALL_OLD_TIMESTAMP, 0)
        val lastUpdatePath = preferences.getString(Constants.PREF_INSTALL_PACKAGE_PATH, null)
        val reinstalling = preferences.getBoolean(Constants.PREF_INSTALL_AGAIN, false)
        if ((buildTimestamp != prevTimestamp || reinstalling) &&
                lastUpdatePath != null) {
            val lastUpdate = File(lastUpdatePath)
            if (lastUpdate.exists()) {
                lastUpdate.delete()
                // Remove the pref not to delete the file if re-downloaded
                preferences.edit().remove(Constants.PREF_INSTALL_PACKAGE_PATH).apply()
            }
        }
        val DOWNLOADS_CLEANUP_DONE = "cleanup_done"
        if (preferences.getBoolean(DOWNLOADS_CLEANUP_DONE, false)) {
            return
        }
        Log.d(TAG, "Cleaning $downloadPath")
        if (!downloadPath.isDirectory) {
            return
        }
        val files = downloadPath.listFiles() ?: return

        // Ideally the database is empty when we get here
        val dbHelper = UpdatesDbHelper(context)
        val knownPaths: MutableList<String> = ArrayList()
        for (update in dbHelper.updates) {
            knownPaths.add(update.file!!.absolutePath)
        }
        for (file in files) {
            if (!knownPaths.contains(file.absolutePath)) {
                Log.d(TAG, "Deleting " + file.absolutePath)
                file.delete()
            }
        }
        preferences.edit().putBoolean(DOWNLOADS_CLEANUP_DONE, true).apply()
    }

    fun appendSequentialNumber(file: File?): File {
        val name: String
        val extension: String
        val extensionPosition = file!!.name.lastIndexOf(".")
        if (extensionPosition > 0) {
            name = file.name.substring(0, extensionPosition)
            extension = file.name.substring(extensionPosition)
        } else {
            name = file.name
            extension = ""
        }
        val parent = file.parentFile
        for (i in 1 until Int.MAX_VALUE) {
            val newFile = File(parent, "$name-$i$extension")
            if (!newFile.exists()) {
                return newFile
            }
        }
        throw IllegalStateException()
    }

    val isABDevice: Boolean
        get() = SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false)

    fun isABUpdate(zipFile: ZipFile): Boolean {
        return zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
                zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null
    }

    @Throws(IOException::class)
    fun isABUpdate(file: File?): Boolean {
        val zipFile = ZipFile(file)
        val isAB = isABUpdate(zipFile)
        zipFile.close()
        return isAB
    }

    fun hasTouchscreen(context: Context): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    fun addToClipboard(context: Context, label: String?, text: String?,
                       toastMessage: String?) {
        val clipboard = context.getSystemService(
                Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }

    fun isEncrypted(context: Context, file: File?): Boolean {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.isEncrypted(file)
    }

    fun getUpdateCheckSetting(context: Context?): Int {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context!!)
        return preferences.getInt(Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
                Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY)
    }

    fun isUpdateCheckEnabled(context: Context?): Boolean {
        return getUpdateCheckSetting(context) != Constants.AUTO_UPDATES_CHECK_INTERVAL_NEVER
    }

    fun getUpdateCheckInterval(context: Context?): Long {
        return when (getUpdateCheckSetting(context)) {
            Constants.AUTO_UPDATES_CHECK_INTERVAL_DAILY -> AlarmManager.INTERVAL_DAY
            Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY -> AlarmManager.INTERVAL_DAY * 7
            Constants.AUTO_UPDATES_CHECK_INTERVAL_MONTHLY -> AlarmManager.INTERVAL_DAY * 30
            else -> AlarmManager.INTERVAL_DAY * 7
        }
    }
}