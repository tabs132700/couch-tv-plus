package dev.couchtv.ontv;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * a key sender that stays running. Started once over the TV's own ADB as the shell user
 * (CLASSPATH=<this APK> app_process ... dev.couchtv.ontv.KeyInjector), it reads one key per line from stdin
 * ("KEYCODE_DPAD_RIGHT", or "LONG KEYCODE_HOME" for a long press) and injects it straight away. `input keyevent`
 * starts a new Java process for every key, which takes over a second on slow projectors; this pays that cost once.
 */
public final class KeyInjector {
    public static void main(String[] args) throws Exception {
        Class<?> imc = Class.forName("android.hardware.input.InputManager");
        Object im = imc.getMethod("getInstance").invoke(null);
        Method inject = imc.getMethod("injectInputEvent", InputEvent.class, int.class);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("ready");
        System.out.flush();
        String line;
        while ((line = in.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) continue;
            boolean longPress = line.startsWith("LONG ");
            int code = KeyEvent.keyCodeFromString(longPress ? line.substring(5).trim() : line);
            if (code == KeyEvent.KEYCODE_UNKNOWN) continue;
            try {
                long down = SystemClock.uptimeMillis();
                send(inject, im, new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
                if (longPress) {
                    SystemClock.sleep(600);
                    send(inject, im, new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, 0,
                            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_LONG_PRESS, InputDevice.SOURCE_KEYBOARD));
                }
                send(inject, im, new KeyEvent(down, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, 0,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD));
            } catch (Exception e) {
                System.out.println("error " + e);
                System.out.flush();
            }
        }
    }

    private static void send(Method inject, Object im, KeyEvent e) throws Exception {
        inject.invoke(im, e, 0);   // 0: INJECT_INPUT_EVENT_MODE_ASYNC
    }
}
