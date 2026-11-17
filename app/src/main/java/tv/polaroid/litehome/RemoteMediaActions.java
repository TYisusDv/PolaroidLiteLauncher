package tv.polaroid.litehome;

import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

/** Audio and media buttons supported by the phone remote. */
final class RemoteMediaActions {
    private RemoteMediaActions() { }

    static boolean dispatch(Context context, String action) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return false;
        if ("volume_up".equals(action)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI);
            return true;
        }
        if ("volume_down".equals(action)) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
            return true;
        }
        if ("mute".equals(action)) {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE,
                        AudioManager.FLAG_SHOW_UI);
            } else {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_SHOW_UI);
            }
            return true;
        }
        int keyCode = "previous".equals(action) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS
                : "next".equals(action) ? KeyEvent.KEYCODE_MEDIA_NEXT
                : "play_pause".equals(action) ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : 0;
        if (keyCode == 0) return false;
        long now = android.os.SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now + 35, KeyEvent.ACTION_UP, keyCode, 0));
        return true;
    }
}
