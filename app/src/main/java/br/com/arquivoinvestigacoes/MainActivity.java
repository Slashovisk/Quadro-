package br.com.arquivoinvestigacoes;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.widget.FrameLayout;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Abre a página do Arquivo de Investigações dentro de um WebView.
 * A cada abertura busca a versão mais nova no GitHub; se houver, guarda e usa
 * (na hora, se chegou logo ao abrir; senão na próxima abertura).
 * Os quadros ficam no armazenamento do WebView (mesma origem fixa), então sobrevivem às atualizações.
 */
public class MainActivity extends Activity {
    private static final String BASE = "https://arquivo.local/";
    private static final int REQ_FILE = 7;

    private WebView web;
    private ValueCallback<Uri[]> fileCb;
    private String loaded = "";
    private long t0;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        t0 = SystemClock.elapsedRealtime();
        web = new WebView(this);
        // Desde o Android 15 o app ocupa a tela toda, por trás das barras do sistema.
        // Este contêiner deixa a página só na área livre (sem ficar embaixo dos botões de navegação).
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1B1410);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, in) -> {
            int pl, pt, pr, pb;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets sb = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = in.getInsets(WindowInsets.Type.ime());
                pl = sb.left; pt = sb.top; pr = sb.right; pb = Math.max(sb.bottom, ime.bottom);
            } else {
                pl = in.getSystemWindowInsetLeft(); pt = in.getSystemWindowInsetTop();
                pr = in.getSystemWindowInsetRight(); pb = in.getSystemWindowInsetBottom();
            }
            v.setPadding(pl, pt, pr, pb);
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : in.consumeSystemWindowInsets();
        });

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith(BASE) || u.startsWith("blob:") || u.startsWith("data:")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl()));
                } catch (Exception ignored) { }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    startActivityForResult(p.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });

        String cached = readFile(cacheFile());
        loaded = cached != null ? cached : readAsset();
        show(loaded);
        checkUpdate();
    }

    private void show(String html) {
        web.loadDataWithBaseURL(BASE, html, "text/html", "UTF-8", null);
    }

    private File cacheFile() {
        return new File(getFilesDir(), "index-atualizado.html");
    }

    private String readAsset() {
        try (InputStream in = getAssets().open("index.html")) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<p>Falha ao abrir o aplicativo.</p>";
        }
    }

    private String readFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    private void checkUpdate() {
        new Thread(() -> {
            try {
                URL u = new URL(BuildConfig.UPDATE_URL + "?t=" + (System.currentTimeMillis() / 60000));
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(15000);
                if (c.getResponseCode() != 200) return;
                String html;
                try (InputStream in = c.getInputStream()) {
                    html = new String(readAll(in), StandardCharsets.UTF_8);
                }
                if (html.length() < 2000 || !html.contains("</html>")) return;
                if (html.equals(loaded)) return;
                File tmp = new File(getFilesDir(), "index-novo.tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(html.getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(cacheFile())) return;
                final String novo = html;
                // Só troca na hora se chegou nos primeiros segundos (antes de a pessoa mexer no quadro).
                if (SystemClock.elapsedRealtime() - t0 < 6000) {
                    ui.post(() -> {
                        loaded = novo;
                        show(novo);
                    });
                }
            } catch (Exception ignored) {
                // sem internet: segue com a versão guardada
            }
        }).start();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        // Primeiro fecha o que estiver aberto na página (visualizador, arquivo de quadros); senão sai.
        web.evaluateJavascript(
            "(function(){var l=document.getElementById('lb');if(l&&!l.hidden){if(window.closeV)closeV();else l.hidden=true;return 1}"
            + "var h=document.getElementById('hClose');var hm=document.getElementById('home');"
            + "if(hm&&!hm.hidden&&h&&!h.hidden){h.click();return 1}return 0})()",
            r -> {
                if (!"1".equals(r)) {
                    MainActivity.super.onBackPressed();
                }
            });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
