package com.android.updater.ui

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Color
import android.view.View
import android.widget.Button

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
    var mContext: UpdatesActivity? = null
    fun render(context: UpdatesActivity) {
        mContext = context
        context.runOnUiThread { hideAllViews() }
        if (icon != 0) {
            mContext!!.headerIcon!!.setImageResource(icon)
            mContext!!.headerIcon!!.visibility = View.VISIBLE
        }
        if (!strTitle.isEmpty()) {
            mContext!!.headerTitle!!.text = strTitle
            mContext!!.headerTitle!!.visibility = View.VISIBLE
        }
        if (!strStatus.isEmpty()) {
            mContext!!.headerStatus!!.text = strStatus
            mContext!!.headerStatus!!.visibility = View.VISIBLE
        }
        if (!btnPrimaryText.isEmpty()) {
            mContext!!.btnPrimary!!.text = btnPrimaryText
            setBtnClickListener(mContext!!.btnPrimary, btnPrimaryClickListener)
            mContext!!.btnPrimary!!.isEnabled = true
            mContext!!.btnPrimary!!.visibility = View.VISIBLE
        }
        if (!btnSecondaryText.isEmpty()) {
            mContext!!.btnSecondary!!.text = btnSecondaryText
            setBtnClickListener(mContext!!.btnSecondary, btnSecondaryClickListener)
            mContext!!.btnSecondary!!.isEnabled = true
            mContext!!.btnSecondary!!.visibility = View.VISIBLE
        }
        if (!btnExtraText.isEmpty()) {
            mContext!!.btnExtra!!.text = btnExtraText
            setBtnClickListener(mContext!!.btnExtra, btnExtraClickListener)
            mContext!!.btnExtra!!.isEnabled = true
            mContext!!.btnExtra!!.visibility = View.VISIBLE
        }
        if (!progStep.isEmpty()) {
            mContext!!.progressText!!.text = progStep
            mContext!!.progressText!!.visibility = View.VISIBLE
        }
        if (progPercent > -1) {
            mContext!!.progressBar!!.setProgress(progPercent, true)
            mContext!!.progressBar!!.visibility = View.VISIBLE
        }
        if (htmlContent != null && !htmlContent!!.isEmpty()) {
            val hexColor = if (htmlColor != 0) String.format("; color: #%06X", 0xFFFFFF and htmlColor) else ""
            val html = String.format("<html><head><style>body { font-size: light%s; display:inline; padding:0px; margin:0px; letter-spacing: -0.02; line-height: 1.5; }</style></head><body>%s</body></html>", hexColor, htmlContent)
            if (html != mContext!!.htmlContentLast) {
                mContext!!.htmlContentLast = html
                mContext!!.webView!!.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                mContext!!.webView!!.setBackgroundColor(Color.TRANSPARENT)
            }
            mContext!!.webView!!.settings.builtInZoomControls = false
            mContext!!.webView!!.visibility = View.VISIBLE
        }
    }

    private fun hideAllViews() {
        mContext!!.headerIcon!!.visibility = View.GONE
        mContext!!.headerTitle!!.visibility = View.GONE
        mContext!!.headerStatus!!.visibility = View.GONE
        mContext!!.btnPrimary!!.visibility = View.GONE
        mContext!!.btnSecondary!!.visibility = View.GONE
        mContext!!.btnExtra!!.visibility = View.GONE
        mContext!!.progressText!!.visibility = View.GONE
        mContext!!.progressBar!!.visibility = View.GONE
        mContext!!.webView!!.visibility = View.GONE
        mContext!!.progressBar!!.scaleY = 1.0f
    }

    private fun setBtnClickListener(btn: Button?, clickListener: View.OnClickListener?) {
        btn!!.setOnClickListener { v: View? ->
            if (clickListener != null) {
                var activity: UpdatesActivity? = null
                var context = btn.context
                while (context is ContextWrapper) {
                    if (context is Activity || context is UpdatesActivity) {
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