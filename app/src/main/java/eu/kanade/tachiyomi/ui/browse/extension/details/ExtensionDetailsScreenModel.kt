package eu.kanade.tachiyomi.ui.browse.extension.details

import android.content.Context
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.coroutineScope
import eu.kanade.domain.extension.interactor.ExtensionSourceItem
import eu.kanade.domain.extension.interactor.GetExtensionSources
import eu.kanade.domain.source.interactor.ToggleSource
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.logcat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private const val URL_EXTENSION_COMMITS =
    "https://github.com/tachiyomiorg/tachiyomi-extensions/commits/master"
private const val URL_EXTENSION_BLOB =
    "https://github.com/tachiyomiorg/tachiyomi-extensions/blob/master"

class ExtensionDetailsScreenModel(
    pkgName: String,
    context: Context,
    private val network: NetworkHelper = Injekt.get(),
    private val extensionManager: ExtensionManager = Injekt.get(),
    private val getExtensionSources: GetExtensionSources = Injekt.get(),
    private val toggleSource: ToggleSource = Injekt.get(),
) : StateScreenModel<ExtensionDetailsState>(ExtensionDetailsState()) {

    private val _events: Channel<ExtensionDetailsEvent> = Channel()
    val events: Flow<ExtensionDetailsEvent> = _events.receiveAsFlow()

    init {
        coroutineScope.launch {
            launch {
                extensionManager.installedExtensionsFlow
                    .map { it.firstOrNull { extension -> extension.pkgName == pkgName } }
                    .collectLatest { extension ->
                        if (extension == null) {
                            _events.send(ExtensionDetailsEvent.Uninstalled)
                            return@collectLatest
                        }
                        mutableState.update { state ->
                            state.copy(extension = extension)
                        }
                    }
            }
            launch {
                state.collectLatest { state ->
                    if (state.extension == null) return@collectLatest
                    getExtensionSources.subscribe(state.extension)
                        .map {
                            it.sortedWith(
                                compareBy(
                                    { !it.enabled },
                                    { item ->
                                        item.source.name.takeIf { item.labelAsName }
                                            ?: LocaleHelper.getSourceDisplayName(item.source.lang, context).lowercase()
                                    },
                                ),
                            )
                        }.collectLatest { sources ->
                            mutableState.update {
                                it.copy(
                                    sources = sources,
                                )
                            }
                        }
                }
            }
        }
    }

    fun getChangelogUrl(): String {
        val extension = state.value.extension ?: return ""

        val pkgName = extension.pkgName.substringAfter("eu.kanade.tachiyomi.extension.")
        val pkgFactory = extension.pkgFactory
        if (extension.hasChangelog) {
            return createUrl(URL_EXTENSION_BLOB, pkgName, pkgFactory, "/CHANGELOG.md")
        }

        // Falling back on GitHub commit history because there is no explicit changelog in extension
        return createUrl(URL_EXTENSION_COMMITS, pkgName, pkgFactory)
    }

    fun getReadmeUrl(): String {
        val extension = state.value.extension ?: return ""

        if (!extension.hasReadme) {
            return "https://tachiyomi.org/help/faq/#extensions"
        }

        val pkgName = extension.pkgName.substringAfter("eu.kanade.tachiyomi.extension.")
        val pkgFactory = extension.pkgFactory
        return createUrl(URL_EXTENSION_BLOB, pkgName, pkgFactory, "/README.md")
    }

    fun clearCookies() {
        val extension = state.value.extension ?: return

        val urls = extension.sources
            .filterIsInstance<HttpSource>()
            .map { it.baseUrl }
            .distinct()

        val cleared = urls.sumOf {
            network.cookieManager.remove(it.toHttpUrl())
        }

        logcat { "Cleared $cleared cookies for: ${urls.joinToString()}" }
    }

    fun uninstallExtension() {
        val extension = state.value.extension ?: return
        extensionManager.uninstallExtension(extension.pkgName)
    }

    fun toggleSource(sourceId: Long) {
        toggleSource.await(sourceId)
    }

    fun toggleSources(enable: Boolean) {
        state.value.extension?.sources
            ?.map { it.id }
            ?.let { toggleSource.await(it, enable) }
    }

    private fun createUrl(
        url: String,
        pkgName: String,
        pkgFactory: String?,
        path: String = "",
    ): String {
        return if (!pkgFactory.isNullOrEmpty()) {
            when (path.isEmpty()) {
                true -> "$url/multisrc/src/main/java/eu/kanade/tachiyomi/multisrc/$pkgFactory"
                else -> "$url/multisrc/overrides/$pkgFactory/" + (pkgName.split(".").lastOrNull() ?: "") + path
            }
        } else {
            url + "/src/" + pkgName.replace(".", "/") + path
        }
    }
}

sealed class ExtensionDetailsEvent {
    object Uninstalled : ExtensionDetailsEvent()
}

data class ExtensionDetailsState(
    val extension: Extension.Installed? = null,
    val sources: List<ExtensionSourceItem> = emptyList(),
) {

    val isLoading: Boolean
        get() = sources.isEmpty()
}
