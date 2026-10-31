package com.example.ws2812b;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements DebugPanel.AddressProvider {

    private static final int SEG_COUNT = 11;
    private static final int PORT = 8080;
    private static final int TIMEOUT_MS = 4000;

    private static final String DEFAULT_ADDRESS = "172.20.10.7:8080";
    private static final String PREFS_NAME = "ws2812b";
    private static final String KEY_ADDRESS = "address";

    private static final String[] SEG_NAMES = {
            "一组 1-4", "一组 5-7", "一组 8-12", "一组 13-18", "一组 19-23", "一组 23-30",
            "二组 1-5", "二组 7-9", "二组 9-14", "二组 19-22", "二组 23-26"
    };

    private static final String[] SEG_VALUES = {"0", "1", "x"};
    private static final String[] SEG_ACTIONS = {"正", "反", "灭"};

    private static final int COLOR_BG = 0xFF111111;
    private static final int COLOR_CARD = 0xFF1C1C1E;
    private static final int COLOR_BAR = 0xFF1A1A1C;
    private static final int COLOR_ON = 0xFF0A84FF;
    private static final int COLOR_REV = 0xFFFF9F0A;
    private static final int COLOR_OFF = 0xFF636366;
    private static final int COLOR_IDLE = 0xFF242426;
    private static final int COLOR_STROKE = 0xFF3A3A3C;
    private static final int COLOR_TEXT = 0xFFE5E5EA;
    private static final int COLOR_TEXT_DIM = 0xFF9A9A9F;

    private final String[] state = {"x", "x", "x", "x", "x", "x", "x", "x", "x", "x", "x"};
    private final Button[][] segButtons = new Button[SEG_COUNT][3];

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences prefs;
    private EditText addressInput;
    private TextView statusText;
    private int requestSeq = 0;

    private LinearLayout controlContainer;
    private DebugPanel debugPanel;
    private Button tabControl;
    private Button tabDebug;

    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        buildUi();
        refreshSegments();
    }

    @Override
    protected void onResume() {
        super.onResume();
        fetchState();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        root.setFitsSystemWindows(true);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("灯带控制");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), dp(16), dp(16), dp(2));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("二组灯带 · 11 段 · 正流 / 倒流 / 熄灭");
        subtitle.setTextColor(COLOR_TEXT_DIM);
        subtitle.setTextSize(12);
        subtitle.setPadding(dp(16), 0, dp(16), dp(6));
        root.addView(subtitle);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(16), dp(6), dp(16), dp(6));
        root.addView(tabs);

        tabControl = makeTab("控制");
        tabDebug = makeTab("调试");
        tabs.addView(tabControl, tabParams());
        tabs.addView(tabDebug, tabParams());
        tabControl.setOnClickListener(v -> showTab(false));
        tabDebug.setOnClickListener(v -> showTab(true));

        controlContainer = new LinearLayout(this);
        controlContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(controlContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(6), dp(16), dp(2));
        controlContainer.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addressInput = new EditText(this);
        addressInput.setSingleLine(true);
        addressInput.setText(prefs.getString(KEY_ADDRESS, DEFAULT_ADDRESS));
        addressInput.setSelection(addressInput.getText().length());
        addressInput.setHint("板子 IP，例如 172.20.10.7");
        addressInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressInput.setTextColor(Color.WHITE);
        addressInput.setHintTextColor(COLOR_TEXT_DIM);
        addressInput.setTextSize(15);
        addressInput.setBackground(rounded(COLOR_CARD, COLOR_STROKE, 10));
        addressInput.setBackgroundTintList(null);
        addressInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        header.addView(addressInput, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button connect = new Button(this);
        connect.setText("保存并连接");
        styleButton(connect, COLOR_ON, Color.WHITE, 15, 10);
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(dp(112), dp(46));
        connectParams.leftMargin = dp(8);
        header.addView(connect, connectParams);
        connect.setOnClickListener(v -> {
            prefs.edit().putString(KEY_ADDRESS, addressInput.getText().toString().trim()).apply();
            hideKeyboard();
            debugPanel.syncBase(baseUrl());
            fetchState();
        });

        statusText = new TextView(this);
        statusText.setText("正在同步状态…");
        statusText.setTextColor(COLOR_TEXT_DIM);
        statusText.setTextSize(13);
        statusText.setPadding(dp(16), dp(8), dp(16), dp(4));
        controlContainer.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        controlContainer.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(6), dp(16), dp(6));
        scroll.addView(list);

        for (int i = 0; i < SEG_COUNT; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(COLOR_CARD, 0, 12));
            row.setPadding(dp(12), dp(8), dp(8), dp(8));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.bottomMargin = dp(6);
            list.addView(row, rowParams);

            TextView label = new TextView(this);
            label.setText(SEG_NAMES[i]);
            label.setTextColor(COLOR_TEXT);
            label.setTextSize(14);
            row.addView(label, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            for (int j = 0; j < 3; j++) {
                Button b = new Button(this);
                b.setText(SEG_ACTIONS[j]);
                b.setTextSize(13);
                b.setAllCaps(false);
                b.setMinWidth(0);
                b.setMinimumWidth(0);
                b.setMinHeight(0);
                b.setMinimumHeight(0);
                b.setPadding(0, 0, 0, 0);
                b.setIncludeFontPadding(false);
                b.setStateListAnimator(null);
                b.setBackgroundTintList(null);
                LinearLayout.LayoutParams bParams = new LinearLayout.LayoutParams(dp(52), dp(38));
                bParams.leftMargin = dp(6);
                row.addView(b, bParams);
                final int seg = i;
                final int action = j;
                b.setOnClickListener(v -> onSegmentClicked(seg, action));
                segButtons[i][j] = b;
            }
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(COLOR_BAR);
        bar.setPadding(dp(12), dp(10), dp(12), dp(10));
        controlContainer.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addBarButton(bar, "全正流", COLOR_ON).setOnClickListener(v -> applyAll("0"));
        addBarButton(bar, "全倒流", COLOR_REV).setOnClickListener(v -> applyAll("1"));
        addBarButton(bar, "全灭", COLOR_OFF).setOnClickListener(v -> applyAll("x"));

        debugPanel = new DebugPanel(this, io, this);
        root.addView(debugPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        showTab(false);
    }

    private Button makeTab(String text) {
        Button b = new Button(this);
        styleButton(b, COLOR_IDLE, COLOR_TEXT_DIM, 14, 9);
        b.setText(text);
        return b;
    }

    private LinearLayout.LayoutParams tabParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        return params;
    }

    private void showTab(boolean debug) {
        controlContainer.setVisibility(debug ? View.GONE : View.VISIBLE);
        debugPanel.setVisibility(debug ? View.VISIBLE : View.GONE);
        styleButton(tabControl, debug ? COLOR_IDLE : COLOR_ON,
                debug ? COLOR_TEXT_DIM : Color.WHITE, 14, 9);
        styleButton(tabDebug, debug ? COLOR_ON : COLOR_IDLE,
                debug ? Color.WHITE : COLOR_TEXT_DIM, 14, 9);
        hideKeyboard();
    }

    @Override
    public String currentBaseUrl() {
        return baseUrl();
    }

    private Button addBarButton(LinearLayout parent, String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        styleButton(b, color, Color.WHITE, 15, 10);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1f);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
        parent.addView(b, params);
        return b;
    }

    private void styleButton(Button b, int fill, int textColor, int textSizeSp, float radiusDp) {
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

    private void refreshSegments() {
        for (int i = 0; i < SEG_COUNT; i++) {
            String v = state[i];
            styleSegment(segButtons[i][0], "0".equals(v), COLOR_ON);
            styleSegment(segButtons[i][1], "1".equals(v), COLOR_REV);
            styleSegment(segButtons[i][2], "x".equals(v), COLOR_OFF);
        }
    }

    private void styleSegment(Button b, boolean active, int color) {
        if (b == null) {
            return;
        }
        b.setBackground(active ? rounded(color, 0, 9) : rounded(COLOR_IDLE, COLOR_STROKE, 9));
        b.setBackgroundTintList(null);
        b.setTextColor(active ? Color.WHITE : COLOR_TEXT_DIM);
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
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(addressInput.getWindowToken(), 0);
        }
        addressInput.clearFocus();
    }

    // ------------------------------------------------------------ actions

    private void onSegmentClicked(int seg, int action) {
        String value = SEG_VALUES[action];
        if (value.equals(state[seg])) {
            return;
        }
        state[seg] = value;
        refreshSegments();
        sendState();
    }

    private void applyAll(String value) {
        boolean changed = false;
        for (int i = 0; i < SEG_COUNT; i++) {
            if (!value.equals(state[i])) {
                state[i] = value;
                changed = true;
            }
        }
        if (!changed) {
            return;
        }
        refreshSegments();
        sendState();
    }

    // --------------------------------------------------------------- http

    private String baseUrl() {
        String raw = addressInput.getText().toString().trim();
        if (raw.isEmpty()) {
            raw = DEFAULT_ADDRESS;
        }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://" + raw;
        }
        while (raw.endsWith("/")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        String authority = raw.substring(raw.indexOf("://") + 3);
        if (!authority.contains(":")) {
            raw = raw + ":" + PORT;
        }
        return raw;
    }

    private void fetchState() {
        final int seq = ++requestSeq;
        final String url = baseUrl() + "/api/state";
        statusText.setText("正在同步状态…");
        statusText.setTextColor(COLOR_TEXT_DIM);
        io.execute(() -> {
            String[] parsed = null;
            String error = null;
            try {
                parsed = parseState(request(url, null));
            } catch (IOException e) {
                error = describe(e);
            }
            final String[] result = parsed;
            final String failure = error;
            ui.post(() -> {
                if (seq != requestSeq) {
                    return;
                }
                if (result != null) {
                    System.arraycopy(result, 0, state, 0, SEG_COUNT);
                    refreshSegments();
                    statusText.setText("已连接 · 11 段状态已同步");
                    statusText.setTextColor(COLOR_ON);
                } else {
                    statusText.setText("连接失败：" + failure);
                    statusText.setTextColor(0xFFFF453A);
                }
            });
        });
    }

    private void sendState() {
        final int seq = ++requestSeq;
        final String body = toJson(state.clone());
        final String url = baseUrl() + "/api/control";
        statusText.setText("正在发送…");
        statusText.setTextColor(COLOR_TEXT_DIM);
        io.execute(() -> {
            String error = null;
            try {
                request(url, body);
            } catch (IOException e) {
                error = describe(e);
            }
            final String failure = error;
            ui.post(() -> {
                if (seq != requestSeq) {
                    return;
                }
                if (failure == null) {
                    statusText.setText("已发送 · " + summarize(state));
                    statusText.setTextColor(COLOR_ON);
                } else {
                    statusText.setText("发送失败：" + failure);
                    statusText.setTextColor(0xFFFF453A);
                }
            });
        });
    }

    private static String summarize(String[] values) {
        int on = 0;
        int rev = 0;
        int off = 0;
        for (String v : values) {
            if ("0".equals(v)) {
                on++;
            } else if ("1".equals(v)) {
                rev++;
            } else {
                off++;
            }
        }
        return "正 " + on + " · 反 " + rev + " · 灭 " + off;
    }

    private static String request(String urlStr, String body) throws IOException {
        HttpURLConnection connection = null;
        OutputStream out = null;
        InputStream in = null;
        try {
            connection = (HttpURLConnection) new URL(urlStr).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                out = connection.getOutputStream();
                out.write(body.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new HttpStatusException(code);
            }
            in = connection.getInputStream();
            return readAll(in);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
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

    private static String[] parseState(String json) throws IOException {
        try {
            JSONArray array = new JSONArray(json);
            if (array.length() < SEG_COUNT) {
                throw new IOException("数据格式不正确");
            }
            String[] values = new String[SEG_COUNT];
            for (int i = 0; i < SEG_COUNT; i++) {
                String v = array.getString(i);
                values[i] = "0".equals(v) ? "0" : ("1".equals(v) ? "1" : "x");
            }
            return values;
        } catch (JSONException e) {
            throw new IOException("数据格式不正确", e);
        }
    }

    private static String toJson(String[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values[i]).append('"');
        }
        return sb.append(']').toString();
    }

    private static String describe(IOException e) {
        if (e instanceof HttpStatusException) {
            return "板子返回 HTTP " + ((HttpStatusException) e).code;
        }
        return "连不上板子，请检查 IP 和 WiFi";
    }
}
