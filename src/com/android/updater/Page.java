package com.android.updater;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;

import java.util.Objects;

public class Page {
    public int icon = 0;
    public String strTitle = "";
    public String strStatus = "";
    public String btnPrimaryText = "";
    public View.OnClickListener btnPrimaryClickListener;
    public String btnSecondaryText = "";
    public View.OnClickListener btnSecondaryClickListener;
    public String btnExtraText = "";
    public View.OnClickListener btnExtraClickListener;
    public String progStep = "";
    public int progPercent = -1;
    public String htmlContent = "";
    public int htmlColor = 0;
    public Runnable runnable = new Runnable() {
        @Override
        public void run() {
            //We don't do anything right now!
        }
    };
    public Boolean runnableRan = false;

    public UpdatesActivity mContext;

    public void render(UpdatesActivity context) {
        mContext = context;
        context.runOnUiThread(this::hideAllViews);

        if (icon != 0) {
            mContext.headerIcon.setImageResource(icon);
            mContext.headerIcon.setVisibility(View.VISIBLE);
        }

        if (!strTitle.isEmpty()) {
            mContext.headerTitle.setText(strTitle);
            mContext.headerTitle.setVisibility(View.VISIBLE);
        }

        if (!strStatus.isEmpty()) {
            mContext.headerStatus.setText(strStatus);
            mContext.headerStatus.setVisibility(View.VISIBLE);
        }

        if (btnPrimaryClickListener != null && !btnPrimaryText.isEmpty()) {
            mContext.btnPrimary.setText(btnPrimaryText);
            setBtnClickListener(mContext.btnPrimary, btnPrimaryClickListener);
            mContext.btnPrimary.setEnabled(true);
            mContext.btnPrimary.setVisibility(View.VISIBLE);
            if (Objects.equals(btnSecondaryText, ""))
                mContext.btnSecondary.setVisibility(View.INVISIBLE);
            if (Objects.equals(btnExtraText, ""))
                mContext.btnExtra.setVisibility(View.INVISIBLE);
        }

        if (btnSecondaryClickListener != null && !btnSecondaryText.isEmpty()) {
            mContext.btnSecondary.setText(btnSecondaryText);
            setBtnClickListener(mContext.btnSecondary, btnSecondaryClickListener);
            mContext.btnSecondary.setEnabled(true);
            mContext.btnSecondary.setVisibility(View.VISIBLE);
            if (Objects.equals(btnPrimaryText, ""))
                mContext.btnPrimary.setVisibility(View.INVISIBLE);
            if (Objects.equals(btnExtraText, ""))
                mContext.btnExtra.setVisibility(View.INVISIBLE);
        }

        if (btnExtraClickListener != null && !btnExtraText.isEmpty()) {
            mContext.btnExtra.setText(btnExtraText);
            setBtnClickListener(mContext.btnExtra, btnExtraClickListener);
            mContext.btnExtra.setEnabled(true);
            mContext.btnExtra.setVisibility(View.VISIBLE);
            if (Objects.equals(btnPrimaryText, ""))
                mContext.btnPrimary.setVisibility(View.INVISIBLE);
            if (Objects.equals(btnSecondaryText, ""))
                mContext.btnSecondary.setVisibility(View.INVISIBLE);
        }

        if (!progStep.isEmpty()) {
            mContext.progressText.setText(progStep);
            mContext.progressText.setVisibility(View.VISIBLE);
        }

        if (progPercent > -1) {
            mContext.progressBar.setProgress(progPercent, true);
            mContext.progressBar.setVisibility(View.VISIBLE);
        }

        if (htmlContent != null && !htmlContent.isEmpty()) {
            String hexColor = htmlColor != 0 ? String.format("; color: #%06X", 0xFFFFFF & htmlColor) : "";
            String html = String.format("<html><head><style>body { font-size: light%s; display:inline; padding:0px; margin:0px; letter-spacing: -0.02; line-height: 1.5; }</style></head><body>%s</body></html>", hexColor, htmlContent);

            if (!html.equals(mContext.htmlContentLast)) {
                mContext.htmlContentLast = html;
                mContext.webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                mContext.webView.setBackgroundColor(Color.TRANSPARENT);
            }

            mContext.webView.getSettings().setBuiltInZoomControls(false);
            mContext.webView.setVisibility(View.VISIBLE);
        }
    }

    private void hideAllViews() {
        mContext.headerIcon.setVisibility(View.GONE);
        mContext.headerTitle.setVisibility(View.GONE);
        mContext.headerStatus.setVisibility(View.GONE);
        mContext.btnPrimary.setVisibility(View.GONE);
        mContext.btnSecondary.setVisibility(View.GONE);
        mContext.btnExtra.setVisibility(View.GONE);
        mContext.progressText.setVisibility(View.GONE);
        mContext.progressBar.setVisibility(View.GONE);
        mContext.webView.setVisibility(View.GONE);
        mContext.progressBar.setScaleY(1.0f);
    }

    private void setBtnClickListener(Button btn, View.OnClickListener clickListener) {
        btn.setOnClickListener(v -> {
            if (clickListener != null) {
                UpdatesActivity activity = null;

                Context context = btn.getContext();
                while (context instanceof ContextWrapper) {
                    if (context instanceof Activity || context instanceof UpdatesActivity) {
                        activity = (UpdatesActivity) context;
                    }
                    context = ((ContextWrapper) context).getBaseContext();
                }
                if (activity == null) {
                    clickListener.onClick(v);
                } else {
                    activity.runOnUiThread(() -> clickListener.onClick(v));
                }
            }
        });
    }
}
