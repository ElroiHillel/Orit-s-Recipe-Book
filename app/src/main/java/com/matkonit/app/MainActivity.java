package com.matkonit.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin WebView shell. The whole app lives in assets/index.html.
 * The page is served from a fake https origin so localStorage and fetch() behave like a normal website.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/index.html";
    private static final int REQ_FILE = 1001;
    private static final int SHARE_MAX_PX = 2400;

    private WebView web;
    private WebView printView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private volatile String pendingShare = null;
    private boolean pageLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Native");
        web.setWebViewClient(new AppClient());
        web.setWebChromeClient(new ChromeClient());

        handleShareIntent(getIntent());
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START_URL);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (web == null || !pageLoaded) { super.onBackPressed(); return; }
        // Let the page close a sheet or go back a screen first; leave the app only from the home screen.
        web.evaluateJavascript("(window.handleBack && window.handleBack()) ? 1 : 0", result -> {
            if (!"1".equals(result)) leaveApp();
        });
    }

    private void leaveApp() {
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- web client

    private class AppClient extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (!HOST.equals(u.getHost())) return null;
            String path = u.getPath();
            if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
            try {
                InputStream in = getAssets().open(path.substring(1));
                return new WebResourceResponse(mimeFor(path), "utf-8", in);
            } catch (Exception e) {
                return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null, null);
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (HOST.equals(u.getHost())) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, u));
            } catch (Exception ignored) { }
            return true;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            pageLoaded = true;
            notifyPageOfShare();
        }
    }

    private static String mimeFor(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    // ---------------------------------------------------------------- file chooser (gallery + camera)

    private class ChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;

            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            String[] types = params.getAcceptTypes();
            String type = (types != null && types.length > 0 && types[0] != null && !types[0].isEmpty()) ? types[0] : "*/*";
            if (type.startsWith(".")) type = "*/*";
            pick.setType(type);
            pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);

            Intent chooser = Intent.createChooser(pick, "בחירת תמונה");
            Intent camDirect = null;
            cameraUri = null;
            if (type.startsWith("image") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, "matkonit_" + System.currentTimeMillis() + ".jpg");
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Matkonit");
                    cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                    if (cameraUri != null) {
                        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                        if (params.isCaptureEnabled()) camDirect = cam;   // the in-app "צילום" button
                    }
                } catch (Exception e) {
                    cameraUri = null;
                }
            }
            try {
                startActivityForResult(camDirect != null ? camDirect : chooser, REQ_FILE);
            } catch (Exception e) {
                fileCallback = null;
                cleanupCameraUri();
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE || fileCallback == null) return;
        List<Uri> uris = new ArrayList<>();
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData cd = data.getClipData();
                for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            } else if (data != null && data.getData() != null) {
                uris.add(data.getData());
            } else if (cameraUri != null && hasContent(cameraUri)) {
                uris.add(cameraUri);
                cameraUri = null; // keep the photo
            }
        }
        cleanupCameraUri();
        fileCallback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        fileCallback = null;
    }

    private boolean hasContent(Uri u) {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            return in != null && in.read() != -1;
        } catch (Exception e) {
            return false;
        }
    }

    private void cleanupCameraUri() {
        if (cameraUri != null) {
            try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) { }
            cameraUri = null;
        }
    }

    // ---------------------------------------------------------------- receiving shares from other apps

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        final Intent in = intent;
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                StringBuilder text = new StringBuilder();
                CharSequence subj = in.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
                CharSequence body = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
                if (subj != null && subj.length() > 0) text.append(subj).append("\n");
                if (body != null) text.append(body);
                if (text.length() > 0) o.put("text", text.toString().trim());

                JSONArray imgs = new JSONArray();
                List<Uri> streams = new ArrayList<>();
                if (Intent.ACTION_SEND.equals(in.getAction())) {
                    Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
                    if (p instanceof Uri) streams.add((Uri) p);
                } else {
                    ArrayList<Parcelable> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                    if (list != null) for (Parcelable p : list) if (p instanceof Uri) streams.add((Uri) p);
                }
                for (Uri u : streams) {
                    String d = imageAsDataUrl(u);
                    if (d != null) imgs.put(d);
                }
                if (imgs.length() > 0) o.put("images", imgs);
                if (o.length() == 0) return;
                pendingShare = o.toString();
                runOnUiThread(this::notifyPageOfShare);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "לא הצלחתי לקרוא את מה ששותף", Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void notifyPageOfShare() {
        if (pageLoaded && pendingShare != null) {
            web.evaluateJavascript("window.onNativeShare && window.onNativeShare()", null);
        }
    }

    private String imageAsDataUrl(Uri u) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / (sample * 2) >= SHARE_MAX_PX) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                bmp = BitmapFactory.decodeStream(in, null, opts);
            }
            if (bmp == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos);
            bmp.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- JS bridge

    // ---------------------------------------------------------------- native text editor
    // A plain Android EditText in a full-screen dialog. Works with every keyboard (SwiftKey included),
    // which the WebView textarea did not. Shows the source photos above the text when given.

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static int color(JSONObject o, String key, int def) {
        try { return Color.parseColor(o.optString(key, "").trim()); } catch (Exception e) { return def; }
    }

    private static GradientDrawable rounded(int fill, int stroke, int radius, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    private Bitmap decodeDataUrl(String url, int maxW) {
        try {
            byte[] bytes = Base64.decode(url.substring(url.indexOf(',') + 1), Base64.DEFAULT);
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, b);
            int sample = 1;
            while (b.outWidth / (sample * 2) >= maxW) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (Exception e) {
            return null;
        }
    }

    private void sendEditResult(String reqId, String text) {
        String js = "window.onNativeEdit && window.onNativeEdit(" + JSONObject.quote(reqId) + "," + (text == null ? "null" : JSONObject.quote(text)) + ")";
        web.evaluateJavascript(js, null);
    }

    private void showNativeEditor(String reqId, String optsJson) {
        JSONObject o;
        try { o = new JSONObject(optsJson); } catch (Exception e) { sendEditResult(reqId, null); return; }
        final int bg = color(o, "bg", Color.WHITE), surface = color(o, "surface", Color.WHITE), ink = color(o, "ink", Color.BLACK);
        final int muted = color(o, "muted", Color.GRAY), accent = color(o, "accent", 0xFFD9577A), accentInk = color(o, "accentInk", Color.WHITE), line = color(o, "line", Color.LTGRAY);
        final float fs = (float) o.optDouble("fs", 16);

        final Dialog d = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        final boolean[] answered = {false};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(12), dp(8), dp(12), dp(12));

        // top bar: title, zoom (when there is a photo), cancel, save
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView title = new TextView(this);
        title.setText(o.optString("title", ""));
        title.setTextColor(ink);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final EditText et = new EditText(this);

        JSONArray imgs = o.optJSONArray("images");
        final LinearLayout imgCol = new LinearLayout(this);
        imgCol.setOrientation(LinearLayout.VERTICAL);
        final float[] zoom = {1f};
        final int screenW = getResources().getDisplayMetrics().widthPixels - dp(24);
        boolean hasImages = false;
        if (imgs != null) {
            for (int i = 0; i < imgs.length(); i++) {
                Bitmap bm = decodeDataUrl(imgs.optString(i, ""), 2000);
                if (bm == null) continue;
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bm);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(ImageView.ScaleType.FIT_START);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(screenW, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin = dp(6);
                imgCol.addView(iv, lp);
                hasImages = true;
            }
        }
        if (hasImages) {
            String[] labels = {"−", "+"};
            float[] steps = {-0.5f, 0.5f};
            for (int k = 0; k < 2; k++) {
                final float step = steps[k];
                Button z = new Button(this, null, android.R.attr.borderlessButtonStyle);
                z.setText(labels[k]);
                z.setTextColor(accent);
                z.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
                z.setMinWidth(0); z.setMinimumWidth(0);
                z.setPadding(dp(10), 0, dp(10), 0);
                z.setOnClickListener(v -> {
                    zoom[0] = Math.max(1f, Math.min(4f, zoom[0] + step));
                    for (int c = 0; c < imgCol.getChildCount(); c++) {
                        View child = imgCol.getChildAt(c);
                        ViewGroup.LayoutParams lp = child.getLayoutParams();
                        lp.width = Math.round(screenW * zoom[0]);
                        child.setLayoutParams(lp);
                    }
                });
                bar.addView(z, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
            }
        }

        Button cancel = new Button(this, null, android.R.attr.borderlessButtonStyle);
        cancel.setText(o.optString("cancel", "ביטול"));
        cancel.setTextColor(muted);
        cancel.setAllCaps(false);
        bar.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        Button save = new Button(this, null, android.R.attr.borderlessButtonStyle);
        save.setText(o.optString("save", "שמירה"));
        save.setTextColor(accentInk);
        save.setAllCaps(false);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setBackground(rounded(accent, accent, dp(22), 0));
        save.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        saveLp.setMarginStart(dp(6));
        bar.addView(save, saveLp);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (hasImages) {
            ScrollView vs = new ScrollView(this);
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            hs.addView(imgCol);
            vs.addView(hs);
            vs.setBackground(rounded(surface, line, dp(14), dp(1)));
            vs.setPadding(dp(6), dp(6), dp(6), dp(6));
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.42f);
            vlp.topMargin = dp(8);
            root.addView(vs, vlp);
        }

        et.setText(o.optString("text", ""));
        et.setHint(o.optString("hint", ""));
        et.setHintTextColor(muted);
        et.setTextColor(ink);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, fs);
        et.setLineSpacing(0, 1.25f);
        et.setGravity(Gravity.TOP | Gravity.START);
        et.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG_RTL);
        et.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        et.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setSingleLine(false);
        et.setHorizontallyScrolling(false);
        et.setVerticalScrollBarEnabled(true);
        et.setBackground(rounded(surface, line, dp(14), dp(1)));
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, hasImages ? 0.58f : 1f);
        elp.topMargin = dp(8);
        root.addView(et, elp);

        cancel.setOnClickListener(v -> { answered[0] = true; sendEditResult(reqId, null); d.dismiss(); });
        save.setOnClickListener(v -> { answered[0] = true; sendEditResult(reqId, et.getText().toString()); d.dismiss(); });
        // The back button keeps what was typed, like most note apps.
        d.setOnDismissListener(di -> { if (!answered[0]) { answered[0] = true; sendEditResult(reqId, et.getText().toString()); } });

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            d.getWindow().setStatusBarColor(bg);
        }
        d.show();
        et.requestFocus();
        et.setSelection(et.getText().length());
    }

    private class Bridge {
        @JavascriptInterface
        public void editText(String reqId, String optsJson) {
            runOnUiThread(() -> showNativeEditor(reqId, optsJson));
        }

        @JavascriptInterface
        public void keepAwake(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public String getPendingShare() {
            String s = pendingShare;
            pendingShare = null;
            return s == null ? "" : s;
        }

        @JavascriptInterface
        public void copy(String text) {
            runOnUiThread(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("מתכון", text));
            });
        }

        @JavascriptInterface
        public String readClipboard() {
            try {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm == null || !cm.hasPrimaryClip()) return "";
                ClipData cd = cm.getPrimaryClip();
                if (cd == null || cd.getItemCount() == 0) return "";
                CharSequence t = cd.getItemAt(0).coerceToText(MainActivity.this);
                return t == null ? "" : t.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void share(String text, String title) {
            runOnUiThread(() -> {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, text);
                if (title != null && !title.isEmpty()) send.putExtra(Intent.EXTRA_SUBJECT, title);
                startActivity(Intent.createChooser(send, "שיתוף המתכון"));
            });
        }

        @JavascriptInterface
        public String saveFile(String name, String mime, String content) {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            String storeMime = "text/markdown".equals(mime) ? "application/octet-stream" : mime;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, storeMime);
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Matkonit");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) return "השמירה נכשלה";
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) return "השמירה נכשלה";
                        os.write(bytes);
                    }
                    return "נשמר בהורדות/Matkonit";
                } else {
                    File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (dir == null) return "השמירה נכשלה";
                    File f = new File(dir, name);
                    try (FileOutputStream fos = new FileOutputStream(f)) {
                        fos.write(bytes);
                    }
                    return "נשמר: " + f.getAbsolutePath();
                }
            } catch (Exception e) {
                return "השמירה נכשלה: " + e.getMessage();
            }
        }

        @JavascriptInterface
        public void printHtml(String html, String jobName) {
            runOnUiThread(() -> {
                printView = new WebView(MainActivity.this);
                printView.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
                        if (pm == null) return;
                        String job = (jobName == null || jobName.isEmpty()) ? "מתכון" : jobName;
                        PrintDocumentAdapter adapter = view.createPrintDocumentAdapter(job);
                        pm.print(job, adapter, new PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4).build());
                    }
                });
                printView.loadDataWithBaseURL("https://" + HOST + "/", html, "text/html", "utf-8", null);
            });
        }
    }
}
