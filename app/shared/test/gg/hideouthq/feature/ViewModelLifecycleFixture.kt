package gg.hideouthq.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
internal fun withViewModels(test: suspend TestScope.(ViewModels) -> Unit) = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val viewModels = ViewModels()
    try {
        test(viewModels)
    } finally {
        viewModels.store.clear()
        runCurrent()
        Dispatchers.resetMain()
    }
}

internal class ViewModels {
    val store = ViewModelStore()
    fun <T : ViewModel> keep(viewModel: T): T = viewModel.also { store.put(it.toString(), it) }
}
