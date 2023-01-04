package org.lineageos.updater;

import android.app.Activity;
import android.graphics.Color;
import android.media.Image;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

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
    public int progPercent = 0;
    public String htmlContent = "";
    public int htmlColor = 0;
    public Runnable runnable = new Runnable() {
        @Override
        public void run() {
            Log.d(mContext.TAG, "This page does not run custom code!");
        }
    };
    public Boolean runnableRan = false;

    public UpdatesActivity mContext;

    public void render(UpdatesActivity context) {
        this.mContext = context;
        context.runOnUiThread(
                new Runnable() {
                    @Override
                    public void run() {

                        mContext.headerIcon.setVisibility(View.GONE);
                        mContext.headerTitle.setVisibility(View.GONE);
                        mContext.headerStatus.setVisibility(View.GONE);
                        mContext.btnPrimary.setVisibility(View.GONE);
                        mContext.btnSecondary.setVisibility(View.GONE);
                        mContext.btnExtra.setVisibility(View.GONE);
                        mContext.progressText.setVisibility(View.GONE); //Good separator between headerStatus and webView
                        mContext.progressBar.setVisibility(View.GONE);
                        mContext.webView.setVisibility(View.GONE);

                        mContext.progressBar.setScaleY(1.0f);

                        if (icon != 0) {
                            mContext.headerIcon.setImageResource(icon);
                            mContext.headerIcon.setVisibility(View.VISIBLE);
                        }

                        if (!Objects.equals(strTitle, "")) {
                            mContext.headerTitle.setText(strTitle);
                            mContext.headerTitle.setVisibility(View.VISIBLE);
                        }

                        if (!Objects.equals(strStatus, "")) {
                            mContext.headerStatus.setText(strStatus);
                            mContext.headerStatus.setVisibility(View.VISIBLE);
                        }

                        if (!Objects.equals(btnPrimaryText, "") && btnPrimaryClickListener != null) {
                            mContext.btnPrimary.setText(btnPrimaryText);
                            setBtnClickListener(mContext.btnPrimary, btnPrimaryClickListener);
                            mContext.btnPrimary.setVisibility(View.VISIBLE);
                            if (Objects.equals(btnSecondaryText, ""))
                                mContext.btnSecondary.setVisibility(View.INVISIBLE);
                            if (Objects.equals(btnExtraText, ""))
                                mContext.btnExtra.setVisibility(View.INVISIBLE);
                        }
                        if (!Objects.equals(btnSecondaryText, "") && btnSecondaryClickListener != null) {
                            mContext.btnSecondary.setText(btnSecondaryText);
                            setBtnClickListener(mContext.btnSecondary, btnSecondaryClickListener);
                            mContext.btnSecondary.setVisibility(View.VISIBLE);
                            if (Objects.equals(btnPrimaryText, ""))
                                mContext.btnPrimary.setVisibility(View.INVISIBLE);
                            if (Objects.equals(btnExtraText, ""))
                                mContext.btnExtra.setVisibility(View.INVISIBLE);
                        }
                        if (!Objects.equals(btnExtraText, "") && btnExtraClickListener != null) {
                            mContext.btnExtra.setText(btnExtraText);
                            setBtnClickListener(mContext.btnExtra, btnExtraClickListener);
                            mContext.btnExtra.setVisibility(View.VISIBLE);
                            if (Objects.equals(btnPrimaryText, ""))
                                mContext.btnPrimary.setVisibility(View.INVISIBLE);
                            if (Objects.equals(btnSecondaryText, ""))
                                mContext.btnSecondary.setVisibility(View.INVISIBLE);
                        }

                        mContext.progressText.setText(progStep);
                        if (!Objects.equals(progStep, "")) {
                            mContext.progressText.setText(progStep);
                            mContext.progressBar.setProgress(progPercent, true);

                            mContext.progressText.setVisibility(View.VISIBLE);
                            mContext.progressBar.setVisibility(View.VISIBLE);
                        }

                        if (!Objects.equals(htmlContent, "")) {
                            String hexColor = "";
                            if (htmlColor != 0) {
                                int colorTextR = Color.red(htmlColor);
                                int colorTextG = Color.green(htmlColor);
                                int colorTextB = Color.blue(htmlColor);
                                hexColor = String.format("; color: #%02x%02x%02x", colorTextR, colorTextG, colorTextB);
                            }

                            String html = "<html><head><style>body { font-size: light" + hexColor + "; }</style></head><body>" + htmlContent + "</body></html>";
                            if (!html.equals(mContext.htmlContentLast)) {
                                mContext.htmlContentLast = html;
                                Log.d(UpdatesActivity.TAG, "Last HTML didn't match, using new HTML: " + mContext.htmlContentLast);

                                mContext.webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                                mContext.webView.setBackgroundColor(Color.TRANSPARENT);
                            }

                            mContext.webView.setVisibility(View.VISIBLE);
                        }
                    }
                }
        );
    }

    private void setBtnClickListener(Button btn, View.OnClickListener clickListener) {
        btn.setOnClickListener(v -> {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (clickListener != null) {
                clickListener.onClick(v);
            }
        });
    }
}
