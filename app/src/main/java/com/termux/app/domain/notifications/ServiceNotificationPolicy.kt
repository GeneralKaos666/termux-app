package com.termux.app.domain.notifications

/** Whether the foreground service should stop: only when nothing is keeping it alive. */
fun shouldStopService(
    wakeLockHeld: Boolean,
    sessionCount: Int,
    taskCount: Int,
    floatRunning: Boolean,
): Boolean = !wakeLockHeld && sessionCount <= 0 && taskCount <= 0 && !floatRunning
