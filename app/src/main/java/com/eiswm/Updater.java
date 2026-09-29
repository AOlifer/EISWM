package com.eiswm;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Обновления с сервера: файл JSON по адресу update_url (config.xml) описывает последнюю версию,
 * APK скачивается, проверяется и ставится через {@link PackageInstaller}. На машине приложение
 * работает с system uid и ставит обновление само, без системного установщика.
 *
 * <pre>
 * {"versionCode": 6, "versionName": "1.6", "date": "2026-10-05",
 *  "apk": "https://…/EISWM-1.6.apk", "size": 5123456, "sha256": "…",
 *  "changes": {"ru": "…", "en": "…", "de": "…"}}
 * </pre>
 * Обязательны versionCode, versionName и apk; остальное по желанию.
 */
final class Updater {
    static final String PREF_LAST_CHECK = "update_last_check";
    /** Автоматическая проверка при запуске — не чаще раза в сутки. */
    static final long AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_JSON_BYTES = 64 * 1024;

    /** Описание версии на сервере. */
    static final class Release {
        int versionCode;
        String versionName, date, apk, sha256, changes;
        long size;
    }

    interface Progress {
        /** @param total -1, если размер неизвестен. */
        void onProgress(long done, long total);

        boolean cancelled();
    }

    private final Context context;

    Updater(Context context) {
        this.context = context.getApplicationContext();
    }

    int currentVersionCode() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** Можно ли в этой сборке ставить обновление (в варианте emulator — нет, у него другая подпись). */
    boolean canInstall() {
        return context.getResources().getBoolean(R.bool.update_can_install);
    }

    /** Читает JSON с сервера. Вызывать в фоновом потоке. */
    Release fetchLatest() throws IOException {
        HttpURLConnection c = open(context.getString(R.string.update_url));
        try {
            byte[] body = readAll(c.getInputStream(), MAX_JSON_BYTES);
            JSONObject o = new JSONObject(new String(body, "UTF-8"));
            Release r = new Release();
            r.versionCode = o.getInt("versionCode");
            r.versionName = o.getString("versionName");
            r.apk = o.getString("apk");
            r.date = o.optString("date", "");
            r.sha256 = o.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
            r.size = o.optLong("size", -1);
            r.changes = localized(o.optJSONObject("changes"));
            if (!r.apk.startsWith("https://") && !r.apk.startsWith("http://")) {
                throw new IOException("bad apk url");
            }
            return r;
        } catch (org.json.JSONException e) {
            throw new IOException(e);
        } finally {
            c.disconnect();
        }
    }

    /** Описание изменений на языке системы, иначе английское, иначе любое. */
    private static String localized(JSONObject changes) {
        if (changes == null) return "";
        String lang = Locale.getDefault().getLanguage();
        String s = changes.optString(lang, "");
        if (s.isEmpty()) s = changes.optString("en", "");
        if (s.isEmpty() && changes.keys().hasNext()) s = changes.optString(changes.keys().next(), "");
        return s;
    }

    /**
     * Скачивает APK в кэш и проверяет его: контрольная сумма, имя пакета и номер версии.
     * @return готовый файл или null, если загрузку отменили.
     */
    File download(Release r, Progress progress) throws IOException {
        File dir = new File(context.getCacheDir(), "update");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache dir");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File apk = new File(dir, "EISWM-" + r.versionCode + ".apk");

        HttpURLConnection c = open(r.apk);
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException(e);
        }
        long total = c.getContentLength() > 0 ? c.getContentLength() : r.size;
        try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (progress.cancelled()) {
                    out.close();
                    apk.delete();
                    return null;
                }
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                progress.onProgress(done, total);
            }
        } finally {
            c.disconnect();
        }

        if (!r.sha256.isEmpty() && !r.sha256.equals(hex(sha.digest()))) {
            apk.delete();
            throw new IOException(context.getString(R.string.update_error_checksum));
        }
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null || !context.getPackageName().equals(info.packageName)) {
            apk.delete();
            throw new IOException(context.getString(R.string.update_error_package));
        }
        if (info.versionCode <= currentVersionCode()) {
            apk.delete();
            throw new IOException(context.getString(R.string.update_error_version));
        }
        return apk;
    }

    /**
     * Передаёт APK установщику пакетов. Результат придёт в {@link ResultReceiver};
     * при успехе процесс приложения завершится, а {@link ResultReceiver} откроет его снова.
     */
    void install(File apk) throws IOException {
        PackageInstaller pi = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(context, ResultReceiver.class);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, i, PendingIntent.FLAG_UPDATE_CURRENT);
            s.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            pi.abandonSession(id);
            throw e;
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Cache-Control", "no-cache");
        int code = c.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect();
            throw new IOException("HTTP " + code);
        }
        return c;
    }

    private static byte[] readAll(InputStream in, int limit) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = s.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IOException("response too large");
            }
            return out.toByteArray();
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    /**
     * Результат установки. Если системе нужно подтверждение пользователя, открывает его;
     * при ошибке сообщает о ней.
     */
    public static class ResultReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(confirm);
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                android.widget.Toast.makeText(context,
                        context.getString(R.string.update_error_install, msg == null ? String.valueOf(status) : msg),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    /** После обновления снова открывает приложение: установщик завершил его процесс. */
    public static class ReplacedReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
            File[] left = new File(context.getCacheDir(), "update").listFiles();
            if (left != null) for (File f : left) f.delete();
            if (!context.getSharedPreferences(BaseActivity.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(PREF_REOPEN, false)) return;
            context.getSharedPreferences(BaseActivity.PREFS, Context.MODE_PRIVATE)
                    .edit().remove(PREF_REOPEN).apply();
            Intent i = new Intent(context, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        }
    }

    /** Флаг «открыть приложение после установки»: ставится перед установкой из приложения. */
    static final String PREF_REOPEN = "update_reopen";
}
