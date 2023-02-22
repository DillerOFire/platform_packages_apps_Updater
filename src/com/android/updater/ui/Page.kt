package com.android.updater.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.Button
import com.android.updater.databinding.PageUpdatesBinding

private const val TAG = "Page"
class Page {
    var icon = 0
    var strTitle = ""
    var strStatus = ""
    var btnPrimaryText = ""
    var btnPrimaryClickListener: View.OnClickListener? = null
    var btnSecondaryText = ""
    var btnSecondaryClickListener: View.OnClickListener? = null
    var btnExtraText = ""
    var btnExtraClickListener: View.OnClickListener? = null
    var progStep = ""
    var progPercent = -1
    var htmlContent: String = ""
    var htmlColor = 0
    var runnable = Runnable {
        //We don't do anything right now!
    }
    var runnableRan = false

    fun render(context: UpdatesActivity, checkHtmlLast: (html: String) -> Boolean) {
        val binding = context.binding
        context.runOnUiThread { hideAllViews(binding) }
        if (icon != 0) {
            Log.d(TAG, "render: $icon")
            binding.headerIcon.setImageResource(icon)
            binding.headerIcon.visibility = View.VISIBLE
        }
        if (strTitle.isNotEmpty()) {
            binding.headerTitle.text = strTitle
            binding.headerTitle.visibility = View.VISIBLE
        }
        if (strStatus.isNotEmpty()) {
            binding.headerStatus.text = strStatus
            binding.headerStatus.visibility = View.VISIBLE
        }
        if (btnPrimaryText.isNotEmpty()) {
            binding.btnPrimary.text = btnPrimaryText
            setBtnClickListener(binding.btnPrimary, btnPrimaryClickListener)
            binding.btnPrimary.isEnabled = true
            binding.btnPrimary.visibility = View.VISIBLE
        }
        if (btnSecondaryText.isNotEmpty()) {
            binding.btnSecondary.text = btnSecondaryText
            setBtnClickListener(binding.btnSecondary, btnSecondaryClickListener)
            binding.btnSecondary.isEnabled = true
            binding.btnSecondary.visibility = View.VISIBLE
        }
        if (btnExtraText.isNotEmpty()) {
            binding.btnExtra.text = btnExtraText
            setBtnClickListener(binding.btnExtra, btnExtraClickListener)
            binding.btnExtra.isEnabled = true
            binding.btnExtra.visibility = View.VISIBLE
        }
        if (progStep.isNotEmpty()) {
            binding.progressText.text = progStep
            binding.progressText.visibility = View.VISIBLE
        }
        if (progPercent > -1) {
            binding.progressBar.setProgress(progPercent, true)
            binding.progressBar.visibility = View.VISIBLE
        }
        if (htmlContent.isNotEmpty()) {
            val hexColor = if (htmlColor != 0) String.format("; color: #%06X", 0xFFFFFF and htmlColor) else ""
            val html = String.format("<html><head><style>body { font-size: light%s; display:inline; padding:0px; margin:0px; letter-spacing: -0.02; line-height: 1.5; }</style></head><body>%s</body></html>", hexColor, htmlContent)
            if (checkHtmlLast(html)) {
                binding.webview.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                binding.webview.setBackgroundColor(Color.TRANSPARENT)
            }
            binding.webview.settings.builtInZoomControls = false
            binding.webview.visibility = View.VISIBLE
        }
    }

    private fun hideAllViews(binding: PageUpdatesBinding) {
        binding.headerIcon.visibility = View.GONE
        binding.headerTitle.visibility = View.GONE
        binding.headerStatus.visibility = View.GONE
        binding.btnPrimary.visibility = View.GONE
        binding.btnSecondary.visibility = View.GONE
        binding.btnExtra.visibility = View.GONE
        binding.progressText.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.webview.visibility = View.GONE
        binding.progressBar.scaleY = 1.0f
    }

    private fun setBtnClickListener(btn: Button?, clickListener: View.OnClickListener?) {
        btn!!.setOnClickListener { v: View? ->
            if (clickListener != null) {
                var activity: UpdatesActivity? = null
                var context = btn.context
                while (context is ContextWrapper) {
                    if (context is Activity) {
                        activity = context as UpdatesActivity
                    }
                    context = context.baseContext
                }
                if (activity == null) {
                    clickListener.onClick(v)
                } else {
                    activity.runOnUiThread(Runnable { clickListener.onClick(v) })
                }
            }
        }
    }
}