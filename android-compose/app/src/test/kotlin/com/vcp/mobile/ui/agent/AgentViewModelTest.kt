package com.vcp.mobile.ui.agent

import com.vcp.mobile.data.network.HubAgentConfig
import com.vcp.mobile.data.network.HubAgentGroupConfig
import com.vcp.mobile.data.network.HubAgentIdentityConfig
import com.vcp.mobile.data.network.HubAgentMemoryConfig
import com.vcp.mobile.data.network.HubAgentModelConfig
import com.vcp.mobile.data.network.HubAgentMutationResult
import com.vcp.mobile.data.network.HubAgentPromptConfig
import com.vcp.mobile.data.network.HubAgentRequestConfig
import com.vcp.mobile.data.network.HubAgentToolConfig
import com.vcp.mobile.data.network.HubAgentToolPermission
import com.vcp.mobile.data.network.HubBridgeError
import com.vcp.mobile.data.network.HubBridgeFailure
import com.vcp.mobile.data.repository.HubAgentRepository
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
class AgentViewModelTest {

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
    fun `create flow saves trimmed fields and appends agent to list`() = runTest(dispatcher) {
        val repository = FakeHubAgentRepository()
        val viewModel = AgentViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startCreateFlow()
        viewModel.onNameChanged("  Planner  ")
        viewModel.onAvatarUriChanged("  🤖  ")
        viewModel.onSystemPromptChanged("  You plan tasks.  ")
        viewModel.saveAgent()
        dispatcher.scheduler.advanceUntilIdle()

        val saved = repository.created.single()
        assertEquals("Planner", saved.identity.name)
        assertEquals("🤖", saved.identity.avatarUri)
        assertEquals("You plan tasks.", saved.prompt.systemPrompt)
        assertNull(viewModel.state.value.form)
        assertEquals(1, viewModel.state.value.agents.size)
        assertEquals("Agent 已创建", viewModel.state.value.statusMessage)
    }

    @Test
    fun `edit flow preserves non form fields while updating edited values`() = runTest(dispatcher) {
        val existing = sampleAgent(
            id = "agent-1",
            name = "Writer",
            avatarUri = "https://example.com/old.png",
            prompt = "Old prompt",
        ).copy(
            model = HubAgentModelConfig(modelId = "gpt-5"),
            request = HubAgentRequestConfig(temperature = 0.7f),
            tools = HubAgentToolConfig(
                enableLocalTools = true,
                overrides = listOf(HubAgentToolPermission(toolId = "calendar", enabled = false)),
            ),
            group = HubAgentGroupConfig(aliases = listOf("scribe")),
        )
        val repository = FakeHubAgentRepository(agents = mutableListOf(existing))
        val viewModel = AgentViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startEditFlow("agent-1")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.onNameChanged("Writer Pro")
        viewModel.onAvatarUriChanged("🪶")
        viewModel.onSystemPromptChanged("Updated prompt")
        viewModel.saveAgent()
        dispatcher.scheduler.advanceUntilIdle()

        val updated = repository.updated.single().second
        assertEquals("Writer Pro", updated.identity.name)
        assertEquals("🪶", updated.identity.avatarUri)
        assertEquals("Updated prompt", updated.prompt.systemPrompt)
        assertEquals("gpt-5", updated.model.modelId)
        assertEquals(0.7f, updated.request.temperature)
        assertEquals(listOf("scribe"), updated.group.aliases)
        assertEquals("Agent 已更新", viewModel.state.value.statusMessage)
        assertNull(viewModel.state.value.form)
    }

    @Test
    fun `save validates required fields locally before hitting repository`() = runTest(dispatcher) {
        val repository = FakeHubAgentRepository()
        val viewModel = AgentViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startCreateFlow()
        viewModel.onNameChanged(" ")
        viewModel.onSystemPromptChanged(" ")
        viewModel.saveAgent()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.created.isEmpty())
        val form = viewModel.state.value.form
        assertNotNull(form)
        assertEquals("名称不能为空", form?.nameError)
        assertEquals("System prompt 不能为空", form?.promptError)
        assertFalse(viewModel.state.value.isSaving)
    }

    @Test
    fun `mutation failure keeps editor open and surfaces bridge message`() = runTest(dispatcher) {
        val repository = FakeHubAgentRepository(createResult = HubAgentMutationResult.Failure(
            HubBridgeFailure(
                statusCode = 422,
                statusMessage = "Unprocessable",
                error = HubBridgeError(
                    kind = "validation_error",
                    code = "agent_config_invalid",
                    message = "prompt.system_prompt must be non-empty",
                    retriable = false,
                ),
            )
        ))
        val viewModel = AgentViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.startCreateFlow()
        viewModel.onNameChanged("Planner")
        viewModel.onSystemPromptChanged("Prompt")
        viewModel.saveAgent()
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(viewModel.state.value.form)
        assertEquals("prompt.system_prompt must be non-empty", viewModel.state.value.errorMessage)
    }

    private fun sampleAgent(
        id: String,
        name: String,
        avatarUri: String? = null,
        prompt: String,
    ): HubAgentConfig {
        return HubAgentConfig(
            id = id,
            identity = HubAgentIdentityConfig(
                name = name,
                avatarUri = avatarUri,
            ),
            prompt = HubAgentPromptConfig(
                systemPrompt = prompt,
            ),
            model = HubAgentModelConfig(),
            request = HubAgentRequestConfig(),
            memory = HubAgentMemoryConfig(),
            tools = HubAgentToolConfig(),
            group = HubAgentGroupConfig(),
            updatedAt = "2026-03-13T00:00:00Z",
        )
    }
}

private class FakeHubAgentRepository(
    private val agents: MutableList<HubAgentConfig> = mutableListOf(),
    private val createResult: HubAgentMutationResult? = null,
    private val updateResult: HubAgentMutationResult? = null,
) : HubAgentRepository {
    val created = mutableListOf<HubAgentConfig>()
    val updated = mutableListOf<Pair<String, HubAgentConfig>>()

    override suspend fun listAgents(): List<HubAgentConfig> = agents.toList()

    override suspend fun getAgent(agentId: String): HubAgentConfig {
        return agents.first { it.id == agentId }
    }

    override suspend fun createAgent(agent: HubAgentConfig): HubAgentMutationResult {
        created += agent
        val result = createResult ?: HubAgentMutationResult.Success(agent, 201)
        if (result is HubAgentMutationResult.Success) {
            agents.removeAll { it.id == result.agent.id }
            agents += result.agent
        }
        return result
    }

    override suspend fun updateAgent(agentId: String, agent: HubAgentConfig): HubAgentMutationResult {
        updated += agentId to agent
        val result = updateResult ?: HubAgentMutationResult.Success(agent, 200)
        if (result is HubAgentMutationResult.Success) {
            agents.removeAll { it.id == result.agent.id }
            agents += result.agent
        }
        return result
    }

    override suspend fun deleteAgent(agentId: String): HubAgentMutationResult {
        error("not needed in tests")
    }
}
