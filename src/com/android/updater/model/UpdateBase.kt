/*
 * Copyright (C) 2017 The LineageOS Project
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
package com.android.updater.model

open class UpdateBase : UpdateBaseInfo {
    override var name: String = ""
    override var downloadUrl: String = ""
    override var downloadId: String = ""
    override var timestamp: Long = 0
    override var type = 0
    override var version: String = ""
    override var fileSize: Long = 0
    override var changelogUrl: String = ""

    constructor()
    constructor(update: UpdateBaseInfo) {
        name = update.name
        downloadUrl = update.downloadUrl
        downloadId = update.downloadId
        timestamp = update.timestamp
        type = update.type
        version = update.version
        fileSize = update.fileSize
    }
}