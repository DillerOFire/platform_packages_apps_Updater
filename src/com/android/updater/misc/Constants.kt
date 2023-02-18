/*
 * Copyright (C) 2017-2020 The LineageOS Project
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

object Constants {
    const val AB_PAYLOAD_BIN_PATH = "payload.bin"
    const val AB_PAYLOAD_PROPERTIES_PATH = "payload_properties.txt"
    const val AUTO_UPDATES_CHECK_INTERVAL_NEVER = 0
    const val AUTO_UPDATES_CHECK_INTERVAL_DAILY = 1
    const val AUTO_UPDATES_CHECK_INTERVAL_WEEKLY = 2
    const val AUTO_UPDATES_CHECK_INTERVAL_MONTHLY = 3
    const val PREF_LAST_UPDATE_CHECK = "last_update_check"
    const val PREF_AUTO_UPDATES_CHECK_INTERVAL = "auto_updates_check_interval"
    const val PREF_MOBILE_DATA_WARNING = "pref_mobile_data_warning"
    const val PREF_NEEDS_REBOOT_ID = "needs_reboot_id"
    const val UNCRYPT_FILE_EXT = ".uncrypt"
    const val PROP_AB_DEVICE = "ro.build.ab_update"
    const val PROP_BUILD_DATE = "ro.build.date.utc"
    const val PROP_BUILD_SECURITY_PATCH = "ro.build.version.security_patch"
    const val PROP_BUILD_VERSION = "ro.build.version.release"
    const val PROP_BUILD_VERSION_INCREMENTAL = "ro.build.version.incremental"
    const val PROP_DEVICE = "ro.build.product"
    const val PROP_NEXT_DEVICE = "ro.updater.next_device"
    const val PROP_RELEASE_TYPE = "ro.build.type"
    const val PROP_UPDATER_URI = "ro.updater.uri"
    const val PROP_UPDATER_URI_CHANGELOG = "ro.updater.uri.changelog"
    const val PREF_INSTALL_OLD_TIMESTAMP = "install_old_timestamp"
    const val PREF_INSTALL_NEW_TIMESTAMP = "install_new_timestamp"
    const val PREF_INSTALL_PACKAGE_PATH = "install_package_path"
    const val PREF_INSTALL_AGAIN = "install_again"
    const val PREF_INSTALL_NOTIFIED = "install_notified"
    const val HAS_SEEN_INFO_DIALOG = "has_seen_info_dialog"
}