package com.malcerz.btvoicerecorder;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecordingConfiguration;
import android.media.AudioRouting;
import android.media.MediaRecorder;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TAG = "BTVoiceRecorder48k";
    private static final int PERMISSION_REQUEST = 101;
    private static final int AUDIO_HZ = 48_000;
    private static final int AUDIO_BITRATE = 192_000;

    private static final int[] SOURCES = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED
    };
    private static final String[] MODE_NAMES = {"MIC", "ROZMOWA", "ROZPOZNAWANIE", "BEZ OBRÓBKI"};
    private static final String[] MODE_FILES = {"MIC", "CALL", "RECOGNITION", "UNPROCESSED"};

    private AudioManager audio;
    private TextView status;
    private TextView timer;
    private Button buttonMode;
    private Button buttonCheck;
    private Button buttonRecord;
    private Button buttonInput;
    private Button buttonCopy;
    private final List<AudioDeviceInfo> btInputs = new ArrayList<>();
    private int selectedDeviceIndex = 0;
    private String lastReport = "";
    private final Handler main = new Handler(Looper.getMainLooper());

    private int selectedMode = 0;
    private AudioDeviceInfo btInput;
    private AudioDeviceInfo btCommunication;
    private MediaRecorder recorder;
    private Uri fileUri;
    private ParcelFileDescriptor fileDescriptor;
    private int originalAudioMode = AudioManager.MODE_NORMAL;
    private boolean routeOwned = false;
    private boolean pendingStart = false;
    private boolean recording = false;
    private boolean aborting = false;
    private long startMs = 0;
    private String audioInfo = "";
    private String lastFile = "";

    private final Runnable clock = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            long seconds = Math.max(0, (SystemClock.elapsedRealtime() - startMs) / 1000L);
            timer.setText(String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
            main.postDelayed(this, 250);
        }
    };

    private final AudioRouting.OnRoutingChangedListener routeListener = router -> {
        if (!recording || aborting || recorder == null) return;
        AudioDeviceInfo routed = recorder.getRoutedDevice();
        Log.i(TAG, "Recording route changed: " + describe(routed));
        if (!isBtInput(routed) || btInput == null || routed.getType() != btInput.getType()) {
            failClosed("Utracono mikrofon BT (route=" + describe(routed) + "). Nagranie usunięte.");
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        status = findViewById(R.id.status);
        timer = findViewById(R.id.timer);
        buttonMode = findViewById(R.id.mode);
        buttonCheck = findViewById(R.id.check_bt);
        buttonRecord = findViewById(R.id.record);
        buttonInput = findViewById(R.id.choose_input);
        buttonCopy = findViewById(R.id.copy_report);
        buttonInput.setOnClickListener(v -> {
            if (recording || pendingStart) return;
            scanDevices();
            if (btInputs.isEmpty()) { scanAndShow(); return; }
            selectedDeviceIndex = (selectedDeviceIndex + 1) % btInputs.size();
            scanAndShow();
        });
        buttonCopy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("BT audio diagnostics",
                    lastReport.isEmpty() ? status.getText().toString() : lastReport));
            android.widget.Toast.makeText(this, "Raport skopiowany", android.widget.Toast.LENGTH_SHORT).show();
        });

        buttonMode.setOnClickListener(v -> {
            if (recording || pendingStart) return;
            selectedMode = (selectedMode + 1) % SOURCES.length;
            refreshModeLabel();
            show("Tryb: " + MODE_NAMES[selectedMode] + "\nNagrywanie: tylko mikrofon BT; AAC 48 kHz, mono, 192 kb/s (żądane).");
        });
        buttonCheck.setOnClickListener(v -> scanAndShow());
        buttonRecord.setOnClickListener(v -> {
            if (recording) stopRecording(false, null);
            else if (!pendingStart) startRecording();
        });
        refreshModeLabel();

        if (!hasPermissions()) requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
        else scanAndShow();
    }

    private String[] requiredPermissions() {
        return new String[] { Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT };
    }
    private boolean hasPermissions() {
        for (String p : requiredPermissions()) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }
    @Override public void onRequestPermissionsResult(int req, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(req, permissions, results);
        if (req == PERMISSION_REQUEST) {
            if (hasPermissions()) scanAndShow();
            else show("Brak uprawnień do mikrofonu lub urządzeń Bluetooth. Nadaj uprawnienia w ustawieniach aplikacji.");
        }
    }

    private boolean isBtInput(AudioDeviceInfo device) {
        return device != null && device.isSource() && (
                device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                || device.getType() == AudioDeviceInfo.TYPE_HEARING_AID);
    }
    private boolean isBtCommunication(AudioDeviceInfo device) {
        return device != null && (
                device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                || device.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET
                || device.getType() == AudioDeviceInfo.TYPE_HEARING_AID);
    }
    private String describe(AudioDeviceInfo d) {
        return d == null ? "BRAK" : d.getProductName() + " [type=" + d.getType()
                + ", id=" + d.getId() + ", input=" + d.isSource() + "]";
    }

    private String btType(AudioDeviceInfo d) {
        if (d == null) return "BRAK";
        if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return "SCO / HFP";
        if (d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) return "BLE_HEADSET / LE Audio";
        if (d.getType() == AudioDeviceInfo.TYPE_HEARING_AID) return "HEARING_AID";
        return "type=" + d.getType();
    }

    private void scanDevices() {
        btInputs.clear();
        for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (isBtInput(d)) btInputs.add(d);
        }
        if (selectedDeviceIndex >= btInputs.size()) selectedDeviceIndex = 0;
        btInput = btInputs.isEmpty() ? null : btInputs.get(selectedDeviceIndex);
        btCommunication = null;
        if (btInput != null) for (AudioDeviceInfo c : audio.getAvailableCommunicationDevices()) {
            if (c.getType() == btInput.getType() && isBtCommunication(c)) {
                btCommunication = c;
                break;
            }
        }
        buttonInput.setText(btInput == null ? "BRAK MIKROFONU BT" :
                "WEJSCIE " + (selectedDeviceIndex + 1) + "/" + btInputs.size()
                        + ": " + btType(btInput) + " (ZMIEN)");
    }

    private String details(AudioDeviceInfo d) {
        if (d == null) return "BRAK";
        return describe(d) + " — " + btType(d)
                + "\n  Hz=" + Arrays.toString(d.getSampleRates())
                + " kanaly=" + Arrays.toString(d.getChannelCounts())
                + " kodowania=" + Arrays.toString(d.getEncodings());
    }

    private void scanAndShow() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
            return;
        }
        if (recording || pendingStart) return;
        try {
            scanDevices();
            StringBuilder r = new StringBuilder("DIAGNOSTYKA BT SCO / LE\n");
            r.append("Mikrofony BT: ").append(btInputs.size()).append('\n');
            for (int i = 0; i < btInputs.size(); i++) {
                r.append(i == selectedDeviceIndex ? "> " : "  ");
                r.append(i+1).append(". ").append(details(btInputs.get(i))).append('\n');
            }
            r.append("\nUrzadzenia komunikacyjne:\n");
            for (AudioDeviceInfo c : audio.getAvailableCommunicationDevices()) {
                r.append("- ").append(describe(c)).append(" — ").append(btType(c)).append('\n');
            }
            r.append("\nWYBRANE: ").append(describe(btInput));
            r.append("\nCOMM: ").append(describe(btCommunication));
            r.append("\nSYSTEM COMM: ").append(describe(audio.getCommunicationDevice()));
            r.append("\nTRYB: ").append(MODE_NAMES[selectedMode]);
            r.append("\nAAC 48 kHz / 192 kb/s / mono (zadane)");
            if (btInput == null) r.append("\nBRAK mikrofonu BT — nagrywanie zablokowane.");
            else if (btCommunication == null) r.append("\nBrak COMM — probuje setPreferredDevice(BT).");
            boolean ble = false;
            for (AudioDeviceInfo d : btInputs) if (d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) ble = true;
            if (!ble) r.append("\nLE Audio INPUT: nieudostepnione przez Androida.");
            r.append("\n\nZMIEN WEJSCIE, jesli wykryto kilka.\n"
                    + "48 kHz w pliku nie oznacza 48 kHz przez BT.");
            lastReport = r.toString();
            show(lastReport);
        } catch (Exception e) { show("BLAD BT: " + e); }
    }

    private void startRecording() {
        if (!hasPermissions()) {
            requestPermissions(requiredPermissions(), PERMISSION_REQUEST);
            return;
        }
        try {
            scanDevices();
            if (btInput == null) {
                show("STOP: Android nie udostępnia mikrofonu Bluetooth.");
                return;
            }
            originalAudioMode = audio.getMode();
            routeOwned = true;
            if (btCommunication != null) {
                audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
                if (!audio.setCommunicationDevice(btCommunication)) {
                    restoreAudio();
                    show("STOP: setCommunicationDevice(BT) odrzucone.");
                    return;
                }
            } else {
                // BLE inputs without communication endpoint: prefer input directly.
                audio.setMode(AudioManager.MODE_NORMAL);
            }
            pendingStart = true;
            setButtonState();
            show("Ustawiam wejście BT: " + describe(btInput) + "\nStart za moment...");
            main.postDelayed(() -> {
                if (!pendingStart || isFinishing()) return;
                try { createAndStart(); }
                catch (Exception error) {
                    Log.e(TAG, "Start failed", error);
                    releaseRecorder(true);
                    show("NIE NAGRANO: " + error.getClass().getSimpleName() + ": " + error.getMessage()
                            + "\n48 kHz mogło zostać odrzucone przez urządzenie.");
                }
            }, 700);
        } catch (Exception e) {
            releaseRecorder(true);
            show("Nie można uruchomić mikrofonu BT: " + e);
        }
    }

    private void createAndStart() throws Exception {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        lastFile = "BT48K_" + (btInput.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET ? "LE" : "SCO") + "_" + MODE_FILES[selectedMode] + "_" + stamp + ".m4a";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, lastFile);
        values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/BT Dyktafon 48k");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        fileUri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (fileUri == null) throw new IOException("Nie można utworzyć pliku w MediaStore");
        fileDescriptor = getContentResolver().openFileDescriptor(fileUri, "rw");
        if (fileDescriptor == null) throw new IOException("Nie można otworzyć pliku wyjściowego");

        recorder = new MediaRecorder(this);
        recorder.setAudioSource(SOURCES[selectedMode]);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setAudioChannels(1);
        recorder.setAudioSamplingRate(AUDIO_HZ);
        recorder.setAudioEncodingBitRate(AUDIO_BITRATE);
        recorder.setOutputFile(fileDescriptor.getFileDescriptor());
        if (!recorder.setPreferredDevice(btInput)) {
            throw new IllegalStateException("Android odrzucił setPreferredDevice(BT)");
        }
        recorder.setOnErrorListener((mr, what, extra) -> failClosed("Błąd MediaRecorder " + what + "/" + extra));
        recorder.addOnRoutingChangedListener(routeListener, main);
        recorder.prepare();
        recorder.start();
        recording = true;
        pendingStart = false;
        startMs = SystemClock.elapsedRealtime();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setButtonState();
        timer.setText("00:00");
        main.post(clock);
        show("Rozpoczęto; weryfikacja faktycznego routingu BT...\n"
                + "Żądanie: 48000 Hz / AAC 192 kb/s. Tryb: " + MODE_NAMES[selectedMode]);
        main.postDelayed(() -> verifyActualRoute(0), 650);
    }

    private void verifyActualRoute(int retries) {
        if (!recording || recorder == null || aborting) return;
        AudioDeviceInfo actual = recorder.getRoutedDevice();
        if (actual == null && retries < 2) {
            main.postDelayed(() -> verifyActualRoute(retries + 1), 500);
            return;
        }
        if (!isBtInput(actual) || btInput == null || actual.getType() != btInput.getType()) {
            failClosed("TEST NEGATYWNY: źródło dźwięku nie jest BT: " + describe(actual));
            return;
        }
        audioInfo = makeAudioDiagnostics(actual);
        show("TEST OK — faktyczne wejście jest BT.\n"
                + "Tryb: " + MODE_NAMES[selectedMode] + "\n"
                + audioInfo + "\nNagrywanie trwa. Naciśnij STOP, aby zapisać M4A.");
    }

    private String makeAudioDiagnostics(AudioDeviceInfo route) {
        StringBuilder out = new StringBuilder();
        out.append("SELECTED: ").append(details(btInput)).append('\n');
        out.append("ROUTED: ").append(details(route)).append('\n');
        out.append("Zadane: AAC 48000 Hz / 192 kb/s, mono\n");
        out.append("BT reported rates: ").append(Arrays.toString(route.getSampleRates())).append('\n');
        try {
            AudioRecordingConfiguration cfg = recorder.getActiveRecordingConfiguration();
            if (cfg == null) {
                out.append("Android config: niedostępna\n");
            } else {
                AudioFormat c = cfg.getClientFormat();
                AudioFormat d = cfg.getFormat();
                int clientHz = c == null ? 0 : c.getSampleRate();
                int deviceHz = d == null ? 0 : d.getSampleRate();
                out.append("APP rate: ").append(clientHz).append(" Hz\n");
                out.append("HAL rate: ").append(deviceHz).append(" Hz\n");
                out.append("HAL input: ").append(describe(cfg.getAudioDevice())).append('\n');
                if (deviceHz > 0 && deviceHz < AUDIO_HZ) out.append("UWAGA: resampling z niższego HAL rate!\n");
                else out.append("HAL rate nie gwarantuje szerokiego pasma BT.\n");
                List<AudioEffect.Descriptor> fx = cfg.getEffects();
                out.append("Efekty Android: ");
                if (fx == null || fx.isEmpty()) out.append("brak lub nieujawnione");
                else for (AudioEffect.Descriptor effect : fx) out.append(effect.name).append("; ");
                out.append('\n');
            }
        } catch (Exception e) {
            out.append("Diagnostyka HAL niedostępna: ").append(e.getMessage()).append('\n');
        }
        lastReport = out.toString();
        Log.i(TAG, out.toString().replace('\n', ' '));
        return out.toString();
    }

    private void failClosed(String reason) {
        if (aborting) return;
        aborting = true;
        main.post(() -> {
            stopRecording(true, "PRZERWANO: " + reason + "\nPlik nagrania został skasowany.");
            aborting = false;
        });
    }

    private void stopRecording(boolean delete, String override) {
        boolean wasRecording = recording;
        recording = false;
        pendingStart = false;
        main.removeCallbacks(clock);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setButtonState();

        if (recorder != null) {
            try { recorder.removeOnRoutingChangedListener(routeListener); } catch (Exception ignored) {}
            try { recorder.setOnErrorListener(null); } catch (Exception ignored) {}
            if (wasRecording) {
                try { recorder.stop(); }
                catch (Exception e) { delete = true; Log.e(TAG, "stop", e); }
            }
            try { recorder.reset(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        if (fileDescriptor != null) {
            try { fileDescriptor.close(); } catch (IOException ignored) {}
            fileDescriptor = null;
        }
        if (fileUri != null) {
            if (delete) {
                try { getContentResolver().delete(fileUri, null, null); } catch (Exception ignored) {}
            } else {
                try {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Audio.Media.IS_PENDING, 0);
                    getContentResolver().update(fileUri, done, null, null);
                } catch (Exception e) { Log.e(TAG, "finalize MediaStore", e); }
            }
            fileUri = null;
        }
        restoreAudio();
        if (override != null) show(override);
        else if (wasRecording && !delete) show("ZAPISANO: " + lastFile
                + "\nMuzyka / BT Dyktafon 48k\n" + audioInfo);
    }

    private void releaseRecorder(boolean delete) {
        stopRecording(delete, null);
    }
    private void restoreAudio() {
        if (!routeOwned) return;
        try { audio.clearCommunicationDevice(); } catch (Exception ignored) {}
        try { audio.setMode(originalAudioMode); } catch (Exception ignored) {}
        routeOwned = false;
    }
    private void setButtonState() {
        buttonRecord.setEnabled(recording || !pendingStart);
        buttonRecord.setText(recording ? "STOP" : (pendingStart ? "START..." : "NAGRYWAJ"));
        buttonMode.setEnabled(!recording && !pendingStart);
        buttonCheck.setEnabled(!recording && !pendingStart);
        buttonInput.setEnabled(!recording && !pendingStart);
    }
    private void refreshModeLabel() {
        buttonMode.setText("TRYB: " + MODE_NAMES[selectedMode] + " (ZMIEŃ)");
    }
    private void show(String s) {
        status.setText(s);
        Log.i(TAG, s.replace('\n', ' '));
    }
    @Override protected void onPause() {
        super.onPause();
        if (recording) stopRecording(false, "Aplikacja przeszła do tła. Nagranie zapisano: " + lastFile);
        else if (pendingStart) releaseRecorder(true);
    }
    @Override protected void onDestroy() {
        main.removeCallbacks(clock);
        if (recording || pendingStart) stopRecording(true, null);
        restoreAudio();
        super.onDestroy();
    }
}
