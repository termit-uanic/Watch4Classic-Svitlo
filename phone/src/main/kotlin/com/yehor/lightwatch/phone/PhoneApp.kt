package com.yehor.lightwatch.phone

import android.app.Application
import com.yehor.lightwatch.Settings

class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // the phone screen is big: draw two rings of 12 hours until the user picks otherwise
        Settings.appRingsDefault = 2
    }
}
