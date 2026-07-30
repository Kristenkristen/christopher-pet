package com.cedarstar.christopherpet

enum class PetState(val gifName: String) {
    IDLE("clawd-idle"),
    IDLE_READING("clawd-idle-reading"),
    THINKING("clawd-thinking"),
    TYPING("clawd-typing"),
    SLEEPING("clawd-sleeping"),
    HAPPY("clawd-happy"),
    HEADPHONES("clawd-headphones-groove"),
    BUILDING("clawd-building"),
    NOTIFICATION("clawd-notification"),
    ERROR("clawd-error"),
    CARRYING("clawd-carrying"),
    SWEEPING("clawd-sweeping"),
    CONDUCTING("clawd-conducting"),
    DEBUGGER("clawd-debugger"),
    JUGGLING("clawd-juggling"),
    REACT_ANNOYED("clawd-react-annoyed"),
    REACT_JUMP("clawd-react-double-jump"),
    BUBBLE("clawd-bubble");

    fun gifAssetPath() = "gif/${gifName}.gif"
}

fun String.toPetState(): PetState = when (this) {
    "idle-reading" -> PetState.IDLE_READING
    "thinking"     -> PetState.THINKING
    "typing"       -> PetState.TYPING
    "sleeping"     -> PetState.SLEEPING
    "happy"        -> PetState.HAPPY
    "headphones-groove" -> PetState.HEADPHONES
    "building"     -> PetState.BUILDING
    "notification" -> PetState.NOTIFICATION
    "error"        -> PetState.ERROR
    "carrying"     -> PetState.CARRYING
    "sweeping"     -> PetState.SWEEPING
    "conducting"   -> PetState.CONDUCTING
    "debugger"     -> PetState.DEBUGGER
    "juggling"     -> PetState.JUGGLING
    else           -> PetState.IDLE
}
