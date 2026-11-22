package com.forgerom.app

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.forgerom.app.ai.BootloaderInfo
import com.forgerom.app.ai.BootloaderStatus
import com.forgerom.app.ai.DownloadInfo
import com.forgerom.app.ai.RomFinder
import com.forgerom.app.ai.RomSearchResult
import com.forgerom.app.ai.UiText
import com.forgerom.app.ai.UserFacingException
import com.forgerom.app.device.BootloaderPolicy
import com.forgerom.app.device.CatalogEntry
import com.forgerom.app.device.DeviceCatalog
import com.forgerom.app.device.DeviceInfo
import com.forgerom.app.device.HardwareBootloader
import com.forgerom.app.sources.FreeSources
import com.forgerom.app.sources.RemoteRomCatalog
import com.forgerom.app.sources.SourceParsers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class Screen { ONBOARDING, MAIN, PICKER, SETTINGS, GUIDE }

/** SUGGEST = no root, never changes the device. ROOT = asks for su permission. */
enum class AppMode { SUGGEST, ROOT }

data class UiError(@StringRes val res: Int, val detail: String = "")
data class Notice(@StringRes val res: Int, val positive: Boolean)
data class UpdateInfo(val tag: String, val url: String, val changelog: String = "", val download: DownloadInfo? = null)

data class UiState(
    val screen: Screen = Screen.MAIN,
    val mode: AppMode = AppMode.SUGGEST,
    val rootGranted: Boolean = false,
    val rootChecking: Boolean = false,
    val notice: Notice? = null,
    val detected: DeviceInfo? = null,
    val detectedName: String? = null,
    val resolvingName: Boolean = true,
    val manual: CatalogEntry? = null,          // manually selected device (replaces the detected one)
    val apiKey: String = "",
    val searching: Boolean = false,
    val aiSearching: Boolean = false,
    val result: RomSearchResult? = null,
    val cachedAt: Long? = null,                // set while the shown result comes from the cache
    val update: UpdateInfo? = null,            // a newer ForgeROM release exists
    val updateCheckEnabled: Boolean = true,
    val downloadChecks: List<DownloadVerification> = emptyList(),
    val crash: String? = null,                 // summary of a crash from the previous run
    val error: UiError? = null,
    val pickerQuery: String = "",
    val pickerResults: List<CatalogEntry> = emptyList(),
    val pickerLoading: Boolean = false,
    val catalogStatus: String = "Checking GitHub phone database…",
    val catalogRefreshing: Boolean = false,
    val hardware: HardwareBootloader.Report? = null,
    val hardwareRefreshing: Boolean = false,
    val installerPreparing: Boolean = false,
    val installerReady: Boolean = false,
    val installerLocation: String = "",
    val installerMessage: String = ""
) {
    /** The device used for searching: the manual selection if any, otherwise the detected one. */
    val activeDevice: DeviceInfo?
        get() = manual?.let { m ->
            DeviceInfo(
                manufacturer = m.brand, brand = m.brand, model = m.model, codename = m.codename,
                androidVersion = "", sdk = 0, soc = null
            )
        } ?: detected

    val activeName: String? get() = manual?.name ?: detectedName

    /** "Samsung Galaxy S20 FE SM-G780G (r8q)" or null while the device is still being detected. */
    val displayName: String?
        get() {
            val d = activeDevice ?: return null
            val base = activeName ?: d.manufacturer.replaceFirstChar { it.uppercase() }
            return "$base ${d.model} (${d.codename})".trim()
        }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("forgerom", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS) // AI web search can take a while
        .addInterceptor { chain ->
            // OrangeFox asks API clients to send a custom user agent.
            chain.proceed(chain.request().newBuilder().header("User-Agent", "ForgeROM/0.1 (Android)").build())
        }
        .build()
    private val fastClient = client.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val catalog = DeviceCatalog(app, fastClient)
    private val freeSources = FreeSources(fastClient)
    private val communityRoms = RemoteRomCatalog(app, fastClient)
    private val cache = ResultCache(app)
    private val updateChecker = UpdateChecker(fastClient)
    private val appVersion: String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }
    private val finder = RomFinder(client)
    private var pickerJob: Job? = null
    private var searchJob: Job? = null
    private var aiJob: Job? = null
    private var searchEpoch = 0
    private var rootRequestEpoch = 0

    private fun invalidateSearch() {
        searchEpoch++
        searchJob?.cancel()
        aiJob?.cancel()
        _state.update { it.copy(searching = false, aiSearching = false) }
    }

    fun refreshHardware() {
        _state.update { it.copy(hardwareRefreshing = true) }
        viewModelScope.launch {
            val result = HardwareBootloader.inspect()
            _state.update { it.copy(hardware = result, hardwareRefreshing = false) }
        }
    }

    fun refreshCatalog() {
        _state.update { it.copy(catalogRefreshing = true) }
        viewModelScope.launch {
            try {
                catalog.refresh()
                val current = _state.value
                val detected = current.detected
                val display = detected?.let { catalog.resolve(it)?.name }
                val results = if (current.pickerQuery.isBlank()) emptyList()
                    else catalog.search(current.pickerQuery)
                _state.update { it.copy(catalogStatus = catalog.source,
                    catalogRefreshing = false, pickerResults = results,
                    detectedName = display ?: it.detectedName) }
            } catch (e: Exception) {
                _state.update { it.copy(catalogRefreshing = false,
                    catalogStatus = "Offline — using cached catalogue") }
            }
        }
    }

    /** Recovery handoff: no automatic flashing, only a verified file in Downloads. */
    fun prepareRecoveryZip(uri: Uri, sha256: String) {
        val current = _state.value
        if (current.mode != AppMode.ROOT || !current.rootGranted || current.manual != null) {
            _state.update { it.copy(installerMessage =
                "Root mode and the physically detected device are required.") }
            return
        }
        _state.update { it.copy(installerPreparing = true, installerReady = false,
            installerMessage = "Copying ZIP and verifying SHA-256…") }
        viewModelScope.launch {
            val prepared = RecoveryInstaller.prepare(getApplication(), uri, sha256)
            _state.update {
                if (prepared.isSuccess) {
                    val success = prepared.getOrThrow()
                    it.copy(installerPreparing = false, installerReady = true,
                        installerLocation = success.location,
                        installerMessage = "Verified SHA-256. Ready in " + success.location +
                            ". Choose Install ZIP manually in your compatible recovery.")
                } else {
                    it.copy(installerPreparing = false, installerReady = false,
                        installerLocation = "",
                        installerMessage = prepared.exceptionOrNull()?.message ?: "ZIP preparation failed")
                }
            }
        }
    }

    fun rebootRecovery() {
        val current = _state.value
        if (!current.rootGranted || current.mode != AppMode.ROOT ||
            !current.installerReady || current.manual != null) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RootManager.requestRoot()) {
                    _state.update { it.copy(installerMessage = "Root permission was denied.") }
                    return@launch
                }
                // Opens recovery; the user installs the ZIP there manually.
                ProcessBuilder("su", "-c", "reboot recovery").start()
            } catch (e: Exception) {
                _state.update { it.copy(installerMessage = "Could not open recovery: " +
                    (e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun verifyDownloads() {
        viewModelScope.launch {
            val results = DownloadVerifier.scan(getApplication())
            _state.update { it.copy(downloadChecks = results) }
        }
    }

    private val _state = MutableStateFlow(
        UiState(
            screen = if (prefs.getBoolean("onboarded", false)) Screen.MAIN else Screen.ONBOARDING,
            apiKey = loadApiKey(),
            updateCheckEnabled = prefs.getBoolean("update_check", true),
            crash = CrashLogger.read(app)
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val device = DeviceInfo.fromBuild()
        _state.update { it.copy(detected = device) }
        loadCached()
        showKnownUpdate()
        checkForUpdate()
        verifyDownloads()
        refreshHardware()
        viewModelScope.launch {
            val entry = catalog.resolve(device)
            _state.update { it.copy(detectedName = entry?.name, resolvingName = false, catalogStatus = catalog.source) }
        }
        // Returning user who chose root mode: check su again (no prompt if already allowed).
        if (prefs.getString("mode", "suggest") == "root" && prefs.getBoolean("onboarded", false)) {
            applyMode(AppMode.ROOT)
        }
    }

    // ---- Onboarding, mode and settings ----

    fun completeOnboarding(mode: AppMode) {
        applyMode(mode) {
            prefs.edit().putBoolean("onboarded", true).apply()
            _state.update { it.copy(screen = Screen.MAIN) }
        }
    }

    fun setMode(mode: AppMode) = applyMode(mode)
    fun recheckRoot() = applyMode(AppMode.ROOT)

    private fun applyMode(mode: AppMode, onDone: () -> Unit = {}) {
        val request = ++rootRequestEpoch
        if (mode == AppMode.SUGGEST) {
            prefs.edit().putString("mode", "suggest").apply()
            _state.update { it.copy(mode = AppMode.SUGGEST, rootGranted = false,
                rootChecking = false, installerReady = false) }
            onDone()
            return
        }
        _state.update { it.copy(rootChecking = true) }
        viewModelScope.launch {
            val granted = RootManager.requestRoot()
            if (request != rootRequestEpoch) return@launch
            prefs.edit().putString("mode", if (granted) "root" else "suggest").apply()
            _state.update {
                it.copy(
                    mode = if (granted) AppMode.ROOT else AppMode.SUGGEST,
                    rootGranted = granted,
                    rootChecking = false,
                    installerReady = if (granted) it.installerReady else false,
                    notice = Notice(if (granted) R.string.root_granted else R.string.root_denied, granted)
                )
            }
            onDone()
        }
    }

    fun openSettings() = _state.update { it.copy(screen = Screen.SETTINGS) }
    fun closeSettings() = _state.update { it.copy(screen = Screen.MAIN) }
    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun setApiKey(key: String) {
        val clean = key.trim()
        saveApiKey(clean)
        _state.update { it.copy(apiKey = clean) }
    }

    // ---- API key storage: encrypted with the Android Keystore ----

    private fun loadApiKey(): String {
        val encrypted = prefs.getString("api_key_enc", null)
        if (encrypted != null) return SecureStore.decrypt(encrypted).orEmpty()

        // Keys saved in plain text by 0.1 / 0.2 are migrated (encrypted, then the plain copy is removed).
        val legacy = prefs.getString("api_key", null)
        if (!legacy.isNullOrBlank()) {
            saveApiKey(legacy)
            return legacy
        }
        return ""
    }

    private fun saveApiKey(key: String) {
        val editor = prefs.edit().remove("api_key")
        try {
            if (key.isBlank()) editor.remove("api_key_enc")
            else editor.putString("api_key_enc", SecureStore.encrypt(key))
        } catch (e: Exception) {
            // Keystore unavailable on this device: keep the key in memory only, never store it in plain text.
            editor.remove("api_key_enc")
        }
        editor.apply()
    }

    fun openGuide() = _state.update { it.copy(screen = Screen.GUIDE) }
    fun closeGuide() = _state.update { it.copy(screen = Screen.MAIN) }

    // ---- Crash report ----

    fun dismissCrash() {
        CrashLogger.clear(getApplication())
        _state.update { it.copy(crash = null) }
    }

    // ---- Update check (GitHub releases, at most once a day) ----

    fun setUpdateCheck(enabled: Boolean) {
        prefs.edit().putBoolean("update_check", enabled).apply()
        _state.update { it.copy(updateCheckEnabled = enabled, update = if (enabled) it.update else null) }
        if (enabled) { showKnownUpdate(); checkForUpdate() }
    }

    fun dismissUpdate() {
        _state.value.update?.let { prefs.edit().putString("dismissed_update", it.tag).apply() }
        _state.update { it.copy(update = null) }
    }

    /** Shows an update found earlier (so the card also appears on launches that skip the daily check). */
    private fun showKnownUpdate() {
        if (!_state.value.updateCheckEnabled) return
        val tag = prefs.getString("update_tag", null) ?: return
        val url = prefs.getString("update_url", null) ?: return
        val change = prefs.getString("update_changelog", "").orEmpty()
        val urlApk = prefs.getString("update_apk", null)
        val sha = prefs.getString("update_sha", null)
        val file = prefs.getString("update_filename", null)
        val download = if (urlApk != null && sha != null && file != null)
            DownloadInfo(urlApk, file, prefs.getLong("update_size", -1).takeIf { it > 0 }, sha)
            else null
        if (SourceParsers.isNewer(tag, appVersion) && prefs.getString("dismissed_update", null) != tag) {
            _state.update { it.copy(update = UpdateInfo(tag, url, change, download)) }
        }
    }

    private fun checkForUpdate() {
        if (!_state.value.updateCheckEnabled) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_update_check", 0L) < 24L * 60 * 60 * 1000) return
        viewModelScope.launch {
            val latest = try {
                withContext(Dispatchers.IO) { updateChecker.latest() }
            } catch (e: Exception) {
                null
            } ?: return@launch
            prefs.edit()
                .putLong("last_update_check", now)
                .putString("update_tag", latest.tag)
                .putString("update_url", latest.url)
                .putString("update_changelog", latest.changelog)
                .putString("update_apk", latest.download?.url)
                .putString("update_sha", latest.download?.sha256)
                .putString("update_filename", latest.download?.fileName)
                .putLong("update_size", latest.download?.sizeBytes ?: -1)
                .apply()
            showKnownUpdate()
        }
    }

    // ---- Download (direct file links only; ForgeROM never installs anything) ----

    fun download(file: DownloadInfo) {
        val ok = Downloader.start(getApplication(), file)
        if (ok) verifyDownloads()
        _state.update {
            it.copy(notice = Notice(if (ok) R.string.download_started else R.string.download_failed, ok))
        }
    }

    /** Only opens Android's visible installer UI after the downloaded APK is hash- and signer-verified. */
    fun installVerifiedUpdate(fileName: String) {
        val ok = VerifiedUpdateInstaller.install(getApplication(), fileName)
        _state.update { it.copy(notice = Notice(if (ok) R.string.update_installer_opened
            else R.string.update_install_unavailable, ok)) }
    }

    // ---- Manual device selection ----

    fun openPicker() = _state.update { it.copy(screen = Screen.PICKER, pickerQuery = "", pickerResults = emptyList()) }
    fun closePicker() = _state.update { it.copy(screen = Screen.MAIN) }

    fun onPickerQuery(q: String) {
        _state.update { it.copy(pickerQuery = q, pickerLoading = q.isNotBlank()) }
        pickerJob?.cancel()
        pickerJob = viewModelScope.launch {
            delay(150)
            val r = catalog.search(q)
            _state.update { it.copy(pickerResults = r, pickerLoading = false) }
        }
    }

    fun selectManual(entry: CatalogEntry) {
        invalidateSearch()
        _state.update { it.copy(manual = entry, screen = Screen.MAIN, result = null, cachedAt = null, error = null) }
        loadCached()
    }

    fun useDetected() {
        invalidateSearch()
        _state.update { it.copy(manual = null, result = null, cachedAt = null, error = null) }
        loadCached()
    }

    // ---- Result cache ----

    private fun cacheKey(d: DeviceInfo) = "${d.codename}|${d.model}".lowercase()

    /** Shows saved results (newer than 30 days) for the active device, if there are any. */
    private fun loadCached() {
        val device = _state.value.activeDevice ?: return
        val key = cacheKey(device)
        viewModelScope.launch {
            val hit = withContext(Dispatchers.IO) { cache.get(key) }
            // Ignore the answer if the user switched to another device in the meantime.
            val stillSame = _state.value.activeDevice?.let { cacheKey(it) } == key
            if (stillSame) _state.update {
                if (it.searching || it.aiSearching || it.result != null) it
                else it.copy(result = hit?.result, cachedAt = hit?.savedAt)
            }
        }
    }

    // ---- Search ----

    /** Search only uses authoritative provider data; a codename match is not a flashing guarantee. */
    fun search() {
        val device = _state.value.activeDevice ?: return
        invalidateSearch()
        val epoch = searchEpoch
        val key = cacheKey(device)
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            try {
                // Provider and community ROM records load in parallel, including when offline.
                // Community entries are display-only and never grant flash permissions.
                val (free, curated) = coroutineScope {
                    val officialTask = async { freeSources.search(device) }
                    val curatedTask = async { communityRoms.search(device) }
                    officialTask.await() to curatedTask.await()
                }
                if (epoch != searchEpoch || _state.value.activeDevice?.let(::cacheKey) != key) return@launch
                if (!free.reachable && curated.isEmpty()) {
                    _state.update { it.copy(searching = false, error = UiError(R.string.err_network)) }
                    return@launch
                }
                val bootloader = free.bootloader ?: BootloaderInfo(BootloaderStatus.UNKNOWN,
                    UiText.raw("No verified bootloader policy for this exact model/region"))
                val result = RomSearchResult(
                    marketingName = free.marketingName,
                    bootloader = bootloader,
                    // Prefer the richer community listing when both sources point to the same URL.
                    roms = (curated + free.roms).distinctBy { it.url },
                    recoveries = free.recoveries,
                    warnings = free.warnings + UiText.raw(
                        "Availability is not installation compatibility. Verify firmware, ABI and partitions in the official instructions")
                )
                if (result.roms.isNotEmpty() || result.recoveries.isNotEmpty()) {
                    withContext(Dispatchers.IO) { cache.put(key, result, System.currentTimeMillis()) }
                }
                if (epoch != searchEpoch || _state.value.activeDevice?.let(::cacheKey) != key) return@launch
                _state.update { it.copy(searching = false, result = result, cachedAt = null,
                    detectedName = if (it.manual == null) it.detectedName ?: free.marketingName else it.detectedName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (epoch == searchEpoch) _state.update { it.copy(searching = false,
                    error = UiError(R.string.err_generic, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /**
     * Optional AI deep search (needs the user's own API key). Results are merged into the free results.
     * [language] is the English name of the UI language (e.g. "Turkish"); the AI writes its notes in it.
     */
    fun searchAi(language: String) {
        val state = _state.value
        val device = state.activeDevice ?: return
        if (state.apiKey.isBlank()) {
            _state.update { it.copy(error = UiError(R.string.err_no_api_key)) }
            return
        }
        aiJob?.cancel()
        val epoch = searchEpoch
        val key = cacheKey(device)
        _state.update { it.copy(aiSearching = true, error = null) }
        aiJob = viewModelScope.launch {
            try {
                val ai = finder.find(state.apiKey, device, state.activeName, language)
                if (epoch != searchEpoch || _state.value.activeDevice?.let(::cacheKey) != key) return@launch
                val current = _state.value
                val merged = merge(current.result, ai)
                val savedAt = current.cachedAt ?: System.currentTimeMillis()
                withContext(Dispatchers.IO) { cache.put(key, merged, savedAt) }
                if (epoch != searchEpoch || _state.value.activeDevice?.let(::cacheKey) != key) return@launch
                _state.update { it.copy(aiSearching = false, result = merged,
                    detectedName = if (it.manual == null) it.detectedName ?: ai.marketingName else it.detectedName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UserFacingException) {
                if (epoch == searchEpoch) _state.update { it.copy(aiSearching = false, error = UiError(e.res, e.detail)) }
            } catch (e: Exception) {
                if (epoch == searchEpoch) _state.update { it.copy(aiSearching = false,
                    error = UiError(R.string.err_generic, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** AI guesses never override authoritative provider results or assert that a bootloader is locked. */
    private fun merge(free: RomSearchResult?, ai: RomSearchResult): RomSearchResult {
        val confirmed = free ?: RomSearchResult(null,
            BootloaderInfo(BootloaderStatus.UNKNOWN, UiText.raw("AI unlock claims are not verified")),
            emptyList(), emptyList(), emptyList())
        val existingRoms = confirmed.roms.map { it.url }.toSet()
        val existingRecoveries = confirmed.recoveries.map { it.url }.toSet()
        val extraRoms = ai.roms.filter { it.url !in existingRoms }.map {
            it.copy(status = "unofficial", notes = UiText.raw(
                "AI suggestion — not confirmed compatible. Verify device model and source before use. " +
                (it.notes?.raw ?: "")), download = null)
        }
        val extraRecoveries = ai.recoveries.filter { it.url !in existingRecoveries }.map {
            it.copy(status = "unofficial", notes = UiText.raw("AI suggestion — unverified. " + (it.notes?.raw ?: "")))
        }
        return RomSearchResult(
            marketingName = confirmed.marketingName ?: ai.marketingName,
            bootloader = confirmed.bootloader,
            roms = confirmed.roms + extraRoms,
            recoveries = confirmed.recoveries + extraRecoveries,
            warnings = confirmed.warnings + ai.warnings + UiText.raw("AI search results have not been independently validated")
        )
    }
}
