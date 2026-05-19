package com.cuee.domain.command

interface KorailCommandParser {
    fun parse(utterance: String): KorailCommand?
}
