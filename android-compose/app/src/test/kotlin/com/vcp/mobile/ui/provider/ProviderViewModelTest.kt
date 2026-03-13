package com.vcp.mobile.ui.provider

import com.vcp.mobile.data.network.HubBridgeError
import com.vcp.mobile.data.network.HubBridgeFailure
import com.vcp.mobile.data.network.HubProviderAdapterKind
import com.vcp.mobile.data.network.HubProviderAuthConfig
import com.vcp.mobile.data.network.HubProviderAuthType
import com.vcp.mobile.data.network.HubProviderConfig
import com.vcp.mobile.data.network.HubProviderHeader
import com.vcp.mobile.data.network.HubProviderModelCatalog
import com.vcp.mobile.data.network.HubProviderModelCatalogEntry
import com.vcp.mobile.data.network.HubProviderMutationResult
import com.vcp.mobile.data.repository.HubProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `create flow trims fields and saves bridge payload`() = runTest(dispatcher) {
        val repository = FakeHubProviderRepository()
        val viewModel = ProviderViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startCreateFlow()
        viewModel.onDisplayNameChanged("  OpenAI  ")
        viewModel.onAvatarUriChanged("  https://example.com/avatar.png  ")
        viewModel.onBaseUrlChanged("  https://api.example.com/v1  ")
        viewModel.onAuthTypeChanged(HubProviderAuthType.BEARER_TOKEN)
        viewModel.onAuthTokenChanged("  secret-token  ")
        viewModel.onModelLinesChanged("  gpt-4.1-mini | GPT-4.1 mini  ")
        viewModel.onDefaultModelIdChanged("  gpt-4.1-mini  ")
        viewModel.saveProvider()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = repository.created.single()
        assertEquals("OpenAI", saved.displayName)
        assertEquals("https://example.com/avatar.png", saved.avatarUri)
        assertEquals("https://api.example.com/v1", saved.baseUrl)
        assertEquals("secret-token", saved.auth.token)
        assertEquals("gpt-4.1-mini", saved.modelCatalog.defaultModel)
        assertEquals("gpt-4.1-mini", saved.modelCatalog.entries.single().modelId)
        assertNull(viewModel.state.value.form)
        assertEquals("Provider 已创建", viewModel.state.value.statusMessage)
    }

    @Test
    fun `edit flow preserves advanced fields while updating basic inputs`() = runTest(dispatcher) {
        val existing = sampleProvider(
            localId = "provider-1",
            displayName = "Writer",
            baseUrl = "https://old.example.com/v1",
        ).copy(
            customHeaders = listOf(HubProviderHeader("X-Tenant", "mobile")),
            referenceAliases = listOf("legacy-openai"),
        )
        val repository = FakeHubProviderRepository(providers = mutableListOf(existing))
        val viewModel = ProviderViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startEditFlow("provider-1")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onDisplayNameChanged("Writer Pro")
        viewModel.onBaseUrlChanged("https://new.example.com/v1")
        viewModel.onAuthTokenChanged("new-secret")
        viewModel.onModelLinesChanged("claude-3-7-sonnet | Claude 3.7 Sonnet")
        viewModel.onDefaultModelIdChanged("claude-3-7-sonnet")
        viewModel.saveProvider()
        dispatcher.scheduler.advanceUntilIdle()

        val updated = repository.updated.single().second
        assertEquals("Writer Pro", updated.displayName)
        assertEquals("https://new.example.com/v1", updated.baseUrl)
        assertEquals("new-secret", updated.auth.token)
        assertEquals("X-Tenant", updated.customHeaders.single().name)
        assertEquals(listOf("legacy-openai"), updated.referenceAliases)
        assertEquals("Provider 已更新", viewModel.state.value.statusMessage)
        assertNull(viewModel.state.value.form)
    }

    @Test
    fun `save validates required fields locally before hitting repository`() = runTest(dispatcher) {
        val repository = FakeHubProviderRepository()
        val viewModel = ProviderViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startCreateFlow()
        viewModel.onDisplayNameChanged(" ")
        viewModel.onBaseUrlChanged("example.com")
        viewModel.onAuthTypeChanged(HubProviderAuthType.API_KEY)
        viewModel.onAuthHeaderNameChanged(" ")
        viewModel.onAuthValueChanged(" ")
        viewModel.onModelLinesChanged(" ")
        viewModel.saveProvider()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.created.isEmpty())
        val form = viewModel.state.value.form
        assertNotNull(form)
        assertEquals("名称不能为空", form?.displayNameError)
        assertEquals("Base URL 必须以 http:// 或 https:// 开头", form?.baseUrlError)
        assertEquals("Header 名称不能为空", form?.authHeaderNameError)
        assertEquals("API Key 不能为空", form?.authValueError)
        assertEquals("至少填写一个模型，每行格式为 model_id 或 model_id | 展示名", form?.modelLinesError)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `delete provider removes it from list and closes editor`() = runTest(dispatcher) {
        val existing = sampleProvider(
            localId = "provider-1",
            displayName = "Delete Me",
            baseUrl = "https://delete.example.com/v1",
        )
        val repository = FakeHubProviderRepository(providers = mutableListOf(existing))
        val viewModel = ProviderViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startEditFlow("provider-1")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.deleteProvider()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("provider-1"), repository.deleted)
        assertTrue(viewModel.state.value.providers.isEmpty())
        assertNull(viewModel.state.value.form)
        assertEquals("Provider 已删除", viewModel.state.value.statusMessage)
    }

    @Test
    fun `mutation failure keeps editor open and surfaces bridge message`() = runTest(dispatcher) {
        val repository = FakeHubProviderRepository(
            createResult = HubProviderMutationResult.Failure(
                HubBridgeFailure(
                    statusCode = 422,
                    statusMessage = "Unprocessable",
                    error = HubBridgeError(
                        kind = "validation_error",
                        code = "provider_config_invalid",
                        message = "provider display_name must not be empty",
                        retriable = false,
                    ),
                )
            )
        )
        val viewModel = ProviderViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startCreateFlow()
        viewModel.onDisplayNameChanged("OpenAI")
        viewModel.onBaseUrlChanged("https://api.example.com/v1")
        viewModel.onAuthTypeChanged(HubProviderAuthType.BEARER_TOKEN)
        viewModel.onAuthTokenChanged("secret")
        viewModel.onModelLinesChanged("gpt-4.1-mini")
        viewModel.saveProvider()
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.state.value.form)
        assertEquals("provider display_name must not be empty", viewModel.state.value.errorMessage)
    }

    private fun sampleProvider(
        localId: String,
        displayName: String,
        baseUrl: String,
    ): HubProviderConfig {
        return HubProviderConfig(
            localId = localId,
            adapterKind = HubProviderAdapterKind.OPENAI_COMPATIBLE,
            displayName = displayName,
            baseUrl = baseUrl,
            auth = HubProviderAuthConfig(
                type = HubProviderAuthType.BEARER_TOKEN,
                hasStoredSecret = true,
            ),
            modelCatalog = HubProviderModelCatalog(
                defaultModel = "gpt-4.1-mini",
                entries = listOf(
                    HubProviderModelCatalogEntry(
                        modelId = "gpt-4.1-mini",
                        displayName = "GPT-4.1 mini",
                    )
                ),
            ),
            updatedAt = "2026-03-13T00:00:00Z",
        )
    }
}

private class FakeHubProviderRepository(
    private val providers: MutableList<HubProviderConfig> = mutableListOf(),
    private val createResult: HubProviderMutationResult? = null,
    private val updateResult: HubProviderMutationResult? = null,
) : HubProviderRepository {
    val created = mutableListOf<HubProviderConfig>()
    val updated = mutableListOf<Pair<String, HubProviderConfig>>()
    val deleted = mutableListOf<String>()

    override suspend fun listProviders(): List<HubProviderConfig> = providers.toList()

    override suspend fun getProvider(providerLocalId: String): HubProviderConfig {
        return providers.first { it.localId == providerLocalId }
    }

    override suspend fun createProvider(provider: HubProviderConfig): HubProviderMutationResult {
        created += provider
        val saved = provider.copy(
            localId = provider.localId.ifBlank { "provider_local_created" },
            updatedAt = "2026-03-13T01:00:00Z",
        )
        val result = createResult ?: HubProviderMutationResult.Success(saved, 201)
        if (result is HubProviderMutationResult.Success) {
            providers.removeAll { it.localId == result.provider.localId }
            providers += result.provider
        }
        return result
    }

    override suspend fun updateProvider(
        providerLocalId: String,
        provider: HubProviderConfig,
    ): HubProviderMutationResult {
        updated += providerLocalId to provider
        val result = updateResult ?: HubProviderMutationResult.Success(
            provider.copy(updatedAt = "2026-03-13T02:00:00Z"),
            200,
        )
        if (result is HubProviderMutationResult.Success) {
            providers.removeAll { it.localId == result.provider.localId }
            providers += result.provider
        }
        return result
    }

    override suspend fun deleteProvider(providerLocalId: String): HubProviderMutationResult {
        deleted += providerLocalId
        val removed = providers.first { it.localId == providerLocalId }
        providers.removeAll { it.localId == providerLocalId }
        return HubProviderMutationResult.Success(removed, 200)
    }
}
