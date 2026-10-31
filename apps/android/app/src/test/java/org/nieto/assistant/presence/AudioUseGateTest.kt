package org.nieto.assistant.presence

import org.junit.Assert.*
import org.junit.Test

class AudioUseGateTest {
    @Test fun disposingPlaybackCannotResumeWakeDuringARecording() {
        val gate=AudioUseGate()
        gate.acquire("preview");gate.acquire("recorder")
        gate.release("preview");assertTrue(gate.active)
        gate.release("recorder");assertFalse(gate.active)
    }
    @Test fun repeatedCleanupAndAcquisitionDoNotLeakOrReleaseOtherOwners() {
        val gate=AudioUseGate()
        gate.acquire("recording");gate.acquire("recording")
        gate.release("unrelated");assertTrue(gate.active)
        gate.release("recording");gate.release("recording");assertFalse(gate.active)
    }
}
