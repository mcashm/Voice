package voice.core.data.progress

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import voice.core.initializer.AppInitializer

@ContributesIntoSet(AppScope::class)
@Inject
class ProgressSyncInitializer(
  private val coordinator: ProgressSyncCoordinator,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    coordinator.start()
  }
}
