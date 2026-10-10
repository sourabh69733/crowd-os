package org.freegram.app

import android.provider.Settings
import org.freegram.app.nearby.wifiSettingsIntent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NearbyRadioSettingsTest {
    @Test fun wifiButtonUsesPanelOnModernPhonesAndSettingsOnOlderPhones() {
        assertEquals(Settings.ACTION_WIFI_SETTINGS, wifiSettingsIntent(28).action)
        assertEquals(Settings.Panel.ACTION_WIFI, wifiSettingsIntent(29).action)
        assertEquals(Settings.Panel.ACTION_WIFI, wifiSettingsIntent(35).action)
    }
}
