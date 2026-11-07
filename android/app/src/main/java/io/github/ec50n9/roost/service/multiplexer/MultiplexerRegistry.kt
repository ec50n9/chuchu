package io.github.ec50n9.roost.service.multiplexer

import io.github.ec50n9.roost.model.MultiplexerType

object MultiplexerRegistry {
    val defaultType: MultiplexerType = MultiplexerType.Tmux

    fun forType(type: MultiplexerType): Multiplexer? = when (type) {
        MultiplexerType.Tmux -> TmuxMultiplexer
        MultiplexerType.Zmx -> ZmxMultiplexer
        MultiplexerType.Zellij -> null
    }
}
