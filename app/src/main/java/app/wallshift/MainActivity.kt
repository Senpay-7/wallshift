package app.wallshift

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.wallshift.ui.AppTheme
import app.wallshift.ui.MainScreen
import app.wallshift.ui.MainViewModel

/** Single activity, single screen. No work in onStart/onResume. */
class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels { MainViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val vm = vm // starts the async index/settings read before the first composition
        setContent { AppTheme { MainScreen(vm) } }
    }
}
