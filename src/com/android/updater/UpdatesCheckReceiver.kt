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
package com.android.updaterimport

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import com.android.updater.R
import com.android.updater.UpdatesActivity
import com.android.updater.misc.Utils
import com.android.updater.protos.OtaMetadata
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date

class UpdatesCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            Utils.cleanupDownloadsDir(context)
        }
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        if (!Utils.isUpdateCheckEnabled(context)) {
            return
        }
        if (Intent.ACTION_BOOT_COMPLETED == intent.action) {
            // Set a repeating alarm on boot to check for new updates once per day
            scheduleRepeatingUpdatesCheck(context)
        }
        if (!Utils.isNetworkAvailable(context)) {
            Log.d(TAG, "Network not available, scheduling new check")
            scheduleUpdatesCheck(context)
            return
        }
        try {
            val urlOTA = Utils.getServerURL(context)
            val url = URL(urlOTA)
            val urlConnection = url.openConnection() as HttpURLConnection
            val `in`: InputStream = BufferedInputStream(urlConnection.inputStream)
            val buildBytes = `in`.readAllBytes()
            val build = OtaMetadata.parseFrom(buildBytes)
            Log.d(UpdatesCheckReceiver.Companion.TAG, "Saving update for " + build.originalFilename)
            preferences.edit().putString("update", Base64.encodeToString(buildBytes, Base64.DEFAULT)).apply()
            preferences.edit().commit()
        } catch (e: Exception) {
            Log.e(UpdatesCheckReceiver.Companion.TAG, "Failed to perform scheduled update check", e)
        }
    }

    companion object {
        private const val TAG = "UpdatesCheckReceiver"
        private const val DAILY_CHECK_ACTION = "daily_check_action"
        private const val ONESHOT_CHECK_ACTION = "oneshot_check_action"
        private const val NEW_UPDATES_NOTIFICATION_CHANNEL = "new_updates_notification_channel"
        private fun showNotification(context: Context) {
            val notificationManager = context.getSystemService(
                    NotificationManager::class.java)
            val notificationChannel = NotificationChannel(
                    UpdatesCheckReceiver.Companion.NEW_UPDATES_NOTIFICATION_CHANNEL,
                    context.getString(R.string.new_updates_channel_title),
                    NotificationManager.IMPORTANCE_LOW)
            val notificationBuilder: NotificationCompat.Builder = NotificationCompat.Builder(context,
                    UpdatesCheckReceiver.Companion.NEW_UPDATES_NOTIFICATION_CHANNEL)
            notificationBuilder.setSmallIcon(R.drawable.ic_system_update_dl)
            val notificationIntent = Intent(context, UpdatesActivity::class.java)
            val intent = PendingIntent.getActivity(context, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            notificationBuilder.setContentIntent(intent)
            notificationBuilder.setContentTitle(context.getString(R.string.new_updates_found_title))
            notificationBuilder.setAutoCancel(true)
            notificationManager.createNotificationChannel(notificationChannel)
            notificationManager.notify(0, notificationBuilder.build())
        }

        private fun getRepeatingUpdatesCheckIntent(context: Context): PendingIntent {
            val intent = Intent(context, UpdatesCheckReceiver::class.java)
            intent.action = UpdatesCheckReceiver.Companion.DAILY_CHECK_ACTION
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        fun updateRepeatingUpdatesCheck(context: Context) {
            cancelRepeatingUpdatesCheck(context)
            scheduleRepeatingUpdatesCheck(context)
        }

        fun scheduleRepeatingUpdatesCheck(context: Context) {
            val updateCheckIntent: PendingIntent = UpdatesCheckReceiver.Companion.getRepeatingUpdatesCheckIntent(context)
            val alarmMgr = context.getSystemService(AlarmManager::class.java)
            alarmMgr.setRepeating(AlarmManager.RTC, System.currentTimeMillis() +
                    AlarmManager.INTERVAL_DAY, AlarmManager.INTERVAL_DAY,
                    updateCheckIntent)
            val nextCheckDate = Date(System.currentTimeMillis() +
                    AlarmManager.INTERVAL_DAY)
            Log.d(TAG, "Setting automatic updates check: $nextCheckDate")
        }

        fun cancelRepeatingUpdatesCheck(context: Context) {
            val alarmMgr = context.getSystemService(AlarmManager::class.java)
            alarmMgr.cancel(getRepeatingUpdatesCheckIntent(context))
        }

        private fun getUpdatesCheckIntent(context: Context): PendingIntent {
            val intent = Intent(context, UpdatesCheckReceiver::class.java)
            intent.action = ONESHOT_CHECK_ACTION
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        }

        fun scheduleUpdatesCheck(context: Context) {
            val millisToNextCheck = AlarmManager.INTERVAL_HOUR * 2
            val updateCheckIntent: PendingIntent = getUpdatesCheckIntent(context)
            val alarmMgr = context.getSystemService(AlarmManager::class.java)
            alarmMgr[AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + millisToNextCheck] = updateCheckIntent
            val nextCheckDate = Date(System.currentTimeMillis() + millisToNextCheck)
            Log.d(TAG, "Setting one-shot updates check: $nextCheckDate")
        }

        fun cancelUpdatesCheck(context: Context) {
            val alarmMgr = context.getSystemService(AlarmManager::class.java)
            alarmMgr.cancel(getUpdatesCheckIntent(context))
            Log.d(TAG, "Cancelling pending one-shot check")
        }
    }
}