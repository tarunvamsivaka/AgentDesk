package com.agentdesk.agent.intent

import kotlinx.coroutines.flow.Flow

/**
 * Universal on-device inference provider contract.
 * Decouples model implementations (Google ML Kit Prompt API, ONNX/LiteRT local SLM, deterministic rules)
 * from the high-level intent classification and response streaming pipeline.
 */
interface OnDeviceInferenceProvider {
    val providerId: String
    suspend fun isAvailable(): Boolean
    suspend fun classifyIntent(prompt: String, prefixKey: String = ""): ClassifiedIntent?
    fun streamResponse(prompt: String, contextChunks: List<String> = emptyList()): Flow<String>
    suspend fun releaseResources()
}
