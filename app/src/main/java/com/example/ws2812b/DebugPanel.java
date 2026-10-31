package com.example.ws2812b;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * 类 ApiPost 的接口调试面板：可编辑方法 / URL / Headers / Body，发送后显示状态码、耗时、响应原文，
 * 并保存最近 20 条历史。只依赖平台控件，无第三方库。
 */
public class DebugPanel extends LinearLayout {

    public interface AddressProvider {
        String currentBaseUrl();
    }

    private static final int COLOR_CARD = 0xFF1C1C1E;
    private static final int COLOR_IDLE = 0xFF242426;
    private static final int COLOR_STROKE = 0xFF3A3A3C;
    private static final int COLOR_TEXT = 0xFFE5E5EA;
    private static final int COLOR_DIM = 0xFF9A9A9F;
    private static final int COLOR_ON = 0xFF0A84FF;
    private static final int COLOR_OK = 0xFF30D158;
    private static final int COLOR_WARN = 0xFFFF9F0A;
    private static final int COLOR_ERR = 0xFFFF453A;

    private static final int TIMEOUT_MS = 6000;
    private static final int HISTORY_LIMIT = 20;
    private static final String PREFS_NAME = "ws2812b";
    private static final String KEY_HISTORY = "debug_history";
    private static final String DEFAULT_BODY = "[\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\",\"x\"]";

    private final ExecutorService io;
    private final AddressProvider provider;
    private final SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private Button methodButton;
    private EditText urlInput;
    private EditText headersInput;
    private EditText bodyInput;
    private TextView statusText;
    private TextView responseText;

    private String lastBase;
    private int requestSeq = 0;

    public DebugPanel(Context context, ExecutorService io, AddressProvider provider) {
        super(context);
        this.io = io;
        this.provider = provider;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(2), dp(16), dp(10));
        setBackgroundColor(0xFF111111);
        build();
        lastBase = provider.currentBaseUrl();
        urlInput.setText(lastBase + "/api/state");
        updateMethodState();
    }

    // ---------------------------------------------------------------- UI

    private void build() {
        addView(label("请求"));

        LinearLayout requestRow = row();
        methodButton = new Button(getContext());
        styleButton(methodButton, COLOR_ON, Color.WHITE, 13, 9);
        methodButton.setText("GET");
        methodButton.setOnClickListener(v -> {
            methodButton.setText("POST".contentEquals(methodButton.getText()) ? "GET" : "POST");
            updateMethodState();
        });
        requestRow.addView(methodButton, new LinearLayout.LayoutParams(dp(66), dp(42)));

        urlInput = input(0, false);
        urlInput.setTextSize(13);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setHint("http://IP:8080/api/state");
        urlInput.setSingleLine(true);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        urlParams.leftMargin = dp(8);
        requestRow.addView(urlInput, urlParams);
        addView(requestRow, lpMatchWrap());

        LinearLayout presetRow = row();
        presetRow.setPadding(0, dp(8), 0, dp(2));
        addPreset(presetRow, "state 模板", false);
        addPreset(presetRow, "control 模板", true);
        presetRow.addView(new View(getContext()), new LinearLayout.LayoutParams(0, 1, 1f));
        Button historyButton = new Button(getContext());
        styleButton(historyButton, COLOR_IDLE, COLOR_TEXT, 12, 9);
        historyButton.setText("历史");
        historyButton.setPadding(dp(10), 0, dp(10), 0);
        historyButton.setOnClickListener(v -> showHistory());
        presetRow.addView(historyButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        addView(presetRow, lpMatchWrap());

        addView(label("Headers（每行 Key: Value）"));
        headersInput = input(dp(64), true);
        headersInput.setTextSize(12);
        headersInput.setHint("Content-Type: application/json");
        addView(headersInput, lpMatch(dp(64)));

        addView(label("Body（仅 POST 发送）"));
        bodyInput = input(dp(88), true);
        bodyInput.setText(DEFAULT_BODY);
        bodyInput.setTextSize(13);
        addView(bodyInput, lpMatch(dp(88)));

        LinearLayout sendRow = row();
        sendRow.setPadding(0, dp(10), 0, dp(2));
        Button sendButton = new Button(getContext());
        styleButton(sendButton, COLOR_ON, Color.WHITE, 15, 10);
        sendButton.setText("发送");
        sendButton.setOnClickListener(v -> send());
        sendRow.addView(sendButton, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button clearButton = new Button(getContext());
        styleButton(clearButton, COLOR_IDLE, COLOR_TEXT, 13, 10);
        clearButton.setText("清空响应");
        clearButton.setOnClickListener(v -> {
            responseText.setText("");
            statusText.setText("就绪");
            statusText.setTextColor(COLOR_DIM);
        });
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(dp(96), dp(46));
        clearParams.leftMargin = dp(8);
        sendRow.addView(clearButton, clearParams);
        addView(sendRow, lpMatchWrap());

        statusText = new TextView(getContext());
        statusText.setText("就绪");
        statusText.setTextColor(COLOR_DIM);
        statusText.setTextSize(12);
        statusText.setPadding(0, dp(6), 0, dp(4));
        addView(statusText, lpMatchWrap());

        ScrollView responseScroll = new ScrollView(getContext());
        responseScroll.setBackground(rounded(COLOR_CARD, COLOR_STROKE, 10));
        responseText = new TextView(getContext());
        responseText.setTextColor(COLOR_TEXT);
        responseText.setTextSize(12);
        responseText.setTypeface(Typeface.MONOSPACE);
        responseText.setTextIsSelectable(true);
        responseText.setPadding(dp(12), dp(10), dp(12), dp(10));
        responseScroll.addView(responseText, lpMatchWrap());
        addView(responseScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                urlInput.setSelection(urlInput.getText().length());
            }
        });
    }

    private void addPreset(LinearLayout parent, String text, boolean control) {
        Button b = new Button(getContext());
        styleButton(b, COLOR_IDLE, COLOR_TEXT, 12, 9);
        b.setText(text);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setOnClickListener(v -> applyPreset(control));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        params.rightMargin = dp(8);
        parent.addView(b, params);
    }

    private void applyPreset(boolean control) {
        methodButton.setText(control ? "POST" : "GET");
        urlInput.setText(provider.currentBaseUrl() + (control ? "/api/control" : "/api/state"));
        lastBase = provider.currentBaseUrl();
        if (control) {
            headersInput.setText("Content-Type: application/json; charset=utf-8");
            bodyInput.setText(DEFAULT_BODY);
        }
        updateMethodState();
        hideKeyboard();
    }

    private void updateMethodState() {
        boolean post = "POST".contentEquals(methodButton.getText());
        bodyInput.setEnabled(post);
        bodyInput.setAlpha(post ? 1f : 0.4f);
    }

    public void syncBase(String newBase) {
        String current = urlInput.getText().toString().trim();
        if (current.startsWith(lastBase)) {
            current = newBase + current.substring(lastBase.length());
            urlInput.setText(current);
        } else if (current.isEmpty()) {
            urlInput.setText(newBase + "/api/state");
        }
        lastBase = newBase;
    }

    // --------------------------------------------------------------- send

    private void send() {
        String method = methodButton.getText().toString();
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            url = provider.currentBaseUrl() + "/api/state";
            urlInput.setText(url);
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
            urlInput.setText(url);
        }
        final String finalMethod = method;
        final String finalUrl = url;
        final String headers = headersInput.getText().toString();
        final String body = "POST".equals(method) ? bodyInput.getText().toString() : null;
        final int seq = ++requestSeq;

        hideKeyboard();
        statusText.setText("请求中…");
        statusText.setTextColor(COLOR_DIM);
        responseText.setText("");

        io.execute(() -> {
            Response result = null;
            String failure = null;
            try {
                result = perform(finalMethod, finalUrl, headers, body);
            } catch (IOException e) {
                failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final Response response = result;
            final String error = failure;
            ui.post(() -> {
                if (seq != requestSeq) {
                    return;
                }
                if (response != null) {
                    showResponse(finalMethod, finalUrl, headers, body, response);
                } else {
                    statusText.setText("请求失败：" + error);
                    statusText.setTextColor(COLOR_ERR);
                    responseText.setText("");
                }
            });
        });
    }

    private void showResponse(String method, String url, String headers, String body, Response r) {
        boolean ok = r.code >= 200 && r.code < 400;
        int color = ok ? COLOR_OK : COLOR_WARN;
        statusText.setText("HTTP " + r.code + " " + r.message
                + " · " + r.elapsedMs + " ms"
                + " · " + readableSize(r.body.getBytes(StandardCharsets.UTF_8).length));
        statusText.setTextColor(color);
        responseText.setText(r.body.isEmpty() ? "(空响应)" : r.body);
        saveHistory(method, url, headers, body, r);
    }

    private static Response perform(String method, String urlStr, String rawHeaders, String body)
            throws IOException {
        HttpURLConnection connection = null;
        long start = System.currentTimeMillis();
        try {
            connection = (HttpURLConnection) new URL(urlStr).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestMethod(method);

            Map<String, String> headers = parseHeaders(rawHeaders);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if ("POST".equals(method)) {
                connection.setDoOutput(true);
                if (!headers.containsKey("Content-Type")) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                }
                OutputStream out = connection.getOutputStream();
                try {
                    out.write((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } finally {
                    out.close();
                }
            }

            int code = connection.getResponseCode();
            String message = connection.getResponseMessage();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = in == null ? "" : readAll(in);
            if (in != null) {
                in.close();
            }
            long elapsed = System.currentTimeMillis() - start;
            return new Response(code, message == null ? "" : message, elapsed, text);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Map<String, String> parseHeaders(String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null) {
            return headers;
        }
        for (String line : raw.split("\n")) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!key.isEmpty()) {
                headers.put(key, value);
            }
        }
        return headers;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String readableSize(int bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f);
    }

    private static final class Response {
        final int code;
        final String message;
        final long elapsedMs;
        final String body;

        Response(int code, String message, long elapsedMs, String body) {
            this.code = code;
            this.message = message;
            this.elapsedMs = elapsedMs;
            this.body = body;
        }
    }

    // ------------------------------------------------------------ history

    private void saveHistory(String method, String url, String headers, String body, Response r) {
        JSONArray current = loadHistory();
        JSONArray next = new JSONArray();
        JSONObject item = new JSONObject();
        try {
            item.put("m", method);
            item.put("u", url);
            item.put("h", headers);
            item.put("b", body == null ? "" : body);
            item.put("c", r.code);
            item.put("t", r.elapsedMs);
        } catch (JSONException ignored) {
        }
        next.put(item);
        for (int i = 0; i < current.length() && next.length() < HISTORY_LIMIT; i++) {
            JSONObject old = current.optJSONObject(i);
            if (old != null) {
                next.put(old);
            }
        }
        prefs.edit().putString(KEY_HISTORY, next.toString()).apply();
    }

    private JSONArray loadHistory() {
        try {
            return new JSONArray(prefs.getString(KEY_HISTORY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    private void showHistory() {
        JSONArray history = loadHistory();
        if (history.length() == 0) {
            Toast.makeText(getContext(), "还没有历史请求", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] lines = new String[history.length()];
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            lines[i] = item == null ? "" :
                    item.optString("m") + " " + item.optString("u")
                            + "\nHTTP " + item.optInt("c") + " · " + item.optLong("t") + " ms";
        }
        new AlertDialog.Builder(getContext())
                .setTitle("最近请求（点击载入）")
                .setItems(lines, (dialog, which) -> loadHistoryItem(history.optJSONObject(which)))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void loadHistoryItem(JSONObject item) {
        if (item == null) {
            return;
        }
        methodButton.setText(item.optString("m", "GET"));
        urlInput.setText(item.optString("u"));
        headersInput.setText(item.optString("h"));
        if (item.has("b")) {
            bodyInput.setText(item.optString("b"));
        }
        updateMethodState();
    }

    // ------------------------------------------------------------- common

    private TextView label(String text) {
        TextView view = new TextView(getContext());
        view.setText(text);
        view.setTextColor(COLOR_DIM);
        view.setTextSize(12);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private EditText input(int heightDp, boolean monospace) {
        EditText edit = new EditText(getContext());
        edit.setBackground(rounded(COLOR_CARD, COLOR_STROKE, 10));
        edit.setBackgroundTintList(null);
        edit.setTextColor(COLOR_TEXT);
        edit.setHintTextColor(COLOR_DIM);
        edit.setTextSize(13);
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));
        edit.setGravity(Gravity.TOP | Gravity.START);
        if (monospace) {
            edit.setTypeface(Typeface.MONOSPACE);
        }
        if (heightDp > 0) {
            edit.setMinLines(2);
            edit.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            edit.setSingleLine(false);
        }
        return edit;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lpMatch(int height) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
    }

    private void styleButton(Button b, int fill, int textColor, float textSizeSp, float radiusDp) {
        b.setAllCaps(false);
        b.setTextSize(textSizeSp);
        b.setTextColor(textColor);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setIncludeFontPadding(false);
        b.setStateListAnimator(null);
        b.setBackground(rounded(fill, 0, radiusDp));
        b.setBackgroundTintList(null);
    }

    private GradientDrawable rounded(int fill, int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(dp(1), strokeColor);
        }
        return d;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        }
    }
}
