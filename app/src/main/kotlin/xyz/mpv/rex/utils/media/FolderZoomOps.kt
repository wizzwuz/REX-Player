package xyz.mpv.rex.utils.media

import xyz.mpv.rex.database.entities.PlaybackStateEntity
import xyz.mpv.rex.domain.media.model.Video
import xyz.mpv.rex.domain.playbackstate.repository.PlaybackStateRepository
import xyz.mpv.rex.preferences.BrowserPreferences
import xyz.mpv.rex.preferences.PlayerPreferences
import xyz.mpv.rex.utils.sort.SortUtils

object FolderZoomOps {

  sealed class ApplyZoomResult {
    data object NoVideos : ApplyZoomResult()
    data object NoFirstVideoState : ApplyZoomResult()
    data class Success(val appliedCount: Int) : ApplyZoomResult()
  }

  /**
   * Reads the zoom position (and visual pan, scale, aspect ratio, subtitle position)
   * of the first video in [videos] (ordered according to current sort preferences)
   * and applies it to all other videos in the folder.
   */
  suspend fun applyFirstVideoZoom(
    videos: List<Video>,
    playbackStateRepository: PlaybackStateRepository,
    browserPreferences: BrowserPreferences,
    playerPreferences: PlayerPreferences,
  ): ApplyZoomResult {
    if (videos.isEmpty()) return ApplyZoomResult.NoVideos

    val sortedVideos = SortUtils.sortVideos(
      videos,
      browserPreferences.videoSortType.get(),
      browserPreferences.videoSortOrder.get(),
    )

    val firstVideo = sortedVideos.firstOrNull() ?: return ApplyZoomResult.NoVideos
    val allStates = playbackStateRepository.getAllPlaybackStates()
    val index = PlaybackStateIndex.of(allStates)
    val firstState = index.forVideo(firstVideo)
      ?: playbackStateRepository.getVideoDataByTitle(firstVideo.displayName)
      ?: playbackStateRepository.getVideoDataByTitle(firstVideo.path)
      ?: return ApplyZoomResult.NoFirstVideoState

    // Ensure per-video zoom retention is enabled so the saved zoom is loaded
    playerPreferences.rememberVideoZoom.set(true)

    val remainingVideos = sortedVideos.drop(1)
    if (remainingVideos.isEmpty()) {
      return ApplyZoomResult.Success(0)
    }

    for (video in remainingVideos) {
      val existing = index.forVideo(video)
        ?: playbackStateRepository.getVideoDataByTitle(video.displayName)
        ?: playbackStateRepository.getVideoDataByTitle(video.path)

      val targetTitle = existing?.mediaTitle ?: video.displayName

      val newState = if (existing != null) {
        existing.copy(
          videoZoom = firstState.videoZoom,
          videoPanX = firstState.videoPanX,
          videoPanY = firstState.videoPanY,
          videoScaleX = firstState.videoScaleX,
          videoScaleY = firstState.videoScaleY,
          videoAspect = firstState.videoAspect,
          customAspectRatio = firstState.customAspectRatio,
          subPos = firstState.subPos ?: existing.subPos,
          secondarySubPos = firstState.secondarySubPos ?: existing.secondarySubPos,
        )
      } else {
        PlaybackStateEntity(
          mediaTitle = targetTitle,
          lastPosition = 0,
          playbackSpeed = 1.0,
          videoZoom = firstState.videoZoom,
          videoPanX = firstState.videoPanX,
          videoPanY = firstState.videoPanY,
          videoScaleX = firstState.videoScaleX,
          videoScaleY = firstState.videoScaleY,
          sid = -1,
          secondarySid = -1,
          subDelay = 0,
          subSpeed = 1.0,
          subPos = firstState.subPos,
          secondarySubPos = firstState.secondarySubPos,
          aid = -1,
          audioDelay = 0,
          timeRemaining = (video.duration / 1000).toInt().coerceAtLeast(0),
          hasBeenWatched = false,
          videoAspect = firstState.videoAspect,
          customAspectRatio = firstState.customAspectRatio,
        )
      }
      playbackStateRepository.upsert(newState)
    }

    return ApplyZoomResult.Success(remainingVideos.size)
  }
}
