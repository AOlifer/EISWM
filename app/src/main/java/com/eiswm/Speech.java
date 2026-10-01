package com.eiswm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.speech.tts.TextToSpeech;

import org.json.JSONObject;

import java.util.List;

/**
 * Озвучка текста. Голосовой ассистент com.bw.asr — тем же вызовом, которым лаунчер озвучивает
 * погоду; без него — стандартный синтезатор Android, если он установлен.
 * На Evolute i-Space (прошивка Yato1 2.23) нет ни com.bw.asr, ни синтезатора Android: лаунчер
 * погоду там не озвучивает (couldSpeakWeather() всегда false). Нужно поставить синтезатор,
 * например RHVoice.
 */
final class Speech {
    private static final String CAR_VOICE = "com.bw.asr";
    private static final String CAR_VOICE_ACTION = "com.bw.asr.BW_RECEIVE_SERVICE_ACTION";

    private final Context context;
    private TextToSpeech tts;

    Speech(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Есть ли голосовой ассистент машины. */
    static boolean hasCarVoice(Context c) {
        try {
            c.getPackageManager().getPackageInfo(CAR_VOICE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Название синтезатора для экрана настроек или null, если озвучивать нечем. */
    static String engineName(Context c) {
        if (hasCarVoice(c)) return label(c, CAR_VOICE);
        TextToSpeech probe = new TextToSpeech(c.getApplicationContext(), status -> {
        });
        List<TextToSpeech.EngineInfo> engines = probe.getEngines();
        probe.shutdown();
        return engines.isEmpty() ? null : engines.get(0).label;
    }

    private static String label(Context c, String pkg) {
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            return String.valueOf(c.getPackageManager().getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    /** Озвучить текст; предыдущая озвучка этого объекта прерывается. */
    void speak(String text) {
        if (hasCarVoice(context)) {
            speakWithCarVoice(text);
            return;
        }
        shutdown();
        tts = new TextToSpeech(context, status -> {
            if (status != TextToSpeech.SUCCESS || tts == null) return;
            // Канал машины WARNING, как у звука прощания: он слышен и после выключения зажигания.
            tts.setAudioAttributes(Farewell.carWarningAttributes());
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "eiswm");
        });
    }

    /** Вызов, которым лаунчер озвучивает погоду (BwWeatherSpeakController.openTTS). */
    private void speakWithCarVoice(String text) {
        try {
            JSONObject json = new JSONObject().put("tts", text);
            Intent i = new Intent(CAR_VOICE_ACTION).setPackage(CAR_VOICE)
                    .putExtra("key", 100)
                    .putExtra("command", "bw.only.tts")
                    .putExtra("json", json.toString());
            context.startService(i);
        } catch (Exception e) {
            CarDiag.log(context, "SPEECH car voice failed: " + e);
        }
    }

    void shutdown() {
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
