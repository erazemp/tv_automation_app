package com.erazem.streamtest;

import android.content.Context;
import android.content.SharedPreferences;

final class Config {
    float minutes = 60f;
    String yt = "jfKfPfyJRdk";      // YouTube video id
    String tw = "";                 // Twitch channel ("" = featured stream)
    String nf = "80057281";         // Netflix title id (netflix.com/watch/<id>)

    static Config load(Context c) {
        SharedPreferences p = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        Config k = new Config();
        k.minutes = p.getFloat("minutes", k.minutes);
        k.yt = p.getString("yt", k.yt);
        k.tw = p.getString("tw", k.tw);
        k.nf = p.getString("nf", k.nf);
        return k;
    }
    void save(Context c) {
        c.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit()
            .putFloat("minutes", minutes).putString("yt", yt).putString("tw", tw).putString("nf", nf).apply();
    }
}
