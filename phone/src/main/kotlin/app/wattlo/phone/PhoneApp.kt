package app.wattlo.phone

import android.app.Application
import app.wattlo.Settings
import app.wattlo.SettingsSync
import app.wattlo.Surfaces

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // the phone screen is big: draw two rings of 12 hours until the user picks otherwise
        Settings.appRingsDefault = 2
        SettingsSync.pull(this)
        // the widgets follow the schedule, the fetch time and the settings like the screens do
        Surfaces.onChange = { c -> Widgets.updateAll(c) }
        Surfaces.onFetched = { c -> Widgets.updateAll(c) }
    }
}
