package com.rk.terminal.ui.screens.terminal

import android.app.Activity
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.widget.doOnTextChanged
import androidx.navigation.NavController
import com.google.android.material.R
import androidx.compose.ui.res.stringResource
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.libcommons.application
import com.rk.resources.strings
import com.rk.libcommons.child
import com.rk.libcommons.dpToPx
import com.rk.libcommons.localDir
import com.rk.libcommons.pendingCommand
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.components.InputDialog
import com.rk.terminal.ui.components.SessionTabBar
import com.rk.terminal.ui.routes.MainActivityRoutes
import com.rk.terminal.ui.screens.settings.LayoutMode
import com.rk.terminal.ui.screens.settings.SettingsCard
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.ui.screens.settings.CloseLastSessionBehavior
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysConstants
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysInfo
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView
import com.rk.terminal.ui.theme.colorscheme.ColorSchemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.Properties

var terminalView = WeakReference<TerminalView?>(null)
var virtualKeysView = WeakReference<VirtualKeysView?>(null)


var darkText = mutableStateOf(Settings.blackTextColor)
var bitmap = mutableStateOf<ImageBitmap?>(null)

private val file = application!!.filesDir.child("font.ttf")
private var font = (if (file.exists() && file.canRead()){
    Typeface.createFromFile(file)
}else{
    Typeface.MONOSPACE
})

suspend fun setFont(typeface: Typeface) = withContext(Dispatchers.Main){
    font = typeface
    terminalView.get()?.apply {
        setTypeface(typeface)
        onScreenUpdated()
    }
}

inline fun getViewColor(): Int{
    return if (darkText.value){
        Color.BLACK
    }else{
        Color.WHITE
    }
}

inline fun getComposeColor():androidx.compose.ui.graphics.Color{
    return if (darkText.value){
        androidx.compose.ui.graphics.Color.Black
    }else{
        androidx.compose.ui.graphics.Color.White
    }
}

var showToolbar = mutableStateOf(Settings.toolbar)
var showVirtualKeys = mutableStateOf(Settings.virtualKeys)
var showHorizontalToolbar = mutableStateOf(Settings.toolbar)


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    mainActivityActivity: MainActivity,
    navController: NavController
) {
    val context = LocalContext.current
    val isDarkMode = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()


    LaunchedEffect(Unit){
        withContext(Dispatchers.IO){
            if (context.filesDir.child("background").exists().not()){
                darkText.value = !isDarkMode
            }else if (bitmap.value == null){
                val fullBitmap = BitmapFactory.decodeFile(context.filesDir.child("background").absolutePath)?.asImageBitmap()
                if (fullBitmap != null) bitmap.value = fullBitmap
            }
        }


        scope.launch(Dispatchers.Main){
            virtualKeysView.get()?.apply {
                virtualKeysViewClient =
                    terminalView.get()?.mTermSession?.let {
                        VirtualKeysListener(
                            it
                        )
                    }

                // Use color scheme foreground for virtual keys
                val scheme = ColorSchemeManager.getCurrentScheme()
                buttonTextColor = scheme.foreground

                reload(
                    VirtualKeysInfo(
                        VIRTUAL_KEYS,
                        "",
                        VirtualKeysConstants.CONTROL_CHARS_ALIASES
                    )
                )
            }

            terminalView.get()?.apply {
                onScreenUpdated()
                // Colors are managed by ColorSchemeManager
            }
        }


    }

    Box {
        val scope = rememberCoroutineScope()
        val isTabBarMode = Settings.layout_mode == LayoutMode.TAB_BAR
        var showAddDialog by remember { mutableStateOf(false) }
        var showRenameDialogFor by remember { mutableStateOf<String?>(null) }

        // Helper function to generate unique session ID
        fun generateUniqueSessionId(): String {
            val existingStrings = mainActivityActivity.sessionBinder?.getService()?.sessionOrder?.toList() ?: emptyList()
            var index = 1
            var newString: String
            do {
                newString = "main$index"
                index++
            } while (newString in existingStrings)
            return newString
        }

        // Helper function to create a new session
        fun createNewSession(workingMode: Int) {
            val sessionId = generateUniqueSessionId()
            terminalView.get()?.let {
                val client = TerminalBackEnd(it, mainActivityActivity)
                mainActivityActivity.sessionBinder!!.createSession(
                    sessionId,
                    client,
                    mainActivityActivity,
                    workingMode = workingMode
                )
            }
            changeSession(mainActivityActivity, session_id = sessionId)
        }

        // Helper function to handle closing a session
        fun handleCloseSession(sessionId: String, currentSessionId: String) {
            val service = mainActivityActivity.sessionBinder?.getService() ?: return
            val keys = service.sessionOrder.toList()
            
            if (keys.size <= 1) {
                // Last session - check behavior setting
                if (Settings.close_last_session_behavior == CloseLastSessionBehavior.NEW_SESSION) {
                    // Create new session BEFORE terminating old one to prevent service stopSelf()
                    createNewSession(Settings.working_Mode)
                    // Now safe to terminate the old session
                    mainActivityActivity.sessionBinder?.terminateSession(sessionId)
                } else {
                    // Exit app - terminate then finish
                    mainActivityActivity.sessionBinder?.terminateSession(sessionId)
                    if (service.sessionOrder.isEmpty()) {
                        mainActivityActivity.finish()
                    }
                }
            } else {
                // Not last session - switch to adjacent session if closing current
                if (sessionId == currentSessionId) {
                    val currentIndex = keys.indexOf(sessionId)
                    val nextId = if (currentIndex < keys.size - 1) {
                        keys[currentIndex + 1]
                    } else {
                        keys[currentIndex - 1]
                    }
                    changeSession(mainActivityActivity, nextId)
                }
                mainActivityActivity.sessionBinder?.terminateSession(sessionId)
            }
        }
        // Add session dialog (shared between wide and narrow layouts)
        if (showAddDialog){
            BasicAlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                }
            ) {

                PreferenceGroup {
                    SettingsCard(
                        title = { Text("Alpine") },
                        description = {Text(stringResource(strings.alpine_desc))},
                        onClick = {
                           createNewSession(workingMode = WorkingMode.ALPINE)
                            showAddDialog = false
                        })

                    SettingsCard(
                        title = { Text("Android") },
                        description = {Text(stringResource(strings.android_desc))},
                        onClick = {
                            createNewSession(workingMode = WorkingMode.ANDROID)
                            showAddDialog = false
                        })

                    SettingsCard(
                        title = { Text(stringResource(strings.alpine_root)) },
                        description = {Text(stringResource(strings.alpine_root_desc))},
                        onClick = {
                            createNewSession(workingMode = WorkingMode.ALPINE_ROOT)
                            showAddDialog = false
                        })
            }
        }
        }

        // Rename session dialog
        showRenameDialogFor?.let { sessionId ->
            val service = mainActivityActivity.sessionBinder?.getService()
            val currentDisplayTitle = service?.getDisplayTitle(sessionId) ?: sessionId
            var renameValue by remember(sessionId) { mutableStateOf(currentDisplayTitle) }
            InputDialog(
                title = stringResource(strings.session) + " — " + sessionId,
                inputLabel = stringResource(strings.session),
                inputValue = renameValue,
                onInputValueChange = { renameValue = it },
                onConfirm = {
                    service?.setCustomName(sessionId, renameValue)
                },
                onDismiss = { showRenameDialogFor = null }
            )
        }

        if (isTabBarMode) {
            SetStatusBarTextColor(isDarkIcons = darkText.value)
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                val service = mainActivityActivity.sessionBinder?.getService()
                val sessionKeys = service?.sessionOrder?.toList() ?: emptyList()
                val currentSessionId = service?.currentSession?.value?.first ?: ""

                SessionTabBar(
                    sessions = sessionKeys,
                    currentSessionId = currentSessionId,
                    getDisplayTitle = { id -> service?.getDisplayTitle(id) ?: id },
                    getWorkingMode = { id -> service?.getWorkingMode(id) },
                    onSelectSession = { id -> changeSession(mainActivityActivity, id) },
                    onCloseSession = { id -> handleCloseSession(id, currentSessionId) },
                    onAddSession = { showAddDialog = true },
                    onRenameSession = { id -> showRenameDialogFor = id },
                    onOpenSettings = { navController.navigate(MainActivityRoutes.Settings.route) },
                    modifier = Modifier.fillMaxWidth()
                )

                TabBarTerminalContent(
                    mainActivityActivity = mainActivityActivity,
                    modifier = Modifier.weight(1f)
                )
            }
        } else {
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val configuration = LocalConfiguration.current
            val screenWidthDp = configuration.screenWidthDp
            val drawerWidth = (screenWidthDp * 0.84).dp

            BackHandler(enabled = drawerState.isOpen) {
                scope.launch { drawerState.close() }
            }

            if (drawerState.isClosed) {
                SetStatusBarTextColor(isDarkIcons = darkText.value)
            } else {
                SetStatusBarTextColor(isDarkIcons = !isDarkMode)
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = drawerState.isOpen || !(showToolbar.value && (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || showHorizontalToolbar.value)),
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(strings.session),
                                    style = MaterialTheme.typography.titleLarge
                                )

                                Row {
                                    val keyboardController = LocalSoftwareKeyboardController.current
                                    IconButton(onClick = {
                                        navController.navigate(MainActivityRoutes.Settings.route)
                                        keyboardController?.hide()
                                    }) {
                                        Icon(
                                            imageVector = Icons.Outlined.Settings,
                                            contentDescription = null
                                        )
                                    }

                                    IconButton(onClick = {
                                        showAddDialog = true
                                    }) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null
                                        )
                                    }

                                }


                            }

                            mainActivityActivity.sessionBinder?.getService()?.let { service ->
                                val sessionKeys = service.sessionOrder.toList()
                                LazyColumn {
                                    itemsIndexed(sessionKeys) { index, session_id ->
                                        SelectableCard(
                                            selected = session_id == service.currentSession.value.first,
                                            onSelect = {
                                                changeSession(
                                                    mainActivityActivity,
                                                    session_id
                                                )
                                            },
                                            onLongPress = {
                                                showRenameDialogFor = session_id
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Session number badge
                                                if (index < 9) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(24.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(
                                                                if (session_id == service.currentSession.value.first)
                                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                                else
                                                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f)
                                                            ),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = "${index + 1}",
                                                            style = MaterialTheme.typography.labelMedium,
                                                            color = if (session_id == service.currentSession.value.first)
                                                                MaterialTheme.colorScheme.primary
                                                            else
                                                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.width(12.dp))
                                                }

                                                Text(
                                                    text = service.getDisplayTitle(session_id),
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = getSessionTextColor(service.getWorkingMode(session_id))
                                                )

                                                if (session_id != service.currentSession.value.first) {
                                                    Spacer(modifier = Modifier.weight(1f))

                                                    IconButton(
                                                        onClick = {
                                                            println(session_id)
                                                            mainActivityActivity.sessionBinder?.terminateSession(
                                                                session_id
                                                            )
                                                        },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                    
                                                        Icon(
                                                            imageVector = Icons.Outlined.Delete,
                                                            contentDescription = null,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }

                                            }
                                        }
                                    }
                                }
                            }

                        }
                    }

                },
                content = {
                    TerminalContent(
                        mainActivityActivity = mainActivityActivity,
                        navController = navController,
                        showAddDialog = { showAddDialog = true },
                        openDrawer = { scope.launch { drawerState.open() } },
                    )
                })
        }
    }
}


/**
 * Returns a color for session text based on working mode privilege level.
 * - Alpine Root: red (danger)
 * - Android: amber (warning)
 * - Alpine/default: theme default
 */
@Composable
fun getSessionTextColor(workingMode: Int?): androidx.compose.ui.graphics.Color {
    return when (workingMode) {
        WorkingMode.ALPINE_ROOT -> androidx.compose.ui.graphics.Color(0xFFEF5350)
        WorkingMode.ANDROID -> androidx.compose.ui.graphics.Color(0xFFFFA726)
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalContent(
    mainActivityActivity: MainActivity,
    navController: NavController,
    showAddDialog: () -> Unit,
    openDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopStart
    ) {
        BackgroundImage()
        val color = getComposeColor()
        Column {


            val showToolbarCondition = showToolbar.value && (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || showHorizontalToolbar.value)

            if (showToolbarCondition) {
                val service = mainActivityActivity.sessionBinder?.getService()
                val currentSessionId = service?.currentSession?.value?.first ?: ""
                val displayTitle = service?.getDisplayTitle(currentSessionId) ?: currentSessionId
                val currentWorkingMode = service?.getWorkingMode(currentSessionId)

                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                    ),
                    title = {
                        Column {
                            Text(text = "ReTerminal", color = color)
                            Text(
                                style = MaterialTheme.typography.bodySmall,
                                text = displayTitle,
                                color = getSessionTextColor(currentWorkingMode)
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { openDrawer() }) {
                            Icon(Icons.Default.Menu, null, tint = color)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAddDialog() }) {
                            Icon(Icons.Default.Add, null, tint = color)
                        }
                    }
                )
            }

            val density = LocalDensity.current
            TerminalPaneContent(
                mainActivityActivity = mainActivityActivity,
                modifier = Modifier
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(top = if (showToolbar.value) {
                        0.dp
                    } else {
                        with(density) {
                            TopAppBarDefaults.windowInsets.getTop(density).toDp()
                        }
                    })
            )
        }



    }
}

@Composable
fun TabBarTerminalContent(
    mainActivityActivity: MainActivity,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.TopStart
    ) {
        BackgroundImage()
        TerminalPaneContent(
            mainActivityActivity = mainActivityActivity,
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding()
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalPaneContent(
    mainActivityActivity: MainActivity,
    modifier: Modifier = Modifier
) {
    // Observe color scheme state to trigger recomposition when it changes
    val currentScheme = ColorSchemeManager.currentScheme.value
    val terminalBackgroundColor = currentScheme.background
    
    Column(modifier = modifier) {
        AndroidView(
            factory = { context ->
                TerminalView(context, null).apply {
                    terminalView = WeakReference(this)
                    setTextSize(
                        dpToPx(
                            Settings.terminal_font_size.toFloat(),
                            context
                        )
                    )
                    val client = TerminalBackEnd(this, mainActivityActivity)

                    val session = if (pendingCommand != null) {
                        mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(
                            pendingCommand!!.id, pendingCommand!!.workingMode
                        )
                        mainActivityActivity.sessionBinder!!.getSession(
                            pendingCommand!!.id
                        )
                            ?: mainActivityActivity.sessionBinder!!.createSession(
                                pendingCommand!!.id,
                                client,
                                mainActivityActivity, workingMode = Settings.working_Mode
                            )
                    } else {
                        mainActivityActivity.sessionBinder!!.getSession(
                            mainActivityActivity.sessionBinder!!.getService().currentSession.value.first
                        )
                            ?: mainActivityActivity.sessionBinder!!.createSession(
                                mainActivityActivity.sessionBinder!!.getService().currentSession.value.first,
                                client,
                                mainActivityActivity, workingMode = Settings.working_Mode
                            )
                    }

                    session.updateTerminalSessionClient(client)
                    attachSession(session)
                    setTerminalViewClient(client)
                    setTypeface(font)

                    post {
                        // Apply the saved color scheme
                        ColorSchemeManager.setTerminalView(this)
                        ColorSchemeManager.applyCurrentSchemeToTerminal()
                        
                        // Get the current scheme and apply background color directly
                        val scheme = ColorSchemeManager.getCurrentScheme()
                        // If a background image is set, make terminal view transparent
                        // so the image shows through; otherwise use scheme background
                        if (bitmap.value != null) {
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        } else {
                            setBackgroundColor(scheme.background)
                        }
                        
                        // Update darkText based on scheme
                        darkText.value = !scheme.isDark
                        
                        keepScreenOn = true
                        requestFocus()
                        isFocusableInTouchMode = true
                        
                        // Legacy colors.properties support (can override scheme)
                        val colorsFile = localDir().child("colors.properties")
                        if (colorsFile.exists() && colorsFile.isFile) {
                            val props = Properties()
                            FileInputStream(colorsFile).use { input ->
                                props.load(input)
                            }
                            TerminalColors.COLOR_SCHEME.updateWith(props)
                            mEmulator?.mColors?.reset()
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            update = { terminalView ->
                // Apply color scheme background - this runs when currentScheme changes
                // If a background image is set, make terminal view transparent
                // so the image shows through; otherwise use scheme background
                if (bitmap.value != null) {
                    terminalView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                } else {
                    terminalView.setBackgroundColor(terminalBackgroundColor)
                }
                terminalView.mEmulator?.mColors?.reset()
                terminalView.onScreenUpdated()
                
                // Update darkText based on scheme
                darkText.value = !currentScheme.isDark
                
                // Handle custom background image text color adjustment
                if (bitmap.value != null) {
                    val color = getViewColor()
                    terminalView.mEmulator?.mColors?.mCurrentColors?.apply {
                        set(256, color)
                        set(258, color)
                    }
                }
            },
        )

        if (showVirtualKeys.value) {
            val pagerState = rememberPagerState(pageCount = { 2 })
            val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(75.dp)
            ) { page ->
                when (page) {
                    0 -> {
                        terminalView.get()?.requestFocus()
                        AndroidView(
                            factory = { context ->
                                VirtualKeysView(context, null).apply {
                                    virtualKeysView = WeakReference(this)
                                    virtualKeysViewClient =
                                        terminalView.get()?.mTermSession?.let {
                                            VirtualKeysListener(it)
                                        }

                                    buttonTextColor = onSurfaceColor

                                    reload(
                                        VirtualKeysInfo(
                                            VIRTUAL_KEYS,
                                            "",
                                            VirtualKeysConstants.CONTROL_CHARS_ALIASES
                                        )
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(75.dp)
                        )
                    }

                    1 -> {
                        var text by rememberSaveable { mutableStateOf("") }

                        AndroidView(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(75.dp),
                            factory = { ctx ->
                                EditText(ctx).apply {
                                    maxLines = 1
                                    isSingleLine = true
                                    imeOptions = EditorInfo.IME_ACTION_DONE

                                    doOnTextChanged { textInput, _, _, _ ->
                                        text = textInput.toString()
                                    }

                                    setOnEditorActionListener { _, actionId, _ ->
                                        if (actionId == EditorInfo.IME_ACTION_DONE) {
                                            if (text.isEmpty()) {
                                                val eventDown = KeyEvent(
                                                    KeyEvent.ACTION_DOWN,
                                                    KeyEvent.KEYCODE_ENTER
                                                )
                                                val eventUp = KeyEvent(
                                                    KeyEvent.ACTION_UP,
                                                    KeyEvent.KEYCODE_ENTER
                                                )
                                                terminalView.get()?.dispatchKeyEvent(eventDown)
                                                terminalView.get()?.dispatchKeyEvent(eventUp)
                                            } else {
                                                terminalView.get()?.currentSession?.write(text)
                                                setText("")
                                            }
                                            true
                                        } else {
                                            false
                                        }
                                    }
                                }
                            },
                            update = { editText ->
                                if (editText.text.toString() != text) {
                                    editText.setText(text)
                                    editText.setSelection(text.length)
                                }
                            }
                        )
                    }
                }
            }
        } else {
            virtualKeysView = WeakReference(null)
        }
    }
}

var wallAlpha by mutableFloatStateOf(Settings.wallTransparency)

@Composable
fun BackgroundImage() {
    bitmap.value?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(wallAlpha)
                .zIndex(-1f)
        )
    }
}

@Composable
fun SetStatusBarTextColor(isDarkIcons: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return

    SideEffect {
        WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = isDarkIcons
    }
}


/**
 * Selectable card for the narrow-screen session drawer.
 * Supports long-press for renaming.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SelectableCard(
    selected: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit = {},
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "containerColor"
    )

    Card(
        modifier = modifier.combinedClickable(
            onClick = onSelect,
            onLongClick = onLongPress
        ),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 8.dp else 2.dp
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            content()
        }
    }
}


fun changeSession(mainActivityActivity: MainActivity, session_id: String) {
    terminalView.get()?.apply {
        val client = TerminalBackEnd(this, mainActivityActivity)
        val session =
            mainActivityActivity.sessionBinder!!.getSession(session_id)
                ?: mainActivityActivity.sessionBinder!!.createSession(
                    session_id,
                    client,
                    mainActivityActivity,workingMode = Settings.working_Mode
                )
        session.updateTerminalSessionClient(client)
        attachSession(session)
        setTerminalViewClient(client)
        post {
            // Apply color scheme to this session
            val scheme = ColorSchemeManager.getCurrentScheme()
            // If a background image is set, make terminal view transparent
            if (bitmap.value != null) {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            } else {
                setBackgroundColor(scheme.background)
            }
            
            // Update terminal colors
            mEmulator?.mColors?.reset()
            
            // Update darkText based on scheme
            darkText.value = !scheme.isDark
            
            keepScreenOn = true
            requestFocus()
            isFocusableInTouchMode = true
        }
        virtualKeysView.get()?.apply {
            virtualKeysViewClient =
                terminalView.get()?.mTermSession?.let { VirtualKeysListener(it) }
        }

    }
    mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(session_id,mainActivityActivity.sessionBinder!!.getService().sessionList[session_id]!!)

}


const val VIRTUAL_KEYS =
    ("[" + "\n  [" + "\n    \"ESC\"," + "\n    {" + "\n      \"key\": \"/\"," + "\n      \"popup\": \"\\\\\"" + "\n    }," + "\n    {" + "\n      \"key\": \"-\"," + "\n      \"popup\": \"|\"" + "\n    }," + "\n    \"HOME\"," + "\n    \"UP\"," + "\n    \"END\"," + "\n    \"PGUP\"" + "\n  ]," + "\n  [" + "\n    \"TAB\"," + "\n    \"CTRL\"," + "\n    \"ALT\"," + "\n    \"LEFT\"," + "\n    \"DOWN\"," + "\n    \"RIGHT\"," + "\n    \"PGDN\"" + "\n  ]" + "\n]")
