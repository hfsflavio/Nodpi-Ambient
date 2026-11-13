package com.nodpi.ambient;

import android.service.dreams.DreamService;
import android.view.KeyEvent;

/** The system screensaver ("Daydream") entry point. */
public class AmbientDream extends DreamService {

    private AmbientView view;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);   // lets the remote / touch reach our buttons
        setFullscreen(true);
        setScreenBright(true);
        view = new AmbientView(this);
        setContentView(view);
    }

    @Override
    public void onDetachedFromWindow() {
        if (view != null) {
            view.stop();
            view = null;
        }
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (view != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    view.prev();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    view.next();
                    return true;
                default:
                    break;
            }
        }
        // Back (and anything else) is handled by the system: Back exits the screensaver.
        return super.dispatchKeyEvent(event);
    }
}
