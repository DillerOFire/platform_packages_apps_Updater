package com.android.updater.ui

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.icu.text.DateFormat
import android.icu.text.NumberFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.os.StrictMode
import android.os.SystemProperties
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.android.updater.R
import com.android.updater.controller.UpdaterController
import com.android.updater.controller.UpdaterService
import com.android.updater.controller.UpdaterService.LocalBinder
import com.android.updater.misc.BuildInfoUtils
import com.android.updater.misc.StringGenerator
import com.android.updater.misc.Utils
import com.android.updater.model.DeviceState
import com.android.updater.model.OtaMeta
import com.android.updater.model.UpdateInfo
import com.android.updater.model.UpdateStatus
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL

class UpdatesActivity : AppCompatActivity() {
    private var exception: Exception? = null
    private var activity: UpdatesActivity? = null
    private var prefs: SharedPreferences? = null
    private var prefsEditor: SharedPreferences.Editor? = null

    //Updates viewModel
    private val viewModel: UpdatesViewModel by viewModels()

    //The map of the hour, "pageId" = Page
    private val pages = HashMap<String?, Page?>()

    //Layout to render the pages to
    var pageIdActive: String? = ""
    var headerIcon: ImageView? = null
    var headerTitle: TextView? = null
    var headerStatus: TextView? = null
    var btnPrimary: Button? = null
    var btnSecondary: Button? = null
    var btnExtra: Button? = null
    var progressText: TextView? = null
    var progressBar: ProgressBar? = null
    var webView: WebView? = null
    var htmlContentLast = ""

    //Special details
    private var mHandler: Handler? = null
    private var mRunnable: Runnable? = null
    private var update: UpdateInfo? = null
    private var build: OtaMeta? = null
    private var updateId: String = ""
    private var wasUpdating = false
    private var updateCheck = false
    private var installingUpdate = false
    private var htmlColor = 0
    private var htmlCurrentBuild = ""
    private var htmlChangelog: String = ""

    //Android services
    private var mUpdaterService: UpdaterService? = null
    private var mBroadcastReceiver: BroadcastReceiver? = null
    private var mUpdaterController: UpdaterController? = null
    private var mUpdateEngine: UpdateEngine? = null
    private var mUpdateEngineCallback: UpdateEngineCallback? = null
    fun getPage(pageId: String?): Page? {
        //Log.d(TAG, "Get page: " + pageId);
        return pages[pageId]
    }

    fun renderPage(pageId: String) {
        var pageId = pageId
        if (mUpdaterController != null && !installingUpdate) {
            if (!(mUpdaterController!!.isDownloading(updateId) ||
                            mUpdaterController!!.isVerifyingUpdate(updateId) ||
                            mUpdaterController!!.isInstallingUpdate(updateId) ||
                            mUpdaterController!!.isWaitingForReboot(updateId))) {

                //Log.d(TAG, "UpdateController not updating checking current page: " + pageId);
                if (pageId == "updateInstalling" || pageId == "updateInstallingPaused" || pageId == "updateInstalled") {
                    Log.d(TAG, "Invalid state detected, returning to initial page")
                    prefsEditor!!.clear().apply() // Clear the preferences editor
                    pageId = "checkForUpdates" // Set the page id to "updateChecking"
                }
            }
        }

        //Log.d(TAG, "Render page: " + pageId);
        if (pageIdActive != "" && pageIdActive != pageId) {
            val pageLast = getPage(pageIdActive)
            if (pageLast != null) {
                pageLast.runnableRan = false
            }
        }
        var page = getPage(pageId)
        if (page == null) {
            page = getPage("error")
            page!!.htmlContent = "Unknown pageId: $pageId"
        }
        pageIdActive = pageId
        if (pageIdActive == "error" || pageIdActive == "checkForUpdates" || pageIdActive == "updateAvailable" || pageIdActive == "updateChecking" || pageIdActive != "enrollEarlyUpdates") {
            //Log.d(TAG, "Saving pageId " + pageIdActive);
            prefsEditor!!.putString("pageId", pageIdActive).apply()
        }
        val finalPage = page // Make page final to use it in the runOnUiThread
        runOnUiThread {
            finalPage.render(this)
            if (!finalPage.runnableRan) {
                finalPage.runnableRan = true
                val thread = Thread(finalPage.runnable)
                thread.start()
            }
        }
    }

    fun renderPageProgress(pageId: String, progress: Int, progressStep: String?) {
        var progress = progress
        val page = getPage(pageId)
        if (progress < 0) {
            progress = 1
            progressBar!!.isIndeterminate = true
        } else {
            progressBar!!.isIndeterminate = false
        }
        page!!.progPercent = progress
        page.progStep = progressStep!!
        prefsEditor!!.putInt("progPercent", page.progPercent).apply()
        prefsEditor!!.putString("progStep", page.progStep).apply()
        renderPage(pageId)
    }

    fun renderPageBatteryCheck(pageId: String, progressStep: String?) {
        val page = getPage(pageId)
        page!!.progPercent = 1
        page.progStep = progressStep!!
        progressBar!!.isIndeterminate = true
        renderPage(pageId)

        // Create a new handler if it doesn't exist yet
        if (mHandler == null) {
            mHandler = Handler()
        }

        // Create a new runnable if it doesn't exist yet
        if (mRunnable == null) {
            mRunnable = object : Runnable {
                override fun run() {
                    if (isBatteryLevelOk) {
                        page.progStep = ""
                        renderPage(pageId)
                        mHandler!!.removeCallbacks(this)
                        mRunnable = null
                    } else {
                        // Schedule the runnable to run again after a delay of 5 seconds
                        mHandler!!.postDelayed(this, 3000)
                    }
                }
            }
        }
        mHandler!!.postDelayed(mRunnable!!, 3000)
        renderPage(pageId)
    }

    private fun registerPage(pageId: String, page: Page?) {
        //Log.d(TAG, "Register page: " + pageId);
        pages[pageId] = page
    }

    //A helpful wrapper that refreshes all of our pages for major content updates
    private fun registerPages() {
        registerPage("error", pageError())
        registerPage("checkForUpdates", pageCheckForUpdates())
        registerPage("updateChecking", pageUpdateChecking())
        registerPage("updateAvailable", pageUpdateAvailable())
        registerPage("updateStarting", pageUpdateStarting())
        registerPage("updateDownloading", pageUpdateDownloading())
        registerPage("updatePaused", pageUpdatePaused())
        registerPage("updateRetryDownload", pageUpdateRetryDownload())
        registerPage("updateInstalling", pageUpdateInstalling())
        registerPage("updateInstallingPaused", pageUpdateInstallingPaused())
        registerPage("updateInstalled", pageUpdateInstalled())
        registerPage("updateInstallFailed", pageUpdateInstallFailed())
        registerPage("enrollEarlyUpdates", pageEarlyUpdates())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.page_updates)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        //Allow doing stupid things like running network operations on the main activity thread
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)

        //Prepare the environment before processing
        htmlColor = this.resources.getColor(R.color.theme_accent3, this.theme)
        htmlCurrentBuild = String.format("<p style=\"font-size: 17px;\"> %s<br />%s<br />%s </p>",
                getString(R.string.header_android_version, Build.VERSION.RELEASE),
                getString(R.string.header_build_security_patch, BuildInfoUtils.buildSecurityPatchTimestamp),
                getString(R.string.header_build_date, StringGenerator.getDateLocalizedUTC(this,
                        DateFormat.LONG, BuildInfoUtils.buildDateTimestamp)))
        activity = this
        headerIcon = findViewById(R.id.header_icon)
        headerTitle = findViewById(R.id.header_title)
        headerStatus = findViewById(R.id.header_status)
        btnPrimary = findViewById(R.id.btn_primary)
        btnSecondary = findViewById(R.id.btn_secondary)
        btnExtra = findViewById(R.id.btn_extra)
        webView = findViewById(R.id.webview)
        progressText = findViewById(R.id.progress_text)
        progressBar = findViewById(R.id.progress_bar)
        headerIcon?.setOnClickListener { v: View? -> easterEgg() }

        //Allow using shared preferences
        prefs = PreferenceManager.getDefaultSharedPreferences(activity!!)
        prefsEditor = prefs?.edit()

        //Load shared preferences
        //TODO:
//        if (update == null) {
//            val buildB64 = prefs?.getString("update", "")
//            if (buildB64 != "") {
//                try {
//                    val buildBytes = Base64.decode(buildB64, Base64.DEFAULT)
//                    build = OtaMetadata.parseFrom(buildBytes)
//                    update = Utils.parseProtoUpdate(build)
//                    updateId = update!!.downloadId!!
//                } catch (e: InvalidProtocolBufferException) {
//                    Log.e(TAG, "Failed to load saved update from prefs", e)
//                }
//            } else {
//                Log.d(TAG, "No saved update found")
//            }
//        }

        //Note that, regardless of whether the activity is open, these callbacks will still execute!
        //That means we still update pages in the background based on the update's progress
        mUpdateEngineCallback = object : UpdateEngineCallback() {
            override fun onPayloadApplicationComplete(errorCode: Int) {
                if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                    Log.d(TAG, "UpdateEngine: ERROR")
                    renderPage("updateInstallFailed")
                } else {
                    Log.d(TAG, "UpdateEngine: SUCCESS")
                    renderPage("updateInstalled")
                }
            }

            override fun onStatusUpdate(status: Int, percent: Float) {
                when (status) {
                    UpdateEngine.UpdateStatusConstants.DOWNLOADING -> {
                        installingUpdate = true
                        Log.d(TAG, "UpdateEngine: DOWNLOADING")
                        pageIdActive = prefs?.getString("pageId", "")
                        if (pageIdActive != "updateInstallingPaused") {
                            renderPageProgress("updateInstalling", Math.round(percent * 100), getString(R.string.system_update_installing_title_text))
                        }
                    }

                    UpdateEngine.UpdateStatusConstants.FINALIZING -> {
                        installingUpdate = true
                        Log.d(TAG, "UpdateEngine: FINALIZING")
                        pageIdActive = prefs?.getString("pageId", "")
                        if (pageIdActive != "updateInstallingPaused") {
                            renderPageProgress("updateInstalling", Math.round(percent * 100), getString(R.string.system_update_optimizing_apps))
                        }
                    }

                    UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT -> {
                        installingUpdate = true
                        Log.d(TAG, "UpdateEngine: UPDATED_NEED_REBOOT")
                        renderPage("updateInstalled")
                    }
                }
            }
        }
        mBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                //Log.d(TAG, "Received intent: " + intent.getAction());
                val downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID)
                if (downloadId == "") {
                    Log.e(TAG, "Received intent " + intent.action + " without downloadId?")
                    return
                }
                if (mUpdaterService == null) {
                    Log.e(TAG, "Received intent " + intent.action + " without mUpdaterService?")
                    return
                }
                update = mUpdaterService!!.updaterController!!.getUpdate(downloadId)
                if (UpdaterController.ACTION_UPDATE_STATUS == intent.action) {
                    when (update!!.status) {
                        UpdateStatus.PAUSED_ERROR -> {
                            installingUpdate = false
                            renderPage("updateRetryDownload")
                        }

                        UpdateStatus.VERIFICATION_FAILED -> {
                            installingUpdate = false
                            val page = getPage("updateRetryDownload")
                            page!!.strStatus = getString(R.string.snack_download_verification_failed)
                            renderPage("updateRetryDownload")
                        }

                        UpdateStatus.INSTALLATION_FAILED -> {
                            installingUpdate = false
                            renderPage("updateInstallFailed")
                        }

                        UpdateStatus.VERIFIED -> {
                            installingUpdate = true
                            install()
                        }

                        UpdateStatus.INSTALLED -> {
                            installingUpdate = true
                            renderPage("updateInstalled")
                        }

                        else -> {}
                    }
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS == intent.action) {
                    registerPage("updateDownloading", pageUpdateDownloading())
                    val percentage = NumberFormat.getPercentInstance().format((update!!.progress / 100f).toDouble())
                    val progStep = percentage + " • " + getString(R.string.system_update_system_update_downloading_title_text)
                    if (pageIdActive == "updateDownloading") renderPageProgress("updateDownloading", update!!.progress, progStep)
                } else if (UpdaterController.ACTION_INSTALL_PROGRESS == intent.action) {
                    registerPage("updateInstalling", pageUpdateInstalling())
                    var progStep = getString(R.string.system_update_prepare_install)
                    if (mUpdaterController!!.isInstallingABUpdate) {
                        progStep = if (update!!.finalizing) {
                            getString(R.string.system_update_optimizing_apps)
                        } else {
                            getString(R.string.system_update_installing_title_text)
                        }
                    }
                    if (pageIdActive == "updateInstalling") renderPageProgress("updateInstalling", update!!.installProgress, progStep)
                } else if (UpdaterController.Companion.ACTION_UPDATE_REMOVED == intent.action) {
                    renderPage("checkForUpdates")
                } else {
                    val page = getPage("error")
                    page!!.htmlContent = "Unknown intent: " + intent.action
                    renderPage("error")
                }
            }
        }
        wasUpdating = prefs!!.getBoolean("updating", false)
        //Log.d(TAG, "Loading wasUpdating: " + wasUpdating);
        if (!installingUpdate) {
            pageIdActive = prefs!!.getString("pageId", "")
            if (pageIdActive == "") {
                pageIdActive = "updateChecking"
            } else if (!installingUpdate) {
                pageIdActive = "updateChecking"
            } else if (!wasUpdating && pageIdActive == "updateAvailable") {
                pageIdActive = "checkForUpdates" //Check for updates on next start!
            }
        }
        Log.d(TAG, "Loading pageId $pageIdActive")
        htmlChangelog = prefs!!.getString("changelog", "").orEmpty()
        //Log.d(TAG, "Loading changelog: " + htmlChangelog);

        //Import and fill in the pages for the first time
        registerPages()

        //Bind the update engine
        try {
            mUpdateEngine = UpdateEngine()
            mUpdateEngine!!.bind(mUpdateEngineCallback)
        } catch (e: Exception) {
            Log.i(TAG, "No update engine found")
        }


        lifecycleScope.launch {
            viewModel.metadata.collect { meta ->
                if (meta != null)
                    onMetaCollected(meta)
            }

        }
    }

    private fun pageError(): Page {
        val page = Page()
        page.runnable = Runnable { Log.d(TAG, "This is the error code!") }
        page.icon = R.drawable.ic_system_update_error
        page.strTitle = "ERROR"
        page.strStatus = "An unhandled exception has occurred"
        page.btnPrimaryText = "Try again"
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> renderPage("checkForUpdates") }
        page.btnSecondaryText = "Exit"
        page.btnSecondaryClickListener = View.OnClickListener { v: View? ->
            finish()
            System.exit(1)
        }
        page.htmlColor = htmlColor
        return page
    }

    private fun pageCheckForUpdates(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_check
        page.strStatus = getString(R.string.system_update_no_update_content_text)
        page.btnPrimaryText = getString(R.string.system_update_check_now_button_text)
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> refresh() }
        page.htmlContent = htmlCurrentBuild
        page.htmlColor = htmlColor
        return page
    }

    private fun pageUpdateChecking(): Page {
        val page = Page()
        page.runnable = Runnable { renderPageProgress("updateChecking", -1, "") }
        page.icon = R.drawable.ic_system_update_loading
        page.strStatus = getString(R.string.system_update_update_checking)
        return page
    }

    private fun pageUpdateAvailable(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_dl
        page.strStatus = getString(R.string.system_update_update_available_title_text)
        page.btnPrimaryText = getString(R.string.system_update_update_now)
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> download() }
        page.htmlContent = htmlChangelog
        page.htmlColor = htmlColor
        return page
    }

    private fun pageUpdateStarting(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_dl
        page.strStatus = "Starting..."
        page.htmlContent = htmlChangelog
        page.htmlColor = htmlColor
        return page
    }

    private fun pageUpdateDownloading(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_dl
        page.strStatus = getString(R.string.system_update_installing_title_text)
        page.btnPrimaryText = getString(R.string.system_update_download_pause_button)
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> downloadPause() }
        page.btnExtraText = getString(R.string.system_update_countdown_cancel_button)
        page.btnExtraClickListener = View.OnClickListener { v: View? -> downloadCancel() }
        page.progPercent = prefs!!.getInt("progPercent", 0)
        page.progStep = prefs!!.getString("progStep", "")!!
        page.htmlContent = htmlChangelog
        page.htmlColor = htmlColor
        return page
    }

    private fun pageUpdatePaused(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_dl
        page.strStatus = getString(R.string.system_update_installing_title_text)
        page.btnPrimaryText = getString(R.string.system_update_resume_button_text)
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> downloadResume() }
        page.btnExtraText = getString(R.string.system_update_countdown_cancel_button)
        page.btnExtraClickListener = View.OnClickListener { v: View? -> downloadCancel() }
        page.htmlContent = htmlChangelog
        page.htmlColor = htmlColor
        val pageDownload = getPage("updateDownloading")
        val percentage = NumberFormat.getPercentInstance().format((pageDownload!!.progPercent / 100f).toDouble())
        page.progStep = percentage + " • " + getString(R.string.system_update_download_paused_title_text)
        page.progPercent = pageDownload.progPercent
        return page
    }

    private fun pageUpdateRetryDownload(): Page? {
        val page = getPage("updateDownloading")
        page!!.strStatus = getString(R.string.system_update_download_error_notification_title)
        page.btnPrimaryText = getString(R.string.system_update_download_retry_button_text)
        page.icon = R.drawable.ic_system_update_error
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> download() }
        val percentage = NumberFormat.getPercentInstance().format((page.progPercent / 100f).toDouble())
        page.progStep = percentage + " • " + getString(R.string.system_update_download_retry_button_text)
        page.progPercent = page.progPercent
        return page
    }

    private fun pageUpdateInstalling(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_dl
        page.strStatus = getString(R.string.system_update_installing_title_text)
        page.progPercent = prefs!!.getInt("progPercent", 0)
        page.progStep = prefs!!.getString("progStep", "")!!
        if (Utils.isABDevice) {
            page.btnExtraText = getString(R.string.system_update_download_pause_button)
            page.btnExtraClickListener = View.OnClickListener { v: View? -> installPause() }
        }
        page.htmlContent = htmlChangelog
        page.htmlColor = htmlColor
        return page
    }

    private fun pageUpdateInstallingPaused(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_dl
        page.progPercent = prefs!!.getInt("progPercent", 0)
        page.progStep = getString(R.string.system_update_notification_title_update_paused)
        page.strStatus = getString(R.string.system_update_installing_title_text)
        page.btnExtraText = getString(R.string.system_update_download_resume_button)
        page.btnExtraClickListener = View.OnClickListener { v: View? -> installResume() }
        page.htmlContent = htmlChangelog
        page.htmlColor = htmlColor
        return page
    }

    private fun pageUpdateInstalled(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_dl
        page.strStatus = getString(R.string.system_update_notification_message_pending_reboot_finish_updating)
        page.btnPrimaryText = getString(R.string.system_update_restart_now)
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> reboot() }
        page.htmlContent = htmlChangelog
        page.htmlColor = htmlColor
        return page
    }

    private fun pageUpdateInstallFailed(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_failure
        page.strStatus = getString(R.string.system_update_install_failed_title_text)
        page.btnPrimaryText = getString(R.string.system_update_update_failed)
        page.btnPrimaryClickListener = View.OnClickListener { v: View? -> renderPage("checkForUpdates") }
        page.htmlContent = getString(R.string.system_update_activity_attempt_install_later_text)
        page.htmlColor = htmlColor
        return page
    }

    private fun pageEarlyUpdates(): Page {
        val page = Page()
        page.icon = R.drawable.ic_system_update_enroll
        page.strStatus = getString(R.string.system_update_enroll_early_release)
        page.btnPrimaryText = getString(R.string.system_update_enroll_early_release_accept_button)
        page.btnPrimaryClickListener = View.OnClickListener { v: View? ->
            prefsEditor!!.putInt("earlyUpdates", 1).apply()
            renderPage("checkForUpdates")
        }
        page.btnExtraText = getString(R.string.system_update_enroll_early_release_reject_button)
        page.btnExtraClickListener = View.OnClickListener { v: View? ->
            prefsEditor!!.putInt("earlyUpdates", 0).apply()
            renderPage("checkForUpdates")
        }
        page.htmlContent = getString(R.string.system_update_enroll_early_release_terms)
        page.htmlColor = htmlColor
        return page
    }

    private fun removeDownloads() {
        try {
            val otaPackageDir = File(getString(R.string.download_path))
            if (otaPackageDir.isDirectory && !installingUpdate && !wasUpdating) {
                val files = otaPackageDir.listFiles()
                for (file in files) {
                    if (file.isFile) {
                        file.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while removing updates: $e")
            exception = e
        }
    }

    private fun refresh() {
        viewModel.refresh(Utils.getServerURL(this))
    }

    private val isBatteryLevelOk: Boolean
        get() {
            val intent = activity!!.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (!intent!!.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)) {
                return true
            }
            val percent = Math.round(100f * intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) / intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val powerManager = activity!!.getSystemService(POWER_SERVICE) as PowerManager
            val isBatterySaverOn = powerManager.isPowerSaveMode
            val required = if (isCharging) 40 else 50
            return !isBatterySaverOn && percent >= required
        }

    private fun download() {
        if (isBatteryLevelOk) {
            //Reset the page entirely
            registerPage("updateDownloading", pageUpdateDownloading())
            renderPage("updateDownloading")
            renderPageProgress("updateDownloading", -1, "")
            Log.d(TAG, "Starting download!")
            setUpdating(true)
            mUpdaterController!!.startDownload(updateId)
        } else {
            renderPageBatteryCheck("updateAvailable", getString(R.string.system_update_battery_low))
        }
    }

    private fun downloadCancel() {
        Log.d(TAG, "Cancelling download!")
        setUpdating(false)
        mUpdaterController!!.pauseDownload(updateId)
        mUpdaterController!!.deleteUpdate(updateId)
        prefsEditor!!.putString("pageId", "").apply()
        prefsEditor!!.putInt("progPercent", 0).apply()
        prefsEditor!!.putString("progStep", "").apply()
        renderPage("updateAvailable")
        refresh()
    }

    private fun downloadPause() {
        Log.d(TAG, "Pausing download!")
        setUpdating(true)
        registerPage("updatePaused", pageUpdatePaused())
        progressBar!!.isIndeterminate = true
        renderPage("updatePaused")
        mUpdaterController!!.pauseDownload(updateId)
    }

    private fun downloadResume() {
        Log.d(TAG, "Resuming download!")
        setUpdating(true)
        registerPage("updateDownloading", pageUpdateDownloading())
        progressBar!!.isIndeterminate = false
        renderPage("updateDownloading")
        mUpdaterController!!.resumeDownload(updateId)
    }

    private fun install() {
        Log.d(TAG, "Installing update!")
        setUpdating(true)
        renderPage("updateInstalling")
        Utils.triggerUpdate(this, updateId)
    }

    private fun installPause() {
        Log.d(TAG, "Pausing update installation!")
        setUpdating(true)
        progressBar!!.isIndeterminate = true
        renderPage("updateInstallingPaused")
        mUpdateEngine!!.suspend()
    }

    private fun installResume() {
        Log.d(TAG, "Resuming update installation!")
        setUpdating(true)
        progressBar!!.isIndeterminate = false
        renderPage("updateInstalling")
        mUpdateEngine!!.resume()
    }

    private fun reboot() {
        Log.d(TAG, "Rebooting device!")
        setUpdating(false)
        val pm = this.getSystemService(PowerManager::class.java)
        pm.reboot(null)
    }

    private fun setUpdating(updating: Boolean) {
        wasUpdating = updating
        //Log.d(TAG, "Set updating: " + updating);
        prefsEditor!!.putBoolean("updating", updating).apply()
    }

    public override fun onStart() {
        super.onStart()
        val intent = Intent(this, UpdaterService::class.java)
        startService(intent)
        bindService(intent, mConnection, BIND_AUTO_CREATE)
        val intentFilter = IntentFilter()
        intentFilter.addAction(UpdaterController.Companion.ACTION_UPDATE_STATUS)
        intentFilter.addAction(UpdaterController.Companion.ACTION_DOWNLOAD_PROGRESS)
        intentFilter.addAction(UpdaterController.Companion.ACTION_INSTALL_PROGRESS)
        intentFilter.addAction(UpdaterController.Companion.ACTION_UPDATE_REMOVED)
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver!!, intentFilter)
    }

    public override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver!!)
        if (mUpdaterService != null) {
            unbindService(mConnection)
        }

        //Log.d(TAG, "Committing preferences before close");
        prefsEditor!!.apply() //Make sure we commit preferences no matter what
        super.onDestroy()
    }

    private val mConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName,
                                        service: IBinder) {
            val binder = service as LocalBinder
            mUpdaterService = binder.service
            mUpdaterController = mUpdaterService!!.updaterController
            if (mUpdaterController != null && update != null) {
                mUpdaterController!!.addUpdate(update!!)
                val updatesOnline: MutableList<String?> = ArrayList()
                updatesOnline.add(update!!.downloadId)
                mUpdaterController!!.setUpdatesAvailableOnline(updatesOnline, true)
            }
            if (!installingUpdate && pageIdActive == "updateChecking") {
                Log.d(TAG, "Running automatic update check...")
                refresh()
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            mUpdaterController = null
            mUpdaterService = null
        }
    }
    private var easterEggSteps = 0
    private val handler = Handler()
    private val resetEasterEggSteps = Runnable { easterEggSteps = 0 }
    private fun easterEgg() {
        if (!wasUpdating) {
            easterEggSteps++
            handler.removeCallbacks(resetEasterEggSteps)
            if (easterEggSteps == 7) {
                renderPage("enrollEarlyUpdates")
                easterEggSteps = 0
            } else {
                handler.postDelayed(resetEasterEggSteps, 1000)
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun onMetaCollected(build: OtaMeta) {
        if (mUpdaterController == null) {
            Log.e(TAG, "mUpdaterController is null during update check")
            renderPage("checkForUpdates")
            return
        }
        Log.d(TAG, "Checking for updates!")
        setUpdating(false)
        updateCheck = true
        renderPage("updateChecking")
        Thread(Runnable {
            try {
                Thread.sleep(1500)

                // Remove all current downloads
                removeDownloads()
//                var androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
//                val earlyUpdates = prefs!!.getInt("earlyUpdates", 0)
//                if (earlyUpdates <= 0) {
//                    androidId = "0"
//                }
//                val buildTimestamp = SystemProperties.get("ro.build.date.utc").toLong()
//                var buildIncremental: Long = 0
//                try {
//                    buildIncremental = SystemProperties.get("ro.build.version.incremental").toLong()
//                } catch (e: Exception) {
//                    Log.d(TAG, "Failed to parse ro.build.version.incremental, is this an official build?")
//                }
//                val request = DeviceState(
//                        device = SystemProperties.get("ro.product.vendor.device"),
//                        build = SystemProperties.get("ro.product.vendor.device"),
//                        buildIncremental = buildIncremental,
//                        timestamp = buildTimestamp,
//                        sdkLevel = SystemProperties.get("ro.build.version.sdk"),
//                        securityPatchLevel = SystemProperties.get("ro.build.version.security_patch"),
//                        hwId = androidId
//                )

//                if (BuildConfig.DEBUG) {
//                    request.clearDevice();
//                    request.clearBuild();
//                    request.clearTimestamp();
//                    request.addDevice("alioth");
//                    request.addBuild("Redmi/alioth/alioth:13/TQ1A.230205.002/23020840:user/release-keys");
//                    request.setTimestamp(1665584742);
//                }
                //                urlConnection.setDoOutput(true);
//                urlConnection.setDoInput(true);
//                urlConnection.setRequestMethod("POST");
//                req.writeTo(urlConnection.getOutputStream());

//                InputStream in = new BufferedInputStream(urlConnection.getInputStream());
//                byte[] buildBytes = in.readAllBytes();
//                build = OtaMetadata.parseFrom(buildBytes);
//                Log.d(TAG, "refresh: " + );

                try {
                    Utils.parseProtoUpdate(build).let { upd ->
                        update = upd
                        updateId = upd.downloadId
                        mUpdaterController?.let { updateController ->
                            updateController.addUpdate(upd)
                            val updatesOnline: MutableList<String?> = ArrayList()
                            updatesOnline.add(upd.downloadId)
                            updateController.setUpdatesAvailableOnline(updatesOnline, true)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error while parsing updates proto: $e")
                    exception = e
                }
            } catch (e: Exception) {
                exception = e
            }
            updateCheck = false
            if (exception != null) {
                val page = getPage("checkForUpdates")
                page!!.strStatus = getString(R.string.system_update_no_update_content_text)
                renderPage("checkForUpdates")
                return@Runnable
            }
            if (update != null) {
                try {
                    val urlCL = update!!.changelogUrl
                    if (!urlCL.isEmpty()) {
                        val url = URL(urlCL)
                        val `in` = BufferedReader(InputStreamReader(url.openStream()))
                        val stringBuilder = StringBuilder()
                        var input: String?
                        while (`in`.readLine().also { input = it } != null) {
                            stringBuilder.append(input)
                        }
                        `in`.close()
                        htmlChangelog = stringBuilder.toString()
                    } else {
                        htmlChangelog = ""
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to get changelog", e)
                    htmlChangelog = ""
                }
                htmlChangelog += "<br /><br />"
                htmlChangelog += "Update size: " + Formatter.formatShortFileSize(activity, update!!.fileSize)
                Log.d(TAG, "Saving changelog")
                prefsEditor!!.putString("changelog", htmlChangelog).apply()
                setUpdating(true)
                registerPages() //Reload everything that might display the changelog
                renderPage("updateAvailable")
            } else {
                renderPage("checkForUpdates")
            }
        }).start()
    }

    companion object {
        //Android flags
        const val TAG = "Updates"
    }
}