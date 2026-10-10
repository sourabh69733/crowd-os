package org.freegram.app.nearby

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings

fun wifiSettingsIntent(sdk: Int = Build.VERSION.SDK_INT): Intent =
    Intent(if (sdk >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS)

fun isWifiEnabled(context: Context): Boolean =
    (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true
