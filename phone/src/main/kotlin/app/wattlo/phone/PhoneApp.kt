package app.wattlo.phone

import android.app.Application
import app.wattlo.Settings

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // the phone screen is big: draw two rings of 12 hours until the user picks otherwise
        Settings.appRingsDefault = 2
    }
}
