package gg.hideouthq.core.data

import gg.hideouthq.core.model.NewsPost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Deadlock's news on Steam, newest first; empty until any has been read. */
interface NewsRepository {
    val posts: StateFlow<List<NewsPost>>

    object None : NewsRepository {
        override val posts: StateFlow<List<NewsPost>> = MutableStateFlow(emptyList<NewsPost>()).asStateFlow()
    }
}
