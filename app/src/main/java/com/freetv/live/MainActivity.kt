package com.freetv.live

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.freetv.live.ui.BrowseScreen
import com.freetv.live.ui.FreeTvTheme
import com.freetv.live.ui.PlayerScreen
import com.freetv.live.ui.RemoteScreen
import com.freetv.live.ui.SourcesScreen
import com.freetv.live.ui.TvViewModel

class MainActivity : ComponentActivity() {
    private val vm: TvViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FreeTvTheme {
                val playing by vm.playing.collectAsState()
                var showSources by rememberSaveable { mutableStateOf(false) }
                var showRemote by rememberSaveable { mutableStateOf(false) }

                LaunchedEffect(playing != null, showRemote) { setFullscreen(playing != null && !showRemote) }

                when {
                    showRemote -> RemoteScreen(vm, onBack = { showRemote = false })
                    playing != null -> PlayerScreen(vm, onExit = {}, onOpenRemote = { showRemote = true })
                    showSources -> SourcesScreen(vm, onBack = { showSources = false })
                    else -> BrowseScreen(vm, onOpenSources = { showSources = true }, onOpenRemote = { showRemote = true })
                }
            }
        }
    }

    private fun setFullscreen(on: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !on)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
