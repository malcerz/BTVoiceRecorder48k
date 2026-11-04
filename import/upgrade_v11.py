from pathlib import Path

root = Path("dyktafon-source")
p = root / "app/src/main/java/com/malcerz/btvoicerecorder/MainActivity.java"
s = p.read_text(encoding="utf-8")

def rep(a,b):
    global s
    assert s.count(a) == 1, "Missing or ambiguous anchor: " + a[:80]
    s = s.replace(a,b,1)

rep("import android.content.Context;", "import android.content.Context;\nimport android.content.ClipData;\nimport android.content.ClipboardManager;")
rep("import java.util.Arrays;", "import java.util.Arrays;\nimport java.util.ArrayList;")
rep("    private Button buttonRecord;", """    private Button buttonRecord;
    private Button buttonInput;
    private Button buttonCopy;
    private final List<AudioDeviceInfo> btInputs = new ArrayList<>();
    private int selectedDeviceIndex = 0;
    private String lastReport = "";
""".rstrip())
rep("        buttonRecord = findViewById(R.id.record);", """        buttonRecord = findViewById(R.id.record);
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
        });""")

a = s.index("    private void scanDevices() {")
b = s.index("    private void startRecording() {", a)
s = s[:a] + """    private String btType(AudioDeviceInfo d) {
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
                + "\\n  Hz=" + Arrays.toString(d.getSampleRates())
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
            StringBuilder r = new StringBuilder("DIAGNOSTYKA BT SCO / LE\\n");
            r.append("Mikrofony BT: ").append(btInputs.size()).append('\\n');
            for (int i = 0; i < btInputs.size(); i++) {
                r.append(i == selectedDeviceIndex ? "> " : "  ");
                r.append(i+1).append(". ").append(details(btInputs.get(i))).append('\\n');
            }
            r.append("\\nUrzadzenia komunikacyjne:\\n");
            for (AudioDeviceInfo c : audio.getAvailableCommunicationDevices()) {
                r.append("- ").append(describe(c)).append(" — ").append(btType(c)).append('\\n');
            }
            r.append("\\nWYBRANE: ").append(describe(btInput));
            r.append("\\nCOMM: ").append(describe(btCommunication));
            r.append("\\nSYSTEM COMM: ").append(describe(audio.getCommunicationDevice()));
            r.append("\\nTRYB: ").append(MODE_NAMES[selectedMode]);
            r.append("\\nAAC 48 kHz / 192 kb/s / mono (zadane)");
            if (btInput == null) r.append("\\nBRAK mikrofonu BT — nagrywanie zablokowane.");
            else if (btCommunication == null) r.append("\\nBrak COMM — probuje setPreferredDevice(BT).");
            boolean ble = false;
            for (AudioDeviceInfo d : btInputs) if (d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) ble = true;
            if (!ble) r.append("\\nLE Audio INPUT: nieudostepnione przez Androida.");
            r.append("\\n\\nZMIEN WEJSCIE, jesli wykryto kilka.\\n"
                    + "48 kHz w pliku nie oznacza 48 kHz przez BT.");
            lastReport = r.toString();
            show(lastReport);
        } catch (Exception e) { show("BLAD BT: " + e); }
    }

""" + s[b:]
rep('if (btInput == null || btCommunication == null)', 'if (btInput == null)')
rep('STOP: Android nie udostępnia wejścia mikrofonu Bluetooth i odpowiadającego mu urządzenia komunikacyjnego.',
    'STOP: Android nie udostępnia mikrofonu Bluetooth.')
rep("""            audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
            routeOwned = true;
            if (!audio.setCommunicationDevice(btCommunication)) {
                restoreAudio();
                show("STOP: setCommunicationDevice(BT) zwróciło false.");
                return;
            }""", """            routeOwned = true;
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
            }""")
rep('if (!isBtInput(routed)) {',
    'if (!isBtInput(routed) || btInput == null || routed.getType() != btInput.getType()) {')
rep('if (!isBtInput(actual)) {',
    'if (!isBtInput(actual) || btInput == null || actual.getType() != btInput.getType()) {')
rep('        out.append("ROUTED: ").append(describe(route)).append(\'\\n\');',
    '        out.append("SELECTED: ").append(details(btInput)).append(\'\\n\');\n'
    '        out.append("ROUTED: ").append(details(route)).append(\'\\n\');')
rep("        Log.i(TAG, out.toString().replace('\\n', ' '));",
    "        lastReport = out.toString();\n        Log.i(TAG, out.toString().replace('\\n', ' '));")
rep('        buttonCheck.setEnabled(!recording && !pendingStart);',
    '        buttonCheck.setEnabled(!recording && !pendingStart);\n'
    '        buttonInput.setEnabled(!recording && !pendingStart);')
rep('        lastFile = "BT48K_" + MODE_FILES[selectedMode] + "_" + stamp + ".m4a";',
    '        lastFile = "BT48K_" + (btInput.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET ? "LE" : "SCO")'
    ' + "_" + MODE_FILES[selectedMode] + "_" + stamp + ".m4a";')
p.write_text(s, encoding="utf-8")

lp = root / "app/src/main/res/layout/activity_main.xml"
layout = lp.read_text(encoding="utf-8")
needle = '    <Button\n        android:id="@+id/mode"'
assert needle in layout
layout = layout.replace(needle, '''    <Button
        android:id="@+id/choose_input"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="WYBIERZ WEJSCIE BT" />

''' + needle, 1)
needle = '        <Button\n            android:id="@+id/record"'
assert needle in layout
layout = layout.replace(needle, '''        <Button
            android:id="@+id/copy_report"
            android:layout_width="0dp"
            android:layout_weight="1"
            android:layout_height="wrap_content"
            android:text="KOPIUJ" />
''' + needle, 1)
layout = layout.replace('BT Dyktafon 48 kHz', 'BT Dyktafon - SCO / LE')
lp.write_text(layout, encoding="utf-8")

bp = root / "app/build.gradle"
b = bp.read_text(encoding="utf-8")
assert "versionCode 1" in b
b = b.replace("applicationId 'com.malcerz.btvoicerecorder'",
              "applicationId 'com.malcerz.btvoicerecorder.diag'")
b = b.replace("versionCode 1","versionCode 2")
b = b.replace("versionName '1.0-48k-test'","versionName '1.1-sco-le-test'")
bp.write_text(b, encoding="utf-8")

mp = root / "app/src/main/AndroidManifest.xml"
m = mp.read_text(encoding="utf-8")
m = m.replace('android:label="BT Dyktafon 48k"', 'android:label="BT Dyktafon Test LE"')
m = m.replace('android:name=".MainActivity"',
              'android:name="com.malcerz.btvoicerecorder.MainActivity"')
mp.write_text(m, encoding="utf-8")
print("Applied BT SCO/LE microphone diagnostic v1.1")
