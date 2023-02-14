package com.android.updater;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.icu.text.DateFormat;
import android.icu.text.NumberFormat;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.provider.Settings;
import android.text.format.Formatter;
import android.util.Base64;
import android.util.Log;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import com.google.protobuf.InvalidProtocolBufferException;

import com.android.updater.controller.UpdaterController;
import com.android.updater.controller.UpdaterService;
import com.android.updater.misc.BuildInfoUtils;
import com.android.updater.misc.StringGenerator;
import com.android.updater.misc.Utils;
import com.android.updater.model.UpdateInfo;
import com.android.updater.model.UpdateStatus;
import com.android.updater.protos.DeviceState;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

public class UpdatesActivity extends AppCompatActivity {
    //Android flags
    public static final String TAG = "Updates";
    private Exception exception;
    private UpdatesActivity activity;
    private SharedPreferences prefs;
    private SharedPreferences.Editor prefsEditor;

    //The map of the hour, "pageId" = Page
    private HashMap<String, Page> pages = new HashMap<String, Page>();

    //Layout to render the pages to
    public String pageIdActive = "";
    public ImageView headerIcon;
    public TextView headerTitle;
    public TextView headerStatus;
    public Button btnPrimary;
    public Button btnSecondary;
    public Button btnExtra;
    public TextView progressText;
    public ProgressBar progressBar;
    public WebView webView;
    public String htmlContentLast = "";

    //Special details
    private UpdateInfo update;
    private com.android.updater.protos.OtaMetadata build;
    private String updateId = "";
    private Boolean wasUpdating = false;
    private Boolean updateCheck = false;
    private Boolean installingUpdate = false;
    private int htmlColor = 0;
    private String htmlCurrentBuild = "";
    private String htmlChangelog = "";

    //Android services
    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;
    private UpdaterController mUpdaterController;
    private UpdateEngine mUpdateEngine;
    private UpdateEngineCallback mUpdateEngineCallback;

    private class PageHandler extends AsyncTask<Void, Void, String> {
        @Override
        protected String doInBackground(Void... voids) {
            if (!Objects.equals(updateId, "") && update != null) {
                if (mUpdaterController != null) {
                    if (update.getStatus() == UpdateStatus.STARTING) {
                        return "updateStarting";
                    } else if (mUpdaterController.isDownloading(updateId) || update.getStatus() == UpdateStatus.DOWNLOADING) {
                        return "updateDownloading";
                    } else if (mUpdaterController.isVerifyingUpdate(updateId) || mUpdaterController.isInstallingUpdate(updateId) || update.getStatus() == UpdateStatus.INSTALLING) {
                        return "updateInstalling";
                    } else if (mUpdaterController.isWaitingForReboot(updateId)) {
                        return "updateInstalled";
                    }
                } else if (wasUpdating) {
                    return ""; //We still have the update object and we're still updating, let the logic take care of what's next
                }
            }

            new Thread(() -> {
                try {
                    Thread.sleep(200);
                    if (!installingUpdate && pageIdActive.isEmpty()) {
                        Log.d(TAG, "PageHandler: Update is null");
                        pageIdActive = "updateChecking";
                    }
                } catch (Exception e) {
                    pageIdActive = "updateChecking";
                }
            });
            return pageIdActive;
        }

        @Override
        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            if (!Objects.equals(result, ""))
                renderPage(result);
        }
    }

    public Page getPage(String pageId) {
        //Log.d(TAG, "Get page: " + pageId);
        return pages.get(pageId);
    }

    public void renderPage(String pageId) {
        if (mUpdaterController != null && !installingUpdate) {
            if (!(mUpdaterController.isDownloading(updateId) ||
                    mUpdaterController.isVerifyingUpdate(updateId) ||
                    mUpdaterController.isInstallingUpdate(updateId) ||
                    mUpdaterController.isWaitingForReboot(updateId))) {

                //Log.d(TAG, "UpdateController not updating checking current page: " + pageId);
                if (pageId.equals("updateInstalling") ||
                        pageId.equals("updateInstallingPaused") ||
                        pageId.equals("updateInstalled")) {
                    Log.d(TAG, "Invalid state detected, returning to initial page");
                    prefsEditor.clear().apply(); // Clear the preferences editor
                    pageId = "checkForUpdates"; // Set the page id to "updateChecking"
                }
            }
        }

        //Log.d(TAG, "Render page: " + pageId);

        if (!Objects.equals(pageIdActive, "") && !Objects.equals(pageIdActive, pageId)) {
            Page pageLast = getPage(pageIdActive);
            if (pageLast != null) {
                pageLast.runnableRan = false;
            }
        }

        Page page = getPage(pageId);
        if (page == null) {
            page = getPage("error");
            page.htmlContent = "Unknown pageId: " + pageId;
        }

        pageIdActive = pageId;
        if (!(pageIdActive.equals("error") ||
                pageIdActive.equals("checkForUpdates") ||
                pageIdActive.equals("updateAvailable") ||
                pageIdActive.equals("updateChecking") ||
                pageIdActive.equals("enrollEarlyUpdates"))) {
            //Log.d(TAG, "Saving pageId " + pageIdActive);
            prefsEditor.putString("pageId", pageIdActive).apply();
            prefsEditor.apply();
        }

        page.render(this);
        if (!page.runnableRan) {
            page.runnableRan = true;
            Thread thread = new Thread(page.runnable);
            thread.start();
        }
    }

    public void renderPageProgress(String pageId, int progress, String progressStep) {
        Page page = getPage(pageId);

        if (progress < 0) {
            progress = 1;
            progressBar.setIndeterminate(true);
        } else {
            progressBar.setIndeterminate(false);
        }
        page.progPercent = progress;
        page.progStep = progressStep;

        prefsEditor.putInt("progPercent", page.progPercent);
        prefsEditor.putString("progStep", page.progStep);
        prefsEditor.apply();

        renderPage(pageId);
    }

    public void renderPageCustom(String pageId, Page page) {
        registerPage(pageId, page);
        renderPage(pageId);
    }
    private void registerPage(String pageId, Page page) {
        //Log.d(TAG, "Register page: " + pageId);
        pages.put(pageId, page);
    }
    //A helpful wrapper that refreshes all of our pages for major content updates
    private void registerPages() {
        registerPage("error", pageError());
        registerPage("checkForUpdates", pageCheckForUpdates());
        registerPage("updateChecking", pageUpdateChecking());
        registerPage("updateAvailable", pageUpdateAvailable());
        registerPage("updateStarting", pageUpdateStarting());
        registerPage("updateDownloading", pageUpdateDownloading());
        registerPage("updatePaused", pageUpdatePaused());
        registerPage("updateRetryDownload", pageUpdateRetryDownload());
        registerPage("updateInstalling", pageUpdateInstalling());
        registerPage("updateInstallingPaused", pageUpdateInstallingPaused());
        registerPage("updateInstalled", pageUpdateInstalled());
        registerPage("updateInstallFailed", pageUpdateInstallFailed());
        registerPage("enrollEarlyUpdates", pageEarlyUpdates());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.page_updates);

        //Allow doing stupid things like running network operations on the main activity thread
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        //Prepare the environment before processing
        htmlColor = this.getResources().getColor(R.color.theme_accent3, this.getTheme());
        htmlCurrentBuild = String.format("<p style=\"font-size: 17px;\"> %s<br />%s<br />%s </p>",
                getString(R.string.header_android_version, Build.VERSION.RELEASE),
                getString(R.string.header_build_security_patch, BuildInfoUtils.getBuildSecurityPatchTimestamp()),
                getString(R.string.header_build_date, StringGenerator.getDateLocalizedUTC(this,
                        DateFormat.LONG, BuildInfoUtils.getBuildDateTimestamp())));

        activity = this;
        headerIcon = findViewById(R.id.header_icon);
        headerTitle = findViewById(R.id.header_title);
        headerStatus = findViewById(R.id.header_status);
        btnPrimary = findViewById(R.id.btn_primary);
        btnSecondary = findViewById(R.id.btn_secondary);
        btnExtra = findViewById(R.id.btn_extra);
        webView = findViewById(R.id.webview);
        progressText = findViewById(R.id.progress_text);
        progressBar = findViewById(R.id.progress_bar);

        headerIcon.setOnClickListener(v -> {
            easterEgg();
        });

        //Allow using shared preferences
        prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        prefsEditor = prefs.edit();

        //Load shared preferences
        if (update == null) {
            String buildB64 = prefs.getString("update", "");
            if (!buildB64.equals("")) {
                try {
                    byte[] buildBytes = Base64.decode(buildB64, Base64.DEFAULT);
                    build = com.android.updater.protos.OtaMetadata.parseFrom(buildBytes);
                    update = Utils.parseProtoUpdate(build);
                    updateId = update.getDownloadId();
                } catch (InvalidProtocolBufferException e) {
                    Log.e(TAG, "Failed to load saved update from prefs", e);
                }
            } else {
                Log.d(TAG, "No saved update found");
            }
        }

        //Note that, regardless of whether the activity is open, these callbacks will still execute!
        //That means we still update pages in the background based on the update's progress
        mUpdateEngineCallback = new UpdateEngineCallback() {
            @Override
            public void onPayloadApplicationComplete(int errorCode) {
                if (errorCode != UpdateEngine.ErrorCodeConstants.SUCCESS) {
                    Log.d(TAG, "UpdateEngine: ERROR");
                    renderPage("updateInstallFailed");
                } else {
                    Log.d(TAG, "UpdateEngine: SUCCESS");
                    renderPage("updateInstalled");
                }
            }

            @Override
            public void onStatusUpdate(int status, float percent) {
                switch (status) {
                    case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                        installingUpdate = true;
                        Log.d(TAG, "UpdateEngine: DOWNLOADING");
                        pageIdActive = prefs.getString("pageId", "");
                        if (!pageIdActive.equals("updateInstallingPaused")) {
                            renderPageProgress("updateInstalling", Math.round(percent * 100), getString(R.string.system_update_installing_title_text));
                        }
                        break;
                    case UpdateEngine.UpdateStatusConstants.FINALIZING:
                        installingUpdate = true;
                        Log.d(TAG, "UpdateEngine: FINALIZING");
                        pageIdActive = prefs.getString("pageId", "");
                        if (!pageIdActive.equals("updateInstallingPaused")) {
                            renderPageProgress("updateInstalling", Math.round(percent * 100), getString(R.string.system_update_optimizing_apps));
                        }
                        break;
                    case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT:
                        installingUpdate = true;
                        Log.d(TAG, "UpdateEngine: UPDATED_NEED_REBOOT");
                        renderPage("updateInstalled");
                        break;
                }
            }
        };

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                //Log.d(TAG, "Received intent: " + intent.getAction());

                String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                if (Objects.equals(downloadId, "")) {
                    Log.e(TAG, "Received intent " + intent.getAction() + " without downloadId?");
                    return;
                }
                if (mUpdaterService == null) {
                    Log.e(TAG, "Received intent " + intent.getAction() + " without mUpdaterService?");
                    return;
                }
                update = mUpdaterService.getUpdaterController().getUpdate(downloadId);

                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    switch (update.getStatus()) {
                        case PAUSED_ERROR:
                            installingUpdate = false;
                            renderPage("updateRetryDownload");
                            break;
                        case VERIFICATION_FAILED:
                            installingUpdate = false;
                            Page page = getPage("updateRetryDownload");
                            page.strStatus = getString(R.string.snack_download_verification_failed);
                            renderPage("updateRetryDownload");
                            break;
                        case INSTALLATION_FAILED:
                            installingUpdate = false;
                            renderPage("updateInstallFailed");
                            break;
                        case VERIFIED:
                            installingUpdate = true;
                            install();
                            break;
                        case INSTALLED:
                            installingUpdate = true;
                            renderPage("updateInstalled");
                            break;
                    }
                } else if (UpdaterController.ACTION_DOWNLOAD_PROGRESS.equals(intent.getAction())) {
                    registerPage("updateDownloading", pageUpdateDownloading());
                    String percentage = NumberFormat.getPercentInstance().format(update.getProgress() / 100.f);
                    String progStep = percentage + " • " + getString(R.string.system_update_system_update_downloading_title_text);
                    if (Objects.equals(pageIdActive, "updateDownloading"))
                        renderPageProgress("updateDownloading", update.getProgress(), progStep);
                } else if (UpdaterController.ACTION_INSTALL_PROGRESS.equals(intent.getAction())) {
                    registerPage("updateInstalling", pageUpdateInstalling());
                    String progStep = getString(R.string.system_update_prepare_install);
                    if (mUpdaterController.isInstallingABUpdate()) {
                        if (update.getFinalizing()) {
                            progStep = getString(R.string.system_update_optimizing_apps);
                        } else {
                            progStep = getString(R.string.system_update_installing_title_text);
                        }
                    }

                    if (Objects.equals(pageIdActive, "updateInstalling"))
                        renderPageProgress("updateInstalling", update.getInstallProgress(), progStep);
                } else if (UpdaterController.ACTION_UPDATE_REMOVED.equals(intent.getAction())) {
                    renderPage("checkForUpdates");
                } else {
                    Page page = getPage("error");
                    page.htmlContent = "Unknown intent: " + intent.getAction();
                    renderPage("error");
                }
            }
        };

        wasUpdating = prefs.getBoolean("updating", false);
        //Log.d(TAG, "Loading wasUpdating: " + wasUpdating);
        if (!installingUpdate) {
            pageIdActive = prefs.getString("pageId", "updateChecking");
            if (pageIdActive.equals("")) {
                pageIdActive = "updateChecking";
            } else if (!wasUpdating && pageIdActive.equals("updateAvailable")) {
                pageIdActive = "checkForUpdates"; //Check for updates on next start!
            }
        }
        Log.d(TAG, "Loading pageId " + pageIdActive);
        htmlChangelog = prefs.getString("changelog", "");
        //Log.d(TAG, "Loading changelog: " + htmlChangelog);

        //Import and fill in the pages for the first time
        registerPages();

        //Load the initial page
        new PageHandler().execute();

        //Bind the update engine
        try {
            mUpdateEngine = new UpdateEngine();
            mUpdateEngine.bind(mUpdateEngineCallback);
        } catch (Exception e) {
            Log.i(TAG, "No update engine found");
        }
    }

    private Page pageError() {
        Page page = new Page();
        page.runnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "This is the error code!");
            }
        };
        page.icon = R.drawable.ic_system_update_error;
        page.strTitle = "ERROR";
        page.strStatus = "An unhandled exception has occurred";
        page.btnPrimaryText = "Try again";
        page.btnPrimaryClickListener = v -> {
            renderPage("checkForUpdates");
        };
        page.btnSecondaryText = "Exit";
        page.btnSecondaryClickListener = v -> {
            this.finish();
            System.exit(1);
        };
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageCheckForUpdates() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_check;
        page.strStatus = getString(R.string.system_update_no_update_content_text);
        page.btnPrimaryText = getString(R.string.system_update_check_now_button_text);
        page.btnPrimaryClickListener = v -> {
            refresh();
        };
        page.htmlContent = htmlCurrentBuild;
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageUpdateChecking() {
        Page page = new Page();
        page.runnable = new Runnable() {
            @Override
            public void run() {
                renderPageProgress("updateChecking", -1, "");
            }
        };
        page.icon = R.drawable.ic_system_update_loading;
        page.strStatus = getString(R.string.system_update_update_checking);
        return page;
    }

    private Page pageUpdateAvailable() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_dl;
        page.strStatus = getString(R.string.system_update_update_available_title_text);
        page.btnPrimaryText = getString(R.string.system_update_update_now);
        page.btnPrimaryClickListener = v -> {
            download();
        };
        page.htmlContent = htmlChangelog;
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageUpdateStarting() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_dl;
        page.strStatus = "Starting...";
        page.htmlContent = htmlChangelog;
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageUpdateDownloading() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_dl;
        page.strStatus = getString(R.string.system_update_installing_title_text);
        page.btnPrimaryText = getString(R.string.system_update_download_pause_button);
        page.btnPrimaryClickListener = v -> {
            downloadPause();
        };
        page.btnExtraText = getString(R.string.system_update_countdown_cancel_button);
        page.btnExtraClickListener = v -> {
            downloadCancel();
        };
        page.progPercent = prefs.getInt("progPercent", 0);
        page.progStep = prefs.getString("progStep", "");
        page.htmlContent = htmlChangelog;
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageUpdatePaused() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_dl;
        page.strStatus = getString(R.string.system_update_installing_title_text);
        page.btnPrimaryText = getString(R.string.system_update_resume_button_text);
        page.btnPrimaryClickListener = v -> {
            downloadResume();
        };
        page.btnExtraText = getString(R.string.system_update_countdown_cancel_button);
        page.btnExtraClickListener = v -> {
            downloadCancel();
        };
        page.htmlContent = htmlChangelog;
        page.htmlColor = htmlColor;

        Page pageDownload = getPage("updateDownloading");
        String percentage = NumberFormat.getPercentInstance().format(pageDownload.progPercent / 100.f);
        page.progStep = percentage + " • " + getString(R.string.system_update_download_paused_title_text);
        page.progPercent = pageDownload.progPercent;

        return page;
    }

    private Page pageUpdateRetryDownload() {
        Page page = getPage("updateDownloading");
        page.strStatus = getString(R.string.system_update_download_error_notification_title);
        page.btnPrimaryText = getString(R.string.system_update_download_retry_button_text);
        page.icon = R.drawable.ic_system_update_error;
        page.btnPrimaryClickListener = v -> {
            download();
        };

        String percentage = NumberFormat.getPercentInstance().format(page.progPercent / 100.f);
        page.progStep = percentage + " • " + getString(R.string.system_update_download_retry_button_text);
        page.progPercent = page.progPercent;

        return page;
    }

    private Page pageUpdateInstalling() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_dl;
        page.strStatus = getString(R.string.system_update_installing_title_text);
        page.progPercent = prefs.getInt("progPercent", 0);
        page.progStep = prefs.getString("progStep", "");
        if (Utils.isABDevice()) {
            page.btnExtraText = getString(R.string.system_update_download_pause_button);
            page.btnExtraClickListener = v -> {
                installPause();
            };
        }
        page.htmlContent = htmlChangelog;
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageUpdateInstallingPaused() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_dl;
        page.progPercent = prefs.getInt("progPercent", 0);
        page.progStep = getString(R.string.system_update_notification_title_update_paused);
        page.strStatus = getString(R.string.system_update_installing_title_text);
        page.btnExtraText = getString(R.string.system_update_download_resume_button);
        page.btnExtraClickListener = v -> {
            installResume();
        };
        page.htmlContent = htmlChangelog;
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageUpdateInstalled() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_dl;
        page.strStatus = getString(R.string.system_update_notification_message_pending_reboot_finish_updating);
        page.btnPrimaryText = getString(R.string.system_update_restart_now);
        page.btnPrimaryClickListener = v -> {
            reboot();
        };
        page.htmlContent = htmlChangelog;
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageUpdateInstallFailed() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_failure;
        page.strStatus = getString(R.string.system_update_install_failed_title_text);
        page.btnPrimaryText = getString(R.string.system_update_update_failed);
        page.btnPrimaryClickListener = v -> {
            renderPage("checkForUpdates");
        };
        page.htmlContent = getString(R.string.system_update_activity_attempt_install_later_text);
        page.htmlColor = htmlColor;
        return page;
    }

    private Page pageEarlyUpdates() {
        Page page = new Page();
        page.icon = R.drawable.ic_system_update_enroll;
        page.strStatus = getString(R.string.system_update_enroll_early_release);
        page.btnPrimaryText = getString(R.string.system_update_enroll_early_release_accept_button);
        page.btnPrimaryClickListener = v -> {
            prefsEditor.putInt("earlyUpdates", 1).apply();
            prefsEditor.apply();
            renderPage("checkForUpdates");
        };
        page.btnExtraText = getString(R.string.system_update_enroll_early_release_reject_button);
        page.btnExtraClickListener = v -> {
            prefsEditor.putInt("earlyUpdates", 0).apply();
            prefsEditor.apply();
            renderPage("checkForUpdates");
        };
        page.htmlContent = getString(R.string.system_update_enroll_early_release_terms);
        page.htmlColor = htmlColor;
        return page;
    }

    private void refresh() {
        if (mUpdaterController == null) {
            Log.e(TAG, "mUpdaterController is null during update check");
            renderPage("checkForUpdates");
            return;
        }

        Log.d(TAG, "Checking for updates!");
        setUpdating(false);
        updateCheck = true;
        renderPage("updateChecking");

        new Thread(() -> {
            try  {
                Thread.sleep(1500);

                String urlOTA = Utils.getServerURL(this);
                URL url = new URL(urlOTA);

                String android_id = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ANDROID_ID);
                if (prefs.getInt("earlyUpdates", 0) <= 0)
                    android_id = "0";

                long buildTimestamp = Long.parseLong(SystemProperties.get("ro.build.date.utc"));
                long buildIncremental;
                try {
                    buildIncremental = Long.parseLong(SystemProperties.get("ro.build.version.incremental"));
                } catch (Exception e) {
                    Log.d(TAG, "Failed to parse ro.build.version.incremental, is this an official build?");
                    buildIncremental = 0;
                    exception = null;
                }

                DeviceState.Builder request = DeviceState.newBuilder();
                request.addDevice(SystemProperties.get("ro.product.vendor.device"));
                request.addBuild(SystemProperties.get("ro.vendor.build.fingerprint"));
                request.setBuildIncremental(buildIncremental);
                request.setTimestamp(buildTimestamp);
                request.setSdkLevel(SystemProperties.get("ro.build.version.sdk"));
                request.setSecurityPatchLevel(SystemProperties.get("ro.build.version.security_patch"));
                request.setHwId(android_id);

                Boolean testing = true;
                if (BuildConfig.DEBUG && testing) {
                    request.clearDevice();
                    request.clearBuild();
                    request.clearTimestamp();
                    request.addDevice("alioth");
                    request.addBuild("Redmi/alioth/alioth:13/TQ1A.230205.002/23020840:user/release-keys");
                    request.setTimestamp(1665584742);
                }

                DeviceState req = request.build();

                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setDoOutput(true);
                urlConnection.setDoInput(true);
                urlConnection.setRequestMethod("POST");
                try {
                    req.writeTo(this.openFileOutput("config.txt", Context.MODE_PRIVATE));
                }
                catch (IOException e) {
                    Log.e("Exception", "File write failed: " + e.toString());
                }
                req.writeTo(urlConnection.getOutputStream());

                InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                byte[] buildBytes = in.readAllBytes();
                build = com.android.updater.protos.OtaMetadata.parseFrom(buildBytes);

                try {
                    if (update != null) {
                        File oldFile = update.getFile();
                        if (oldFile != null) {
                            oldFile.delete();
                        }
                    }

                    update = Utils.parseProtoUpdate(build);
                    updateId = update.getDownloadId();

                    if (mUpdaterController != null) {
                        mUpdaterController.addUpdate(update);
                        List<String> updatesOnline = new ArrayList<>();
                        updatesOnline.add(update.getDownloadId());
                        mUpdaterController.setUpdatesAvailableOnline(updatesOnline, true);
                    }

                    Log.d(TAG, "Saving update for " + updateId);
                    prefsEditor.putString("update", Base64.encodeToString(buildBytes, Base64.DEFAULT)).apply();
                    prefsEditor.apply();
                } catch (Exception e) {
                    Log.e(TAG, "Error while parsing updates proto: " + e);
                    exception = e;
                }
            } catch (Exception e) {
                //Log.e(TAG, "Error while downloading updates proto: " + e);
                exception = e;
            }

            updateCheck = false;

            if (exception != null) {
                Page page = getPage("checkForUpdates");
                page.strStatus = getString(R.string.system_update_no_update_content_text);
                renderPage("checkForUpdates");
                return;
            }

            if (!Objects.equals(updateId, "") && update != null) {
                try {
                    String urlCL = update.getChangelogUrl();
                    URL url = new URL(urlCL);
                    BufferedReader in = new BufferedReader(new InputStreamReader(url.openStream()));
                    String input;
                    StringBuffer stringBuffer = new StringBuffer();
                    while ((input = in.readLine()) != null)
                        stringBuffer.append(input);
                    in.close();
                    htmlChangelog = stringBuffer.toString();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to get changelog!");
                    htmlChangelog = "";
                }

                htmlChangelog += "<br /><br />";
                htmlChangelog += "Update size: " + Formatter.formatShortFileSize(activity, update.getFileSize());

                Log.d(TAG, "Saving changelog");
                prefsEditor.putString("changelog", htmlChangelog).apply();
                prefsEditor.apply();

                setUpdating(true);

                registerPages(); //Reload everything that might display the changelog

                renderPage("updateAvailable");
            } else {
                renderPage("checkForUpdates");
            }
        }).start();
    }

    private void download() {
        if (update != null) {
            File oldFile = update.getFile();
            if (oldFile != null) {
                oldFile.delete();
            }
        }

        //Reset the page entirely
        registerPage("updateDownloading", pageUpdateDownloading());
        renderPage("updateDownloading");
        renderPageProgress("updateDownloading", -1, "");

        Log.d(TAG, "Starting download!");
        setUpdating(true);

        mUpdaterController.startDownload(updateId);
    }

    private void downloadCancel() {
        Log.d(TAG, "Cancelling download!");
        setUpdating(false);
        mUpdaterController.pauseDownload(updateId);
        mUpdaterController.deleteUpdate(updateId);
        prefsEditor.putString("pageId", "").apply();
        prefsEditor.putInt("progPercent", 0).apply();
        prefsEditor.putString("progStep", "").apply();
        renderPage("updateAvailable");
        refresh();
    }

    private void downloadPause() {
        Log.d(TAG, "Pausing download!");
        setUpdating(true);
        registerPage("updatePaused", pageUpdatePaused());
        progressBar.setIndeterminate(true);
        renderPage("updatePaused");
        mUpdaterController.pauseDownload(updateId);
    }

    private void downloadResume() {
        Log.d(TAG, "Resuming download!");
        setUpdating(true);
        registerPage("updateDownloading", pageUpdateDownloading());
        progressBar.setIndeterminate(false);
        renderPage("updateDownloading");
        mUpdaterController.resumeDownload(updateId);
    }

    private void install() {
        Log.d(TAG, "Installing update!");
        setUpdating(true);
        renderPage("updateInstalling");
        Utils.triggerUpdate(this, updateId);
    }

    private void installPause() {
        Log.d(TAG, "Pausing update installation!");
        setUpdating(true);
        progressBar.setIndeterminate(true);
        renderPage("updateInstallingPaused");
        mUpdateEngine.suspend();
    }

    private void installResume() {
        Log.d(TAG, "Resuming update installation!");
        setUpdating(true);
        progressBar.setIndeterminate(false);
        renderPage("updateInstalling");
        mUpdateEngine.resume();
    }

    private void reboot() {
        Log.d(TAG, "Rebooting device!");
        setUpdating(false);
        PowerManager pm = this.getSystemService(PowerManager.class);
        pm.reboot(null);
    }

    private void setUpdating(Boolean updating) {
        wasUpdating = updating;
        //Log.d(TAG, "Set updating: " + updating);
        prefsEditor.putBoolean("updating", updating).apply();
        prefsEditor.apply();
    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    public void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }

        //Log.d(TAG, "Committing preferences before close");
        prefsEditor.apply(); //Make sure we commit preferences no matter what

        super.onStop();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            mUpdaterController = mUpdaterService.getUpdaterController();

            if (mUpdaterController != null && update != null) {
                mUpdaterController.addUpdate(update);
                List<String> updatesOnline = new ArrayList<>();
                updatesOnline.add(update.getDownloadId());
                mUpdaterController.setUpdatesAvailableOnline(updatesOnline, true);
            }

            if (pageIdActive.equals("updateChecking")) {
                Log.d(TAG, "Running automatic update check...");
                refresh();
            } else {
                new PageHandler().execute(); //Potentially load a different page from the initial one
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mUpdaterController = null;
            mUpdaterService = null;
        }
    };

    private int easterEggSteps = 0;
    private Handler handler = new Handler();
    private Runnable resetEasterEggSteps = new Runnable() {
        @Override
        public void run() {
            easterEggSteps = 0;
        }
    };

    private void easterEgg() {
        if (!wasUpdating) {
            easterEggSteps++;
            handler.removeCallbacks(resetEasterEggSteps);
            if (easterEggSteps == 7) {
                renderPage("enrollEarlyUpdates");
                easterEggSteps = 0;
            } else {
                handler.postDelayed(resetEasterEggSteps, 1000);
            }
        }
    }
}
