package io.github.aksulightning.flyby.vm

enum class VmState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

data class VmStatus(
    val state: VmState = VmState.STOPPED,
    val error: String? = null,
    val exitCode: Int? = null,
)
