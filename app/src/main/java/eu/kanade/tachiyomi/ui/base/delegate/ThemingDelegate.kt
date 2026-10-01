package eu.kanade.tachiyomi.ui.base.delegate

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate
import eu.kanade.tachiyomi.R

interface ThemingDelegate {
    fun applyAppTheme(activity: Activity)
}

class ThemingDelegateImpl : ThemingDelegate {
    override fun applyAppTheme(activity: Activity) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        activity.setTheme(R.style.Theme_Tachiyomi)
    }
}
