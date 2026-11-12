package com.novel.player;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int MAX_CHUNK = 180;

    private WebView webView;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private ValueCallback<Uri[]> fileChooser;
    private final Map<String, android.speech.tts.Voice> voiceMap = new HashMap<>();
    private String currentVoiceName = "";

    private int queueTotal = 0;
    private int doneCount = 0;
    private boolean queueActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webview);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileChooser != null) fileChooser.onReceiveValue(null);
                fileChooser = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent, REQ_FILE);
                } catch (Exception e) {
                    fileChooser = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new JsApi(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/index.html");
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true;
                try {
                    if (tts.getLanguage() == null) {
                        int r = tts.setLanguage(Locale.CHINESE);
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale.getDefault());
                        }
                    }
                    for (android.speech.tts.Voice v : tts.getVoices()) {
                        voiceMap.put(v.getName(), v);
                    }
                } catch (Exception ignored) {}
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        runJs("window.__onTtsStart(" + id + ")");
                    }

                    @Override
                    public void onDone(String id) {
                        countDown();
                    }

                    @Override
                    public void onError(String id) {
                        countDown();
                    }
                });
            } else {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "语音引擎初始化失败，请到系统设置检查「文字转语音」", Toast.LENGTH_LONG).show());
            }
        });
    }

    private synchronized void countDown() {
        if (!queueActive || queueTotal <= 0) return;
        doneCount++;
        if (doneCount >= queueTotal) {
            queueActive = false;
            queueTotal = 0;
            doneCount = 0;
            runJs("window.__onTtsDone()");
        }
    }

    private void runJs(String code) {
        webView.post(() -> {
            try { webView.evaluateJavascript(code, null); } catch (Exception ignored) {}
        });
    }

    private void pickVoice(String name) {
        if (name == null || name.isEmpty() || name.equals(currentVoiceName)) return;
        currentVoiceName = name;
        android.speech.tts.Voice v = voiceMap.get(name);
        if (v != null && tts != null) {
            try { tts.setVoice(v); } catch (Exception ignored) {}
        }
    }

    private String voicesJson() {
        try {
            JSONArray arr = new JSONArray();
            if (tts != null) {
                for (android.speech.tts.Voice v : tts.getVoices()) {
                    Locale l = v.getLocale();
                    if (l == null || !l.getLanguage().toLowerCase(Locale.ROOT).startsWith("zh")) continue;
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    String label = v.getName().replace("cmn-", "").replace("zh-", "");
                    o.put("label", label + " (" + l.toString() + ")");
                    arr.put(o);
                }
                if (arr.length() == 0) {
                    for (android.speech.tts.Voice v : tts.getVoices()) {
                        JSONObject o = new JSONObject();
                        o.put("name", v.getName());
                        o.put("label", v.getName());
                        arr.put(o);
                    }
                }
            }
            return arr.toString();
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> splitLong(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        text = text.trim();
        if (text.length() <= MAX_CHUNK) {
            out.add(text);
            return out;
        }
        int i = 0;
        while (i < text.length()) {
            int end = Math.min(i + MAX_CHUNK, text.length());
            String seg = text.substring(i, end);
            int cut = -1;
            for (int j = seg.length() - 1; j >= seg.length() / 2; j--) {
                char c = seg.charAt(j);
                if (c == '。' || c == '！' || c == '？' || c == '…' || c == '；' || c == '，' || c == '、'
                        || c == '.' || c == '!' || c == '?' || c == '\n') {
                    cut = j;
                    break;
                }
            }
            if (cut > 0) {
                seg = seg.substring(0, cut + 1);
                i += cut + 1;
            } else {
                i = end;
            }
            out.add(seg);
        }
        return out;
    }

    private class JsApi {
        @JavascriptInterface
        public void ttsInit() {
            if (ttsReady) {
                String json = voicesJson();
                runJs("window.__onTtsReady(" + json + ")");
            }
        }

        @JavascriptInterface
        public void ttsSpeak(String text, String idx, float rate, float pitch, String voice) {
            if (tts == null || !ttsReady || text == null) return;
            pickVoice(voice);
            tts.setSpeechRate(Math.max(0.2f, Math.min(3f, rate)));
            tts.setPitch(Math.max(0.5f, Math.min(2f, pitch)));
            for (String c : splitLong(text)) {
                if (c.isEmpty()) continue;
                queueTotal++;
                if (tts.speak(c, TextToSpeech.QUEUE_ADD, null, idx) == TextToSpeech.ERROR) {
                    queueTotal--;
                }
            }
        }

        @JavascriptInterface
        public void ttsMarkQueueActive() {
            queueActive = true;
            doneCount = 0;
        }

        @JavascriptInterface
        public void ttsStop() {
            if (tts == null) return;
            queueActive = false;
            queueTotal = 0;
            doneCount = 0;
            tts.stop();
        }

        @JavascriptInterface
        public String ttsGetVoices() {
            if (!ttsReady) return null;
            return voicesJson();
        }

        @JavascriptInterface
        public void ttsSetKeepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            if (fileChooser == null) return;
            Uri[] uris = null;
            if (resultCode == RESULT_OK && data != null) {
                try { uris = WebChromeClient.FileChooserParams.parseResult(resultCode, data); } catch (Exception ignored) {}
            }
            fileChooser.onReceiveValue(uris);
            fileChooser = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
