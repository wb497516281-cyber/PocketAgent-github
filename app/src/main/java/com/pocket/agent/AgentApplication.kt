package com.pocket.agent

import android.app.Application
import com.pocket.agent.util.AppContainer

/** Owns the single object graph for the whole app. */
class AgentApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
