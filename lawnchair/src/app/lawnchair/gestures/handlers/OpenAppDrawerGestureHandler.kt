package app.lawnchair.gestures.handlers

import android.content.Context
import app.lawnchair.LawnchairLauncher

/** LC: the app drawer is the App Library now, past the last home page. */
class OpenAppDrawerGestureHandler(context: Context) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        launcher.appLibrary?.open()
    }
}
