package com.pylikv.tachowatch

import android.app.Activity
import android.content.Context
import android.view.WindowManager

/** Window-scoped: Android restores normal screen timeout when the app is backgrounded. */
object ScreenPreference {
    private const val PREFS="tachowatch_display"
    private const val KEEP_SCREEN_ON="keep_screen_on"
    fun enabled(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getBoolean(KEEP_SCREEN_ON,false)
    fun setEnabled(activity:Activity,enabled:Boolean){
        activity.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean(KEEP_SCREEN_ON,enabled).apply()
        apply(activity)
    }
    fun apply(activity:Activity){
        if(enabled(activity))activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
