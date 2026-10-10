package com.akashrajeev.voicebeam.core

/** Latest technical backend state is retained separately from the bounded event ring. */
class BackendStatus {
    @Volatile var value:String="not_started"
        private set
    fun set(state:String) { value=state.take(1024).replace('\n',' ').replace('\r',' ') }
    fun clear() { value="not_started" }
}
