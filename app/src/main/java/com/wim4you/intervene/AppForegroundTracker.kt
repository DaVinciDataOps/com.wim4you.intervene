package com.wim4you.intervene

import android.app.Activity
import android.app.Application
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks whether any app activity is in the foreground.
 * Used to pause map-discovery Firebase listeners while the app is backgrounded.
 */
object AppForegroundTracker {
    @Volatile
    var isInForeground: Boolean = false
        private set

    private var startedActivityCount = 0
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun initialize(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    updateForegroundState(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) {
                    updateForegroundState(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Registers a listener and immediately invokes it with the current state.
     * Returns a disposer that removes the listener.
     */
    fun addListener(listener: (Boolean) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(isInForeground)
        return { listeners.remove(listener) }
    }

    private fun updateForegroundState(inForeground: Boolean) {
        if (isInForeground == inForeground) return
        isInForeground = inForeground
        listeners.forEach { it(inForeground) }
    }
}
