package com.termix.ui.activities.terminal

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.termix.core.R
import com.termix.crashhandler.CrashHandler
import com.termix.migration.OreoMigrationActivity
import com.termix.service.SessionService
import com.termix.ui.navHosts.MainActivityNavHost
import com.termix.ui.routes.MainActivityRoutes
import com.termix.ui.screens.terminal.TerminalScreen
import com.termix.ui.screens.terminal.terminalView
import com.termix.ui.theme.TermixTheme
import com.termix.ui.theme.colorscheme.ColorSchemeManager
import com.termix.update.AppUpdateState
import com.termix.update.GitHubAppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    var sessionBinder by mutableStateOf<SessionService.SessionBinder?>(null)
    var isBound = false
    private val diagnosticId = Integer.toHexString(System.identityHashCode(this))
    private var contentInitialized = false


    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SessionService.SessionBinder
            sessionBinder = binder
            isBound = true
            val serviceId = Integer.toHexString(System.identityHashCode(binder.getService()))
            CrashHandler.event("MainActivity[$diagnosticId].onServiceConnected service=$serviceId")

            // Preserve the settings route when a system activity stops and restarts this activity.
            if (contentInitialized) return
            contentInitialized = true
            lifecycleScope.launch(Dispatchers.Main){
                setContent {
                    // Read color scheme state at the ROOT of the Compose tree
                    // Using 'by' delegate ensures Compose subscribes to changes
                    // and triggers recomposition of the entire tree when scheme changes
                    val currentColorScheme by ColorSchemeManager.currentScheme
                    
                    TermixTheme(terminalColorScheme = currentColorScheme) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            val navController = rememberNavController()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && OreoMigrationActivity.oldTerminalMode) {
                                Column(Modifier.fillMaxSize()) {
                                    TextButton(
                                        onClick = ::closeOldTerminalForBackup,
                                        modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                                    ) {
                                        Text(stringResource(R.string.oreo_migration_return_to_backup))
                                    }
                                    MainActivityNavHost(navController = navController, mainActivity = this@MainActivity)
                                }
                            } else {
                                MainActivityNavHost(navController = navController, mainActivity = this@MainActivity)
                            }

                            val backStackEntry by navController.currentBackStackEntryAsState()

                            val focusManager = LocalFocusManager.current
                            val keyboardController = LocalSoftwareKeyboardController.current

                            LaunchedEffect(backStackEntry?.destination?.route) {
                                if (backStackEntry?.destination?.route != MainActivityRoutes.MainScreen.route) {
                                    // 1️⃣ Clear Compose focus
                                    focusManager.clearFocus(force = true)

                                    // 2️⃣ Clear Android View focus
                                    terminalView.get()?.clearFocus()

                                    // 3️⃣ Hide IME explicitly
                                    keyboardController?.hide()
                                }
                            }


                        }
                    }
                }
            }


        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            sessionBinder = null
            CrashHandler.event("MainActivity[$diagnosticId].onServiceDisconnected")
        }
    }

    override fun onStart() {
        super.onStart()
        if (isFinishing) return
        CrashHandler.event("MainActivity[$diagnosticId].onStart request=SessionService")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, SessionService::class.java))
        }else{
            startService(Intent(this, SessionService::class.java))
        }
        Intent(this, SessionService::class.java).also { intent ->
            val accepted = bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            CrashHandler.event("MainActivity[$diagnosticId].bindService accepted=$accepted")
        }
    }

    override fun onStop() {
        super.onStop()
        CrashHandler.event("MainActivity[$diagnosticId].onStop connected=$isBound")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }


    private var denied = 1
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted && denied <= 2) {
                denied++
                requestPermission()
            }
        }

    fun requestPermission(){
        // Only request on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var isKeyboardVisible = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && OreoMigrationActivity.pending(this)) {
            startActivity(Intent(this, OreoMigrationActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        requestPermission()

        if (intent.hasExtra("awake_intent")){
            moveTaskToBack(true)
        }
        GitHubAppUpdater.from(this)?.let { updater ->
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    updater.check(automatic = true)
                    updater.state.collect { update ->
                        if (update is AppUpdateState.Available && updater.shouldAnnounce(update)) {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.app_update_available_notice, update.release.version),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            }
        }
    }

    var wasKeyboardOpen = false

    private fun closeOldTerminalForBackup() {
        AlertDialog.Builder(this)
            .setTitle(R.string.oreo_migration_return_to_backup)
            .setMessage(R.string.oreo_migration_return_to_backup_guide)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.oreo_migration_close_terminal) { _, _ ->
                sessionBinder?.terminateAllSessions()
                stopService(Intent(this, SessionService::class.java))
                finishAffinity()
                Process.killProcess(Process.myPid())
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        wasKeyboardOpen = isKeyboardVisible
    }

    override fun onResume() {
        super.onResume()

        val rootView = findViewById<View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            rootView.getWindowVisibleDisplayFrame(rect)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - rect.bottom
            val isVisible = keypadHeight > screenHeight * 0.15

            isKeyboardVisible = isVisible
        }


        if (wasKeyboardOpen && !isKeyboardVisible){
            terminalView.get()?.let {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
