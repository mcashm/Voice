package voice.features.settings

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.AppInfoProvider
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.GridMode
import voice.core.data.sleeptimer.SleepTimerPreference
import voice.core.data.store.AnalyticsConsentStore
import voice.core.data.store.AutoRewindAmountStore
import voice.core.data.store.DarkThemeStore
import voice.core.data.store.GridModeStore
import voice.core.data.store.ProgressSyncStore
import voice.core.data.store.SeekTimeStore
import voice.core.data.store.SleepTimerPreferenceStore
import voice.core.logging.api.Logger
import voice.core.ui.DARK_THEME_SETTABLE
import voice.core.ui.GridCount
import voice.navigation.Destination
import voice.navigation.Navigator
import java.time.LocalTime
import voice.core.strings.R as StringsR

@Inject
class SettingsViewModel(
  private val application: Application,
  @DarkThemeStore
  private val useDarkThemeStore: DataStore<Boolean>,
  @AutoRewindAmountStore
  private val autoRewindAmountStore: DataStore<Int>,
  @SeekTimeStore
  private val seekTimeStore: DataStore<Int>,
  private val navigator: Navigator,
  private val appInfoProvider: AppInfoProvider,
  @GridModeStore
  private val gridModeStore: DataStore<GridMode>,
  @SleepTimerPreferenceStore
  private val sleepTimerPreferenceStore: DataStore<SleepTimerPreference>,
  @AnalyticsConsentStore
  private val analyticsConsentStore: DataStore<Boolean>,
  @ProgressSyncStore
  private val progressSyncStore: DataStore<String?>,
  private val gridCount: GridCount,
  dispatcherProvider: DispatcherProvider,
) : SettingsListener {

  private val mainScope = MainScope(dispatcherProvider)
  private val dialog = mutableStateOf<SettingsViewState.Dialog?>(null)

  @Composable
  fun viewState(): SettingsViewState {
    val useDarkTheme by remember { useDarkThemeStore.data }.collectAsState(initial = false)
    val autoRewindAmount by remember { autoRewindAmountStore.data }.collectAsState(initial = 0)
    val seekTime by remember { seekTimeStore.data }.collectAsState(initial = 0)
    val gridMode by remember { gridModeStore.data }.collectAsState(initial = GridMode.GRID)
    val autoSleepTimer by remember { sleepTimerPreferenceStore.data }.collectAsState(
      initial = SleepTimerPreference.Default,
    )
    val analyticsEnabled by remember { analyticsConsentStore.data }.collectAsState(initial = false)
    val progressSyncUri by remember { progressSyncStore.data }.collectAsState(initial = null)
    val (progressSyncStatus, progressSyncConfigured, progressSyncError) = remember(progressSyncUri) {
      val uri = progressSyncUri?.let(Uri::parse)
      if (uri == null) {
        Triple(
          application.getString(StringsR.string.settings_progress_sync_summary),
          false,
          null,
        )
      } else {
        val folder = DocumentFile.fromTreeUri(application, uri)
        if (folder == null) {
          Triple(
            application.getString(StringsR.string.settings_progress_sync_summary),
            false,
            application.getString(StringsR.string.settings_progress_sync_error),
          )
        } else {
          val name = folder.name ?: uri.lastPathSegment ?: uri.toString()
          Triple(
            application.getString(StringsR.string.settings_progress_sync_summary_selected, name),
            true,
            null,
          )
        }
      }
    }
    return SettingsViewState(
      useDarkTheme = useDarkTheme,
      showDarkThemePref = DARK_THEME_SETTABLE,
      seekTimeInSeconds = seekTime,
      autoRewindInSeconds = autoRewindAmount,
      dialog = dialog.value,
      appVersion = appInfoProvider.versionName,
      useGrid = when (gridMode) {
        GridMode.LIST -> false
        GridMode.GRID -> true
        GridMode.FOLLOW_DEVICE -> gridCount.useGridAsDefault()
      },
      autoSleepTimer = SettingsViewState.AutoSleepTimerViewState(
        enabled = autoSleepTimer.autoSleepTimerEnabled,
        startTime = autoSleepTimer.autoSleepStartTime,
        endTime = autoSleepTimer.autoSleepEndTime,
      ),
      analyticsEnabled = analyticsEnabled,
      showAnalyticSetting = appInfoProvider.analyticsIncluded,
      progressSyncStatus = progressSyncStatus,
      progressSyncConfigured = progressSyncConfigured,
      progressSyncError = progressSyncError,
    )
  }

  override fun close() {
    navigator.goBack()
  }

  override fun toggleDarkTheme() {
    mainScope.launch {
      useDarkThemeStore.updateData { !it }
    }
  }

  override fun toggleGrid() {
    mainScope.launch {
      gridModeStore.updateData { currentMode ->
        when (currentMode) {
          GridMode.LIST -> GridMode.GRID
          GridMode.GRID -> GridMode.LIST
          GridMode.FOLLOW_DEVICE -> if (gridCount.useGridAsDefault()) {
            GridMode.LIST
          } else {
            GridMode.GRID
          }
        }
      }
    }
  }

  override fun seekAmountChanged(seconds: Int) {
    mainScope.launch {
      seekTimeStore.updateData { seconds }
    }
  }

  override fun onSeekAmountRowClick() {
    dialog.value = SettingsViewState.Dialog.SeekTime
  }

  override fun autoRewindAmountChang(seconds: Int) {
    mainScope.launch {
      autoRewindAmountStore.updateData { seconds }
    }
  }

  override fun onAutoRewindRowClick() {
    dialog.value = SettingsViewState.Dialog.AutoRewindAmount
  }

  override fun dismissDialog() {
    dialog.value = null
  }

  override fun getSupport() {
    navigator.goTo(Destination.Website("https://github.com/VoiceAudiobook/Voice/discussions/categories/q-a"))
  }

  override fun suggestIdea() {
    navigator.goTo(Destination.Website("https://github.com/VoiceAudiobook/Voice/discussions/categories/ideas"))
  }

  override fun openBugReport() {
    val url = "https://github.com/VoiceAudiobook/Voice/issues/new".toUri()
      .buildUpon()
      .appendQueryParameter("template", "bug.yml")
      .appendQueryParameter("version", appInfoProvider.versionName)
      .appendQueryParameter("androidversion", Build.VERSION.SDK_INT.toString())
      .appendQueryParameter("device", Build.MODEL)
      .toString()
    navigator.goTo(Destination.Website(url))
  }

  override fun openTranslations() {
    dismissDialog()
    navigator.goTo(Destination.Website("https://hosted.weblate.org/engage/voice/"))
  }

  override fun openFaq() {
    navigator.goTo(Destination.Website("https://voice.woitaschek.de/faq/"))
  }

  override fun setAutoSleepTimer(checked: Boolean) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepTimerEnabled = checked)
      }
    }
  }

  override fun setAutoSleepTimerStart(time: LocalTime) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepStartTime = time)
      }
    }
  }

  override fun setAutoSleepTimerEnd(time: LocalTime) {
    mainScope.launch {
      sleepTimerPreferenceStore.updateData { currentPrefs ->
        currentPrefs.copy(autoSleepEndTime = time)
      }
    }
  }

  override fun toggleAnalytics() {
    mainScope.launch {
      analyticsConsentStore.updateData { !it }
    }
  }

  override fun onProgressSyncFolderSelected(uri: Uri) {
    mainScope.launch {
      val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      val result = runCatching {
        application.contentResolver.takePersistableUriPermission(uri, flags)
      }
      if (result.isFailure) {
        val throwable = result.exceptionOrNull() ?: return@launch
        Logger.w(throwable, "Failed to persist progress sync permission for $uri")
        return@launch
      }
      progressSyncStore.updateData { uri.toString() }
    }
  }

  override fun onClearProgressSyncFolder() {
    mainScope.launch {
      val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
      val existing = progressSyncStore.data.first()
      if (existing != null) {
        runCatching {
          application.contentResolver.releasePersistableUriPermission(existing.toUri(), flags)
        }.onFailure { error ->
          Logger.w(error, "Failed to release uri permission for $existing")
        }
      }
      progressSyncStore.updateData { null }
    }
  }
}
