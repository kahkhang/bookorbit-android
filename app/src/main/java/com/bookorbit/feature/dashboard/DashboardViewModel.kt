package com.bookorbit.feature.dashboard

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bookorbit.core.db.ReaderProgressDao
import com.bookorbit.core.model.BookCard
import com.bookorbit.core.model.ReadingGoalWidget
import com.bookorbit.core.model.ReadingStreakWidget
import com.bookorbit.core.model.ScrollerType
import com.bookorbit.feature.browse.BrowseRepository
import com.bookorbit.feature.downloads.DownloadsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: BrowseRepository,
    private val downloads: DownloadsRepository,
    private val progressDao: ReaderProgressDao,
) : ViewModel() {

    data class UiState(
        val continueReading: List<BookCard> = emptyList(),
        val continueListening: List<BookCard> = emptyList(),
        val recentlyAdded: List<BookCard> = emptyList(),
        val loading: Boolean = true,
        /** True when every scroller request failed — distinct from a genuinely empty library. */
        val error: Boolean = false,
        /** Null when the server predates the widget endpoints (or the call failed): the strip is simply hidden. */
        val streak: ReadingStreakWidget? = null,
        val goal: ReadingGoalWidget? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true)
        viewModelScope.launch {
            var failures = 0
            suspend fun load(type: String): List<BookCard> =
                runCatching { repo.scroller(type) }
                    .onFailure {
                        failures++
                        Log.w("Dashboard", "scroller $type failed", it)
                    }
                    .getOrDefault(emptyList())

            val streak = async { runCatching { repo.readingStreak() }.getOrNull() }
            val goal = async { runCatching { repo.readingGoal() }.getOrNull() }
            val continueReading = async { load(ScrollerType.CONTINUE_READING) }
            val continueListening = async { load(ScrollerType.CONTINUE_LISTENING) }
            val recentlyAdded = async { load(ScrollerType.RECENTLY_ADDED) }
            val reading = continueReading.await()
            val listening = continueListening.await()
            val recent = recentlyAdded.await()

            if (failures == 3) {
                val offlineCards = runCatching {
                    downloads.downloads.first().map { entity ->
                        val detail = downloads.cachedBook(entity.bookId)
                        val fileId = detail?.files?.firstOrNull()?.id
                        val pct = fileId?.let { progressDao.get(it)?.percentage }
                        BookCard(
                            id = entity.bookId,
                            title = entity.title,
                            authors = entity.authors.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            files = detail?.files.orEmpty(),
                            readingProgress = pct,
                            addedAt = entity.downloadedAt.toString(),
                            hasCover = entity.coverLocalPath != null || detail?.coverSource != null,
                        )
                    }
                }.getOrDefault(emptyList())

                if (offlineCards.isNotEmpty()) {
                    val inProgress = offlineCards.filter { (it.readingProgress ?: 0.0) > 0.0 }
                    _ui.value = UiState(
                        continueReading = inProgress.ifEmpty { listOf(offlineCards.first()) },
                        continueListening = emptyList(),
                        recentlyAdded = offlineCards,
                        loading = false,
                        error = false,
                        streak = null,
                        goal = null,
                    )
                    return@launch
                }
            }

            _ui.value = UiState(
                reading,
                listening,
                recent,
                loading = false,
                error = failures == 3,
                streak = streak.await(),
                goal = goal.await(),
            )
        }
    }
}
