package com.materialagent

import android.app.Application
import com.materialagent.data.AppContainer

/**
 * Application entry point. Holds the hand-rolled dependency container — the app
 * is small enough that a DI framework would add ceremony without benefit, and a
 * plain object graph keeps the transport layer trivially unit-testable.
 */
class MaterialAgentApp : Application() {

    /** The app's single object graph. Built on first use so cold start stays lean. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        container.autoconnect()
    }

    companion object {
        lateinit var instance: MaterialAgentApp
            private set
    }
}
