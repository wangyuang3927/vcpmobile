package com.vcp.mobile.ui.pairing

import com.vcp.mobile.data.network.HubPairingExchangeError
import com.vcp.mobile.data.network.HubPairingExchangeFailureResponse
import com.vcp.mobile.data.network.HubPairingExchangeRequest
import com.vcp.mobile.data.network.HubPairingExchangeResult
import com.vcp.mobile.data.network.HubPairingExchangeSuccessResponse
import com.vcp.mobile.data.network.HubPairingMobileToken
import com.vcp.mobile.data.network.HubPairingResumeAnchor
import com.vcp.mobile.data.network.HubPairingTrustedDevice
import com.vcp.mobile.data.repository.HubPairingRepository
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
class PairingViewModelTest {

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
    fun `submitPairing exposes success state with trusted device and resume anchor`() = runTest(dispatcher) {
        val repository = FakeHubPairingRepository(
            result = HubPairingExchangeResult.Success(
                HubPairingExchangeSuccessResponse(
                    pairingSessionId = "pairing-session-1",
                    namespace = "workspace-alpha",
                    status = "paired",
                    mobileToken = HubPairingMobileToken(
                        accessToken = "mobile-token-1234567890",
                        tokenType = "bearer",
                        expiresAt = "2026-03-13T12:00:00Z",
                    ),
                    trustedDevice = HubPairingTrustedDevice(
                        trustedDeviceId = "trusted-device-1",
                        deviceName = "Pixel 9",
                        devicePlatform = "android",
                    ),
                    resumeAnchor = HubPairingResumeAnchor(
                        anchor = "resume-anchor-1234567890",
                        expiresAt = "2026-03-20T12:00:00Z",
                    ),
                )
            )
        )
        val viewModel = PairingViewModel(repository)

        viewModel.onPairingSessionIdChanged(" pairing-session-1 ")
        viewModel.onNamespaceChanged(" workspace-alpha ")
        viewModel.onBootstrapTokenChanged(" bootstrap-secret ")
        viewModel.onDeviceNameChanged(" Pixel 9 ")
        viewModel.submitPairing()
        dispatcher.scheduler.advanceUntilIdle()

        val request = repository.requests.single()
        assertEquals("pairing-session-1", request.pairingSessionId)
        assertEquals("workspace-alpha", request.namespace)
        assertEquals("bootstrap-secret", request.bootstrapToken)
        assertEquals("Pixel 9", request.deviceName)
        assertNotNull(viewModel.state.value.successState)
        assertNull(viewModel.state.value.failureState)
        assertEquals("trusted-device-1", viewModel.state.value.successState?.trustedDeviceId)
        assertTrue(viewModel.state.value.statusMessage.contains("配对完成"))
    }

    @Test
    fun `submitPairing surfaces explicit failure and keeps retry path visible`() = runTest(dispatcher) {
        val repository = FakeHubPairingRepository(
            result = HubPairingExchangeResult.Failure(
                HubPairingExchangeFailureResponse(
                    pairingSessionId = "pairing-session-1",
                    namespace = "workspace-alpha",
                    status = "rejected",
                    error = HubPairingExchangeError(
                        code = "pairing_exchange_not_ready",
                        message = "pairing exchange contract is frozen, but token issuance is implemented in TES-43",
                        retriable = true,
                    ),
                )
            )
        )
        val viewModel = PairingViewModel(repository)

        viewModel.onPairingSessionIdChanged("pairing-session-1")
        viewModel.onNamespaceChanged("workspace-alpha")
        viewModel.onBootstrapTokenChanged("bootstrap-secret")
        viewModel.retryPairing()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.state.value.successState)
        val failure = viewModel.state.value.failureState
        assertNotNull(failure)
        assertEquals("pairing_exchange_not_ready", failure?.code)
        assertTrue(failure?.retriable == true)
        assertTrue(viewModel.state.value.statusMessage.contains("可直接重试"))
    }

    @Test
    fun `submitPairing validates required scanned payload before hitting repository`() = runTest(dispatcher) {
        val repository = FakeHubPairingRepository(
            result = HubPairingExchangeResult.Failure(
                HubPairingExchangeFailureResponse(
                    status = "rejected",
                    error = HubPairingExchangeError(
                        code = "unused",
                        message = "unused",
                        retriable = false,
                    )
                )
            )
        )
        val viewModel = PairingViewModel(repository)

        viewModel.onDeviceNameChanged(" ")
        viewModel.onDevicePublicKeyChanged(" ")
        viewModel.submitPairing()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(repository.requests.isEmpty())
        val form = viewModel.state.value.form
        assertEquals("扫码结果缺少会话 ID", form.pairingSessionIdError)
        assertEquals("扫码结果缺少 namespace", form.namespaceError)
        assertEquals("扫码结果缺少 bootstrap token", form.bootstrapTokenError)
        assertEquals("设备名称不能为空", form.deviceNameError)
        assertEquals("设备公钥不能为空", form.devicePublicKeyError)
        assertFalse(viewModel.state.value.isSubmitting)
    }
}

private class FakeHubPairingRepository(
    private val result: HubPairingExchangeResult,
) : HubPairingRepository {
    val requests = mutableListOf<HubPairingExchangeRequest>()

    override suspend fun exchangePairing(request: HubPairingExchangeRequest): HubPairingExchangeResult {
        requests += request
        return result
    }
}
