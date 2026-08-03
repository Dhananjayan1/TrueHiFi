package com.fakehifi.detector

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import android.content.Intent
import android.widget.Toast
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.fakehifi.detector.model.ResultFilter
import com.fakehifi.detector.model.SortOrder
import com.fakehifi.detector.model.TrackInfo
import com.fakehifi.detector.model.TrackResult
import com.fakehifi.detector.model.Verdict
import com.fakehifi.detector.repository.UserPreferencesRepository
import com.fakehifi.detector.ui.DetailScreen
import com.fakehifi.detector.ui.OnboardingScreen
import com.fakehifi.detector.ui.theme.TrueHiFiTheme
import com.fakehifi.detector.ui.theme.VerdictFake
import com.fakehifi.detector.ui.theme.VerdictGenuine
import com.fakehifi.detector.ui.theme.VerdictSuspicious
import com.fakehifi.detector.ui.theme.VerdictUnknown
import com.fakehifi.detector.viewmodel.ScanViewModel
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()
    private lateinit var userPreferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userPreferencesRepository = UserPreferencesRepository(this)

        setContent {
            val hasCompletedOnboarding by userPreferencesRepository.hasCompletedOnboarding.collectAsState(initial = null)
            val contributeToCommunity by userPreferencesRepository.contributeToCommunity.collectAsState(initial = false)
            val pickedFolderUri by userPreferencesRepository.pickedFolderUri.collectAsState(initial = null)
            val lastSeenVersion by userPreferencesRepository.lastSeenVersionCode.collectAsState(initial = 0)

            TrueHiFiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (hasCompletedOnboarding != null) {
                        if (lastSeenVersion < 2 && hasCompletedOnboarding == true) {
                            ChangelogDialog(
                                onDismiss = {
                                    lifecycleScope.launch { userPreferencesRepository.setLastSeenVersionCode(2) }
                                }
                            )
                        }

                        AppNavHost(
                            viewModel = viewModel,
                            startDestination = if (hasCompletedOnboarding == true) "list" else "onboarding",
                            contributeEnabled = contributeToCommunity,
                            pickedFolderUri = pickedFolderUri,
                            onOnboardingComplete = {
                                lifecycleScope.launch { 
                                    userPreferencesRepository.setCompletedOnboarding(true)
                                    userPreferencesRepository.setLastSeenVersionCode(2)
                                }
                            },
                            onContributeToggle = {
                                lifecycleScope.launch { userPreferencesRepository.setContributeToCommunity(it) }
                            },
                            onFolderPicked = {
                                lifecycleScope.launch { userPreferencesRepository.setPickedFolderUri(it) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChangelogDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's New in v1.1", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ChangelogItem("🚀 Performance", "Parallel scanning (up to 4 tracks) and throttled UI updates for 3x faster analysis.")
                ChangelogItem("🔍 Deep Zoom", "Pinch-to-zoom on spectrograms to inspect digital shoulders and noise.")
                ChangelogItem("📊 Dynamic Range", "Professional DR Metering (DR1-20) and Loudness Audit signals.")
                ChangelogItem("📂 Folder Scanning", "Choose specific folders or scan external USB/SD storage via SAF.")
                ChangelogItem("☁️ Community", "Opt-in to contribute results and see community-flagged fakes.")
                ChangelogItem("📜 Hi-Fi Certificates", "Generate shareable reports with full technical breakdown.")
                ChangelogItem("🧹 Safe Cleanup", "Move fakes to system Trash instead of permanent deletion.")
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Let's Go!")
            }
        }
    )
}

@Composable
fun ChangelogItem(title: String, description: String) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun AppNavHost(
    viewModel: ScanViewModel,
    startDestination: String,
    contributeEnabled: Boolean,
    pickedFolderUri: String?,
    onOnboardingComplete: () -> Unit,
    onContributeToggle: (Boolean) -> Unit,
    onFolderPicked: (String?) -> Unit
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    var pendingUris by remember { mutableStateOf<List<String>>(emptyList()) }

    val permissions = remember {
        buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            onOnboardingComplete()
            navController.navigate("list") {
                popUpTo("onboarding") { inclusive = true }
            }
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onTracksDeleted(pendingUris)
        }
    }

    val trashLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // After trashing, we treat them as deleted from our DB for now
            viewModel.onTracksDeleted(pendingUris)
        }
    }

    fun launchDelete(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModel.createDeleteRequest(uris)?.let { pendingIntent ->
            pendingUris = uris
            deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        }
    }

    fun launchTrash(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModel.createTrashRequest(uris, true)?.let { pendingIntent ->
            pendingUris = uris
            trashLauncher.launch(IntentSenderRequest.Builder(pendingIntent).build())
        }
    }

    val context = LocalContext.current

    fun shareTrackResult(result: TrackResult) {
        val summary = """
            🎵 Hi-Fi Verification Report
            Track: ${result.track.title}
            Artist: ${result.track.artist}
            
            Verdict: ${result.verdict.name}
            Confidence: ${result.confidencePercent}%
            
            Metrics:
            - Sample Rate: ${result.sampleRateHz / 1000.0}kHz
            - Bit Depth: ${result.bitDepth}-bit
            - Detected Cutoff: ${result.detectedCutoffHz / 1000.0}kHz
            - Dynamic Range: DR${result.qualityResult?.drRating ?: "N/A"}
            
            Reason: ${result.reason}
            
            Verified with TrueHiFi 🔍
        """.trimIndent()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Hi-Fi Verification: ${result.track.title}")
            putExtra(Intent.EXTRA_TEXT, summary)
        }
        context.startActivity(Intent.createChooser(intent, "Share Hi-Fi Report"))
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.startSingleFileScan(uri)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            onFolderPicked(uri.toString())
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("onboarding") {
            OnboardingScreen(
                onPermissionsRequest = {
                    launcher.launch(permissions)
                }
            )
        }
        composable("list") {
            MainScreen(
                viewModel = viewModel,
                contributeEnabled = contributeEnabled,
                pickedFolderUri = pickedFolderUri,
                onDeleteTracks = { launchDelete(it) },
                onTrashTracks = { launchTrash(it) },
                onContributeToggle = onContributeToggle,
                onPickFolder = { folderPickerLauncher.launch(null) },
                onClearFolder = { onFolderPicked(null) },
                onPickFile = { filePickerLauncher.launch(arrayOf("audio/*", "application/octet-stream")) }
            ) { track ->
                navController.navigate("detail/${URLEncoder.encode(track.uri, "UTF-8")}")
            }
        }
        composable(
            "detail/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: return@composable
            val uri = URLDecoder.decode(encodedUri, "UTF-8")
            
            DetailScreen(
                initialUri = uri,
                results = uiState.filteredResults,
                isScanning = uiState.isScanning,
                onBack = { navController.popBackStack() },
                onDeepScan = { viewModel.startDeepScan(it) },
                onDelete = {
                    launchDelete(listOf(it))
                },
                onShare = { shareTrackResult(it) },
                contributeEnabled = contributeEnabled,
                onContribute = {
                    Toast.makeText(context, "Thank you! Result contributed to the community database.", Toast.LENGTH_LONG).show()
                },
                observeResult = { viewModel.observeFullResult(it) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ScanViewModel,
    contributeEnabled: Boolean,
    pickedFolderUri: String?,
    onDeleteTracks: (List<String>) -> Unit,
    onTrashTracks: (List<String>) -> Unit,
    onContributeToggle: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onClearFolder: () -> Unit,
    onPickFile: () -> Unit,
    onTrackClick: (TrackInfo) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Fix Filter/Sort Scroll Position: Snap back to top when filter or sort changes
    LaunchedEffect(uiState.filter, uiState.sortOrder, uiState.searchQuery) {
        if (uiState.filteredResults.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }
    
    val permissions = remember {
        buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }

    // Initialize permission state by checking actual system status to avoid "double tap" issue
    var hasPermission by remember {
        mutableStateOf(
            permissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteBatchMenu by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result.values.all { it }
        hasPermission = granted
        if (granted) {
            viewModel.startScan()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (uiState.isSelectionMode) {
                        Text("${uiState.selectedUris.size} Selected")
                    } else {
                        Text("TrueHiFi")
                    }
                },
                navigationIcon = {
                    if (uiState.isSelectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear Selection")
                        }
                    }
                },
                actions = {
                    if (uiState.isSelectionMode) {
                        var showSelectionMenu by remember { mutableStateOf(false) }
                        
                        Box {
                            IconButton(onClick = { showSelectionMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "Selection Options")
                            }
                            DropdownMenu(expanded = showSelectionMenu, onDismissRequest = { showSelectionMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Select all FAKES") },
                                    onClick = { showSelectionMenu = false; viewModel.selectAllByVerdict(Verdict.FAKE) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select all SUSPICIOUS") },
                                    onClick = { showSelectionMenu = false; viewModel.selectAllByVerdict(Verdict.SUSPICIOUS) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select all visible") },
                                    onClick = { showSelectionMenu = false; viewModel.selectAllFiltered() }
                                )
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    DropdownMenuItem(
                                        text = { Text("Move selected to TRASH") },
                                        onClick = { showSelectionMenu = false; onTrashTracks(uiState.selectedUris.toList()) }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = { onDeleteTracks(uiState.selectedUris.toList()) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        val canDelete = remember(uiState.filter, uiState.fakeCount, uiState.suspiciousCount) {
                            when (uiState.filter) {
                                ResultFilter.FAKE -> uiState.fakeCount > 0
                                ResultFilter.SUSPICIOUS -> uiState.suspiciousCount > 0
                                ResultFilter.ALL -> uiState.fakeCount > 0 || uiState.suspiciousCount > 0
                                else -> false
                            }
                        }

                        if (canDelete) {
                            Box {
                                IconButton(onClick = {
                                    when (uiState.filter) {
                                        ResultFilter.FAKE -> onDeleteTracks(uiState.results.filter { it.verdict == Verdict.FAKE }.map { it.track.uri })
                                        ResultFilter.SUSPICIOUS -> onDeleteTracks(uiState.results.filter { it.verdict == Verdict.SUSPICIOUS }.map { it.track.uri })
                                        ResultFilter.ALL -> showDeleteBatchMenu = true
                                        else -> {}
                                    }
                                }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete items", tint = MaterialTheme.colorScheme.error)
                                }
                                DropdownMenu(expanded = showDeleteBatchMenu, onDismissRequest = { showDeleteBatchMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Delete all FAKES") },
                                        onClick = {
                                            showDeleteBatchMenu = false
                                            onDeleteTracks(uiState.results.filter { it.verdict == Verdict.FAKE }.map { it.track.uri })
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Delete all SUSPICIOUS") },
                                        onClick = {
                                            showDeleteBatchMenu = false
                                            onDeleteTracks(uiState.results.filter { it.verdict == Verdict.SUSPICIOUS }.map { it.track.uri })
                                        }
                                    )
                                }
                            }
                        }

                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Clear cache & rescan") },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.clearCacheAndResults()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (contributeEnabled) "Opt-out of Community" else "Opt-in to Community") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onContributeToggle(!contributeEnabled)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (pickedFolderUri == null) "Select scan folder" else "Change scan folder") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onPickFolder()
                                    }
                                )
                                if (pickedFolderUri != null) {
                                    DropdownMenuItem(
                                        text = { Text("Clear scan folder (Full Device)") },
                                        onClick = {
                                            showOverflowMenu = false
                                            onClearFolder()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Scan single file...") },
                                    onClick = {
                                        showOverflowMenu = false
                                        onPickFile()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (!hasPermission) {
                            launcher.launch(permissions)
                        } else {
                            viewModel.startScan(pickedFolderUri)
                        }
                    },
                    expanded = !uiState.isScanning,
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    text = { Text(if (pickedFolderUri == null) "Scan Library" else "Scan Folder") }
                )
            }
        }
    ) { padding ->
        if (uiState.results.isEmpty() && !uiState.isScanning) {
            BeginnersGuide(padding)
        } else {
            Column(modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp)) {
                ScanningProgress(
                    isScanning = uiState.isScanning,
                    scannedTracks = uiState.scannedTracks,
                    totalTracks = uiState.totalTracks,
                    currentTitle = uiState.currentTitle,
                    onCancel = { viewModel.cancelScan() }
                )

                if (uiState.results.isNotEmpty() || uiState.isScanning) {
                    Spacer(Modifier.height(16.dp))
                    
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search tracks, artists, or filenames...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true
                    )
                    
                    Spacer(Modifier.height(8.dp))

                    Text(
                        "${uiState.results.size} lossless/hi-res files — ${uiState.fakeCount} fake, ${uiState.suspiciousCount} suspicious",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChipsRow(
                            selected = uiState.filter,
                            onSelect = { viewModel.setFilter(it) },
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Latest First") },
                                    leadingIcon = { if (uiState.sortOrder == SortOrder.LATEST_FIRST) Icon(Icons.Default.Check, null) },
                                    onClick = { showSortMenu = false; viewModel.setSortOrder(SortOrder.LATEST_FIRST) }
                                )
                                DropdownMenuItem(
                                    text = { Text("A-Z (Title)") },
                                    leadingIcon = { if (uiState.sortOrder == SortOrder.TITLE_A_TO_Z) Icon(Icons.Default.Check, null) },
                                    onClick = { showSortMenu = false; viewModel.setSortOrder(SortOrder.TITLE_A_TO_Z) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Verdict (Fakes first)") },
                                    leadingIcon = { if (uiState.sortOrder == SortOrder.VERDICT) Icon(Icons.Default.Check, null) },
                                    onClick = { showSortMenu = false; viewModel.setSortOrder(SortOrder.VERDICT) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                ResultsList(
                    results = uiState.filteredResults,
                    listState = listState,
                    selectedUris = uiState.selectedUris,
                    isSelectionMode = uiState.isSelectionMode,
                    onClick = { uri, track ->
                        if (uiState.isSelectionMode) {
                            viewModel.toggleSelection(uri)
                        } else {
                            onTrackClick(track)
                        }
                    },
                    onLongClick = { viewModel.toggleSelection(it) }
                )
            }
        }
    }
}

@Composable
fun ScanningProgress(
    isScanning: Boolean,
    scannedTracks: Int,
    totalTracks: Int,
    currentTitle: String,
    onCancel: () -> Unit
) {
    if (isScanning) {
        Column {
            Spacer(Modifier.height(8.dp))
            val progress = if (totalTracks > 0) scannedTracks / totalTracks.toFloat() else 0f
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$scannedTracks/$totalTracks — $currentTitle",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onCancel) {
                    Text("Cancel")
                }
            }
            Text(
                "Scan keeps running even if you leave the app — check the notification.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun ResultsList(
    results: List<TrackResult>,
    listState: LazyListState,
    selectedUris: Set<String>,
    isSelectionMode: Boolean,
    onClick: (String, TrackInfo) -> Unit,
    onLongClick: (String) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
        items(results, key = { it.track.uri }) { result ->
            TrackRow(
                result = result,
                isSelected = selectedUris.contains(result.track.uri),
                isSelectionMode = isSelectionMode,
                onClick = { onClick(result.track.uri, result.track) },
                onLongClick = { onLongClick(result.track.uri) }
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun BeginnersGuide(padding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(100.dp).clip(CircleShape),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Welcome to TrueHiFi",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your library has not been scanned yet. Tap 'Scan Library' below to detect upscaled fakes and lossy compression in your lossless files.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(32.dp))
        GlossaryCard()
    }
}

@Composable
fun GlossaryCard() {
    var expanded by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        onClick = { expanded = !expanded },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Understanding the Metrics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand"
                )
            }
            
            if (expanded) {
                Spacer(Modifier.height(16.dp))
                
                GlossaryItem(
                    term = "Spectral Slope",
                    definition = "Measures how sharply high frequencies drop off. A steep brick-wall slope usually indicates a lossy MP3/AAC."
                )
                
                Spacer(Modifier.height(12.dp))
                
                GlossaryItem(
                    term = "Dynamic Range (DR)",
                    definition = "The difference between the loudest and quietest parts of the track. Higher DR means less compression and better mastering."
                )
                
                Spacer(Modifier.height(12.dp))
                
                GlossaryItem(
                    term = "Cutoff",
                    definition = "The exact frequency where the audio data abruptly stops."
                )
            }
        }
    }
}

@Composable
fun GlossaryItem(term: String, definition: String) {
    Column {
        Text(
            text = term,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = definition,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FilterChipsRow(selected: ResultFilter, onSelect: (ResultFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.horizontalScroll(rememberScrollState())) {
        ResultFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.name.lowercase().replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    result: TrackResult,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val color = when (result.verdict) {
        Verdict.GENUINE -> VerdictGenuine
        Verdict.SUSPICIOUS -> VerdictSuspicious
        Verdict.FAKE -> VerdictFake
        Verdict.UNKNOWN -> VerdictUnknown
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            Spacer(Modifier.width(8.dp))
        }

        Box(modifier = Modifier.size(12.dp).background(color, shape = CircleShape))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(result.track.title, style = MaterialTheme.typography.bodyLarge)
            val qualitySuffix = result.qualityResult?.let { " · DR%.0f".format(it.dynamicRange) } ?: ""
            val bitrateEstimation = if (result.originalBitrateKbps > 0) " · ~${result.originalBitrateKbps}kbps src" else ""
            Text(
                "${result.track.artist} · ${result.sampleRateHz / 1000}kHz/${result.bitDepth}-bit · " +
                    "cutoff ~${result.detectedCutoffHz / 1000.0}kHz · ${result.confidencePercent}% confidence" +
                    (if (result.isDeepScan) " · deep" else "") + qualitySuffix + bitrateEstimation,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
