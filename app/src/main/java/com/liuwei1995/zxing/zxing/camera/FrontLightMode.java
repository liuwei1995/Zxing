package com.liuwei1995.zxing.zxing.camera;

import android.content.SharedPreferences;

import com.liuwei1995.zxing.zxing.PreferencesContent;

/**
 * Enumerates settings of the preference controlling the front light.
 */
public enum FrontLightMode {

    /** Always on. */
    ON,
    /** On only when ambient light is low. */
    AUTO,
    /** Always off. */
    OFF;

    private static FrontLightMode parse(String modeString) {
        return modeString == null ? OFF : valueOf(modeString);
    }

    public static FrontLightMode readPref(SharedPreferences sharedPrefs) {
        return parse(sharedPrefs.getString(PreferencesContent.KEY_FRONT_LIGHT_MODE, OFF.toString()));
    }

}