package com.nodpi.ambient;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The whole nodpi Ambient screen: photo slideshow, clock, weather and
 * previous / next buttons. Used by both the screensaver (AmbientDream)
 * and the preview activity (MainActivity).
 *
 * All sizes are written for a 1920 px wide screen and scaled automatically.
 */
public class AmbientView extends FrameLayout {

    // ---------------------------------------------------------------- settings
    /** Ain Beida, Algeria */
    private static final double LAT = 35.7956;
    private static final double LON = 7.3947;

    private static final long SLIDE_MS = 20000;           // time between photos
    private static final long FADE_MS = 1400;             // cross-fade duration
    private static final long WEATHER_MS = 15 * 60 * 1000; // weather refresh
    private static final long WEATHER_RETRY_MS = 2 * 60 * 1000;

    private static final String PHOTOS_DIR = "photos";
    private static final String CLOCK_FONT = "fonts/dmsans_medium.ttf";
    private static final String TEMP_FONT = "fonts/dmsans_regular.ttf";
    // ------------------------------------------------------------------------

    private final float scale;
    private final int screenW, screenH;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AssetManager assets;

    private ExecutorService io;
    private boolean running;

    // photos
    private String[] photos = new String[0];
    private int index;
    private int token;
    private final ImageView[] layers = new ImageView[2];
    private final Bitmap[] bitmaps = new Bitmap[2];
    private int front = 1; // layer currently on top

    // weather UI
    private TextView tempText;
    private ImageView weatherIcon;

    // buttons
    private ImageView btnPrev, btnNext;

    private final Runnable slideRunnable = new Runnable() {
        @Override public void run() { next(); }
    };
    private final Runnable weatherRunnable = new Runnable() {
        @Override public void run() { refreshWeather(); }
    };

    public AmbientView(Context ctx) {
        super(ctx);
        assets = ctx.getAssets();

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        scale = screenW / 1920f;

        setBackgroundColor(Color.BLACK);
        loadPhotoList();
        buildUi(ctx);
    }

    private int px(float v) { return Math.round(v * scale); }

    // ------------------------------------------------------------------- UI --

    private void buildUi(Context ctx) {
        // photo layers
        FrameLayout photoLayer = new FrameLayout(ctx);
        addView(photoLayer, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < 2; i++) {
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            photoLayer.addView(iv, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            layers[i] = iv;
        }

        // soft dark gradient at the bottom so text stays readable on bright photos
        View scrim = new View(ctx);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x88000000});
        scrim.setBackgroundDrawable(g);
        LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, px(400));
        slp.gravity = Gravity.BOTTOM;
        addView(scrim, slp);

        // bottom-left: logo + buttons
        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(LinearLayout.HORIZONTAL);
        left.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(ctx);
        logo.setImageResource(R.drawable.logo_nodpi);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(px(190), px(63));
        llp.rightMargin = px(36);
        left.addView(logo, llp);

        btnPrev = makeButton(ctx, R.drawable.btn_prev, R.drawable.btn_prev_focus);
        btnNext = makeButton(ctx, R.drawable.btn_next, R.drawable.btn_next_focus);
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(px(140), px(140));
        b1.rightMargin = px(14);
        left.addView(btnPrev, b1);
        left.addView(btnNext, new LinearLayout.LayoutParams(px(140), px(140)));

        btnPrev.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { prev(); }
        });
        btnNext.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { next(); }
        });

        LayoutParams leftLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        leftLp.gravity = Gravity.BOTTOM | Gravity.LEFT;
        leftLp.leftMargin = px(52);
        leftLp.bottomMargin = px(30);
        addView(left, leftLp);

        // bottom-right: temperature + icon + clock
        LinearLayout right = new LinearLayout(ctx);
        right.setOrientation(LinearLayout.HORIZONTAL);
        right.setGravity(Gravity.CENTER_VERTICAL);

        Typeface clockFace = loadFont(CLOCK_FONT);
        Typeface tempFace = loadFont(TEMP_FONT);

        tempText = new TextView(ctx);
        tempText.setTextColor(Color.WHITE);
        tempText.setTypeface(tempFace);
        tempText.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(78));
        tempText.setIncludeFontPadding(false);
        tempText.setShadowLayer(px(8), 0, px(2), 0x66000000);
        tempText.setText("--\u00B0");
        right.addView(tempText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        weatherIcon = new ImageView(ctx);
        weatherIcon.setImageResource(R.drawable.w_unknown);
        weatherIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(px(72), px(72));
        ilp.leftMargin = px(8);
        ilp.rightMargin = px(44);
        right.addView(weatherIcon, ilp);

        TextClock clock = new TextClock(ctx);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTypeface(clockFace);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(136));
        clock.setIncludeFontPadding(false);
        clock.setShadowLayer(px(10), 0, px(3), 0x66000000);
        right.addView(clock, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LayoutParams rightLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rightLp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        rightLp.rightMargin = px(84);
        rightLp.bottomMargin = px(80);
        addView(right, rightLp);
    }

    private ImageView makeButton(Context ctx, int normalRes, int focusRes) {
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, ctx.getResources().getDrawable(focusRes));
        sl.addState(new int[]{android.R.attr.state_focused}, ctx.getResources().getDrawable(focusRes));
        sl.addState(new int[]{}, ctx.getResources().getDrawable(normalRes));
        ImageView b = new ImageView(ctx);
        b.setImageDrawable(sl);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setFocusable(true);
        b.setClickable(true);
        return b;
    }

    private Typeface loadFont(String path) {
        try {
            return Typeface.createFromAsset(assets, path);
        } catch (Throwable t) {
            return Typeface.DEFAULT;
        }
    }

    // ------------------------------------------------------------ lifecycle --

    public void start() {
        if (running) return;
        running = true;
        io = Executors.newSingleThreadExecutor();

        if (photos.length > 0) {
            index = new Random().nextInt(photos.length);
            loadPhoto(index);
        }
        showCachedWeather();
        ui.post(weatherRunnable);
    }

    public void stop() {
        if (!running) return;
        running = false;
        token++;
        ui.removeCallbacks(slideRunnable);
        ui.removeCallbacks(weatherRunnable);
        if (io != null) io.shutdownNow();
        io = null;
        for (int i = 0; i < 2; i++) {
            layers[i].animate().cancel();
            layers[i].setImageDrawable(null);
            if (bitmaps[i] != null) {
                bitmaps[i].recycle();
                bitmaps[i] = null;
            }
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    // --------------------------------------------------------------- photos --

    private void loadPhotoList() {
        List<String> list = new ArrayList<String>();
        try {
            String[] all = assets.list(PHOTOS_DIR);
            if (all != null) {
                for (String n : all) {
                    String l = n.toLowerCase();
                    if (l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png")) list.add(n);
                }
            }
        } catch (IOException ignored) {}
        String[] arr = list.toArray(new String[list.size()]);
        Arrays.sort(arr, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int na = leadingNumber(a), nb = leadingNumber(b);
                if (na != nb) return na < nb ? -1 : 1;
                return a.compareTo(b);
            }
        });
        photos = arr;
    }

    private static int leadingNumber(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i == 0) return Integer.MAX_VALUE;
        try { return Integer.parseInt(s.substring(0, i)); } catch (NumberFormatException e) { return Integer.MAX_VALUE; }
    }

    public void next() {
        if (photos.length == 0) return;
        flash(btnNext);
        index = (index + 1) % photos.length;
        loadPhoto(index);
    }

    public void prev() {
        if (photos.length == 0) return;
        flash(btnPrev);
        index = (index - 1 + photos.length) % photos.length;
        loadPhoto(index);
    }

    /** Briefly shows the highlighted state of a button (used for remote D-pad). */
    private void flash(final View v) {
        v.setPressed(true);
        v.postDelayed(new Runnable() {
            @Override public void run() { v.setPressed(false); }
        }, 220);
    }

    private void loadPhoto(final int i) {
        if (!running || io == null) return;
        final int tok = ++token;
        ui.removeCallbacks(slideRunnable);
        final String name = photos[i];
        try {
            io.execute(new Runnable() {
                @Override public void run() {
                    final Bitmap b = decode(name);
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (b == null) {
                                // unreadable photo: try again later
                                if (running && tok == token) ui.postDelayed(slideRunnable, SLIDE_MS);
                                return;
                            }
                            if (!running || tok != token) {
                                b.recycle();
                                return;
                            }
                            display(b);
                            ui.postDelayed(slideRunnable, SLIDE_MS);
                        }
                    });
                }
            });
        } catch (Throwable ignored) {}
    }

    private Bitmap decode(String name) {
        Bitmap out = decodeOnce(name, Bitmap.Config.ARGB_8888);
        if (out == null) out = decodeOnce(name, Bitmap.Config.RGB_565); // low-memory fallback
        return out;
    }

    private Bitmap decodeOnce(String name, Bitmap.Config cfg) {
        InputStream in = null;
        try {
            in = assets.open(PHOTOS_DIR + "/" + name);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(in, null, o);
            in.close();

            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) {
                sample *= 2;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = cfg;
            in = assets.open(PHOTOS_DIR + "/" + name);
            return BitmapFactory.decodeStream(in, null, o2);
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    private void display(Bitmap b) {
        // finish any running fade instantly
        ImageView cur = layers[front];
        cur.animate().cancel();
        cur.setAlpha(1f);

        int inIdx = 1 - front;
        ImageView in = layers[inIdx];
        Bitmap old = bitmaps[inIdx];

        in.setImageBitmap(b);
        bitmaps[inIdx] = b;
        if (old != null) old.recycle(); // that layer is hidden beneath the front one

        in.setAlpha(0f);
        in.bringToFront();
        in.animate().alpha(1f).setDuration(FADE_MS).start();
        front = inIdx;
    }

    // -------------------------------------------------------------- weather --

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    private void showCachedWeather() {
        SharedPreferences p = prefs();
        if (p.contains("temp")) {
            long age = System.currentTimeMillis() - p.getLong("time", 0);
            if (age < 3 * 60 * 60 * 1000L) {
                applyWeather(p.getInt("temp", 0), p.getInt("code", -1), p.getBoolean("day", true));
            }
        }
    }

    private void refreshWeather() {
        if (!running || io == null) return;
        final Context ctx = getContext().getApplicationContext();
        try {
            io.execute(new Runnable() {
                @Override public void run() {
                    WeatherClient.Result r = null;
                    try {
                        r = WeatherClient.fetch(ctx, LAT, LON);
                    } catch (Throwable ignored) {}
                    final WeatherClient.Result res = r;
                    ui.post(new Runnable() {
                        @Override public void run() {
                            if (!running) return;
                            if (res != null) {
                                prefs().edit()
                                        .putInt("temp", res.tempC)
                                        .putInt("code", res.code)
                                        .putBoolean("day", res.isDay)
                                        .putLong("time", System.currentTimeMillis())
                                        .commit();
                                applyWeather(res.tempC, res.code, res.isDay);
                                ui.postDelayed(weatherRunnable, WEATHER_MS);
                            } else {
                                ui.postDelayed(weatherRunnable, WEATHER_RETRY_MS);
                            }
                        }
                    });
                }
            });
        } catch (Throwable ignored) {}
    }

    private void applyWeather(int tempC, int code, boolean day) {
        tempText.setText(tempC + "\u00B0");
        weatherIcon.setImageResource(iconFor(code, day));
    }

    /** Maps WMO weather codes (used by Open-Meteo) to the bundled icons. */
    static int iconFor(int code, boolean day) {
        switch (code) {
            case 0:
            case 1:
                return day ? R.drawable.w_clear_day : R.drawable.w_clear_night;
            case 2:
                return day ? R.drawable.w_partly_day : R.drawable.w_partly_night;
            case 3:
                return R.drawable.w_cloudy;
            case 45:
            case 48:
                return R.drawable.w_fog;
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 80:
                return R.drawable.w_drizzle;
            case 63: case 65: case 66: case 67:
            case 81: case 82:
                return R.drawable.w_rain;
            case 71: case 73: case 75: case 77:
            case 85: case 86:
                return R.drawable.w_snow;
            case 95: case 96: case 99:
                return R.drawable.w_thunder;
            default:
                return R.drawable.w_unknown;
        }
    }
}
