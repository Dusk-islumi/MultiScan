package com.example.multiscan.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * 给页面级 ViewModel 一个**随页面存活**的作用域（替代 navigation-compose 的 per-destination owner）。
 *
 * 为什么需要：项目不用 navigation-compose，`viewModel()` 默认作用域是 Activity 的 ViewModelStore，
 * 页面退栈后 ViewModel 不会被清掉（会累积，且 ScanViewModel 持有的 ScanController/相机资源不会释放）。
 * 这里为每个页面组合创建独立 [ViewModelStore]，页面离开组合时 clear() → 触发 ViewModel.onCleared()。
 *
 * 实现说明：这里不用 `androidx.lifecycle.viewmodel.compose.viewModel` / `initializer`（两者都要求
 * reified 类型参数，而本辅助函数对调用方保持普通泛型），改用非 reified 的 [ViewModelProvider] +
 * [ViewModelProvider.Factory]；每个页面 owner 内只会有一个 VM，类别键固定用 [ViewModel]。
 *
 * 注意：Activity 已声明 configChanges（portrait 固定），配置变更不会重建 Activity/组合，
 * 因此扫描页的 ScanController 不会被意外重建。
 */
@Composable
fun <VM : ViewModel> screenViewModel(createViewModel: () -> VM): VM {
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) {
        onDispose { owner.viewModelStore.clear() }
    }
    val factory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = createViewModel() as T
        }
    }
    @Suppress("UNCHECKED_CAST")
    return ViewModelProvider(owner, factory)[ViewModel::class.java] as VM
}
