package se.spinhaus.app

import android.app.Application

class SpinhausApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.onStartup()
    }
}
