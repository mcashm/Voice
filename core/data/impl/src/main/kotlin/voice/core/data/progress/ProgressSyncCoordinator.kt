package voice.core.data.progress

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.documentfile.provider.DocumentFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import voice.core.common.DispatcherProvider
import voice.core.data.BookContent
import voice.core.data.BookId
import voice.core.data.repo.BookContentRepo
import voice.core.data.repo.BookRepository
import voice.core.data.store.ProgressSyncStore
import voice.core.logging.api.Logger
import kotlin.time.Duration.Companion.seconds

@SingleIn(AppScope::class)
@Inject
class ProgressSyncCoordinator(
  private val application: Application,
  private val bookContentRepo: BookContentRepo,
  private val bookRepository: BookRepository,
  @ProgressSyncStore
  private val progressSyncStore: DataStore<String?>,
  private val json: Json,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)
  private val started = AtomicBoolean(false)

  fun start() {
    if (!started.compareAndSet(false, true)) return
    scope.launch { observeForExport() }
    scope.launch { observeForImport() }
  }

  private suspend fun observeForExport() {
    var lastPayload: String? = null
    progressSyncStore.data
      .combine(bookContentRepo.flow()) { uriString, contents ->
        uriString to contents.filter { it.isActive }
      }
      .debounce(EXPORT_DEBOUNCE.inWholeMilliseconds)
      .collect { (uriString, contents) ->
        val uri = uriString?.let(Uri::parse) ?: return@collect
        val payload = buildPayload(contents)
        if (payload == lastPayload) return@collect
        if (writeProgress(uri, payload)) {
          lastPayload = payload
        }
      }
  }

  private suspend fun observeForImport() {
    progressSyncStore.data
      .distinctUntilChanged()
      .flatMapLatest { uriString ->
        uriString?.let { progressFileChangesFlow(Uri.parse(it)) } ?: emptyFlow()
      }
      .collect { uri ->
        importProgress(uri)
      }
  }

  private fun buildPayload(contents: List<BookContent>): String {
    val entries = contents.map { content ->
      BookProgressEntry(
        bookId = content.id.value,
        currentChapter = content.currentChapter.value,
        positionInChapter = content.positionInChapter,
        lastPlayedAt = content.lastPlayedAt.toString(),
      )
    }
    return json.encodeToString(ListSerializer(BookProgressEntry.serializer()), entries)
  }

  private fun writeProgress(uri: Uri, payload: String): Boolean {
    return runCatching {
      val tree = DocumentFile.fromTreeUri(application, uri) ?: return false
      val file = tree.findFile(PROGRESS_FILE_NAME)
        ?: tree.createFile(MIME_TYPE_JSON, PROGRESS_FILE_NAME)
        ?: return false
      application.contentResolver.openOutputStream(file.uri, "wt")?.bufferedWriter().use { writer ->
        writer ?: throw IOException("Unable to open output stream for $uri")
        writer.write(payload)
      }
      true
    }.onFailure { error ->
      Logger.w(error, "Failed to write progress sync file")
    }.getOrDefault(false)
  }

  private fun progressFileChangesFlow(uri: Uri): Flow<Uri> {
    return flow {
      emit(uri)
      var lastSignature: FileSignature? = null
      while (true) {
        val signature = fileSignature(uri)
        if (signature != lastSignature) {
          emit(uri)
          lastSignature = signature
        }
        delay(FILE_OBSERVE_INTERVAL.inWholeMilliseconds)
      }
    }
  }

  private fun fileSignature(uri: Uri): FileSignature? {
    return runCatching {
      val tree = DocumentFile.fromTreeUri(application, uri) ?: return null
      val file = tree.findFile(PROGRESS_FILE_NAME) ?: return null
      FileSignature(file.lastModified(), file.length())
    }.onFailure { error ->
      Logger.w(error, "Failed to read progress sync file metadata")
    }.getOrNull()
  }

  private suspend fun importProgress(uri: Uri) {
    val tree = DocumentFile.fromTreeUri(application, uri)
    if (tree == null) {
      Logger.w("Unable to resolve tree URI $uri for progress sync")
      return
    }
    val file = tree.findFile(PROGRESS_FILE_NAME) ?: return
    val contents = runCatching {
      application.contentResolver.openInputStream(file.uri)?.bufferedReader().use { reader ->
        reader?.readText()
      }
    }.onFailure { error ->
      Logger.w(error, "Failed to open progress sync file")
    }.getOrNull()
    if (contents.isNullOrBlank()) return
    val entries = runCatching {
      json.decodeFromString(ListSerializer(BookProgressEntry.serializer()), contents)
    }.onFailure { error ->
      Logger.w(error, "Failed to decode progress sync file")
    }.getOrNull() ?: return
    for (entry in entries) {
      val remoteLastPlayed = runCatching { Instant.parse(entry.lastPlayedAt) }
        .onFailure { error ->
          Logger.w(error, "Failed to parse lastPlayedAt for ${entry.bookId}")
        }
        .getOrNull() ?: continue
      bookRepository.updateBook(BookId(entry.bookId)) { current ->
        if (!current.isActive) return@updateBook current
        if (entry.currentChapter !in current.chapters.map { it.value }) return@updateBook current
        if (remoteLastPlayed <= current.lastPlayedAt) return@updateBook current
        val targetChapter = current.chapters.firstOrNull { it.value == entry.currentChapter }
          ?: return@updateBook current
        current.copy(
          currentChapter = targetChapter,
          positionInChapter = entry.positionInChapter,
          lastPlayedAt = remoteLastPlayed,
        )
      }
    }
  }

  @Serializable
  private data class BookProgressEntry(
    val bookId: String,
    val currentChapter: String,
    val positionInChapter: Long,
    val lastPlayedAt: String,
  )

  private data class FileSignature(
    val lastModified: Long,
    val length: Long,
  )

  private companion object {
    private const val PROGRESS_FILE_NAME = "VoiceProgress.json"
    private const val MIME_TYPE_JSON = "application/json"
    private val EXPORT_DEBOUNCE = 1.seconds
    private val FILE_OBSERVE_INTERVAL = 5.seconds
  }
}
