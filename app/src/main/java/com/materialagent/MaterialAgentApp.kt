package com.materialagent

import android.app.Application

/**
 * Application entry point. Holds the hand-rolled dependency container — the app
 * is small enough that a DI framework would add ceremony without benefit, and a
 * plain object graph keeps the transport layer trivially unit-testable.
 */
class MaterialAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: MaterialAgentApp
            private set
    }
}
