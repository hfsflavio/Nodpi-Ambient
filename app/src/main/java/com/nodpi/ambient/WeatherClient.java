package com.nodpi.ambient;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Fetches the current weather from Open-Meteo (free, no API key). */
final class WeatherClient {

    static final class Result {
        final int tempC;
        final int code;
        final boolean isDay;

        Result(int tempC, int code, boolean isDay) {
            this.tempC = tempC;
            this.code = code;
            this.isDay = isDay;
        }
    }

    private WeatherClient() {}

    static Result fetch(Context ctx, double lat, double lon) throws Exception {
        String u = String.format(Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                        + "&current=temperature_2m,weather_code,is_day&timezone=auto", lat, lon);

        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        if (c instanceof HttpsURLConnection) {
            ((HttpsURLConnection) c).setSSLSocketFactory(TlsHelper.factory(ctx));
        }
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "nodpi-ambient/1.0");
        try {
            if (c.getResponseCode() != 200) {
                throw new Exception("HTTP " + c.getResponseCode());
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            in.close();

            JSONObject cur = new JSONObject(bos.toString("UTF-8")).getJSONObject("current");
            int temp = (int) Math.round(cur.getDouble("temperature_2m"));
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) == 1;
            return new Result(temp, code, day);
        } finally {
            c.disconnect();
        }
    }
}
