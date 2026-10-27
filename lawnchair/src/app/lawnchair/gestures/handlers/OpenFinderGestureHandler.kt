package app.lawnchair.gestures.handlers

import android.content.Context
import app.lawnchair.LawnchairLauncher
import app.lawnchair.finder.FinderView

class OpenFinderGestureHandler(context: Context) : GestureHandler(context) {

    override suspend fun onTrigger(launcher: LawnchairLauncher) {
        FinderView.show(launcher, animate = true)
    }
}
