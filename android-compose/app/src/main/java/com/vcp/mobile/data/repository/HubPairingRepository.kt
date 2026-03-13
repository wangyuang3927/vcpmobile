package com.vcp.mobile.data.repository

import com.vcp.mobile.data.network.HubApiClient
import com.vcp.mobile.data.network.HubPairingExchangeRequest
import com.vcp.mobile.data.network.HubPairingExchangeResult

interface HubPairingRepository {
    suspend fun exchangePairing(request: HubPairingExchangeRequest): HubPairingExchangeResult
}

class HubPairingRepositoryImpl(
    private val hubApiClient: HubApiClient
) : HubPairingRepository {
    override suspend fun exchangePairing(request: HubPairingExchangeRequest): HubPairingExchangeResult {
        return hubApiClient.exchangePairing(request)
    }
}
