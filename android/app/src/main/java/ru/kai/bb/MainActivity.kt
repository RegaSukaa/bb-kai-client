package ru.kai.bb

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val prefs = SitePrefs(this)
        setContent {
            BbTheme {
                BrowserScreen(prefs)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // куки сессии Blackboard живут на диске — иначе после перезапуска придётся логиниться заново
        WebBridge.current?.flushSession()
    }
}
