package app.clipsaver

import android.app.Application

class ClipSaverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.start(this)
    }
}
