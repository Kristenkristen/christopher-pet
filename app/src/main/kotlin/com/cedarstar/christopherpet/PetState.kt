package com.cedarstar.christopherpet

enum class PetState(val gifName: String) {
    // Base states
    IDLE("clawd-idle"),
    IDLE_READING("clawd-idle-reading"),
    IDLE_LOOK("clawd-idle-look"),
    IDLE_BUBBLE("clawd-idle-bubble"),
    IDLE_DOZE("clawd-idle-doze"),
    IDLE_COLLAPSE("clawd-idle-collapse"),
    IDLE_YAWN("clawd-idle-yawn"),
    IDLE_LOW_BATTERY("clawd-idle-low-battery"),

    // Activity / working
    THINKING("clawd-working-thinking"),
    TYPING("clawd-working-typing"),
    TYPING_BOSS("clawd-working-typing-boss"),
    SWEEPING("clawd-working-sweeping"),
    CONDUCTING("clawd-working-conducting"),
    DEBUGGER("clawd-working-debugger"),
    JUGGLING("clawd-working-juggling"),
    BUILDING("clawd-working-building"),
    BUILDING_BOXES("clawd-working-building-boxes"),
    CARRYING("clawd-working-carrying"),
    ULTRATHINK("clawd-working-ultrathink"),
    WIZARD("clawd-working-wizard"),

    // Status
    SLEEPING("clawd-sleeping"),
    COLLAPSE_SLEEP("clawd-collapse-sleep"),
    HAPPY("clawd-happy"),
    HEADPHONES("clawd-headphones-groove"),
    NOTIFICATION("clawd-notification"),
    WAKE("clawd-wake"),

    // Reactions
    ERROR("clawd-error"),
    DIZZY("clawd-dizzy"),
    AEGYO_SHY("clawd-aegyo-shy"),
    COFFEE_HAND("clawd-coffee-hand"),
    REACT_ANNOYED("clawd-react-annoyed"),
    REACT_DOUBLE("clawd-react-double"),
    REACT_DOUBLE_JUMP("clawd-react-double-jump"),
    REACT_DRAG("clawd-react-drag"),
    REACT_LEFT("clawd-react-left"),
    REACT_RIGHT("clawd-react-right"),

    // Motion
    CRABWALK("clawd-mini-crabwalk"),
    BUBBLE("clawd-bubble");

    fun gifAssetPath() = "gif/${gifName}.gif"
}

fun String.toPetState(): PetState = when (this) {
    "idle"                  -> PetState.IDLE
    "idle-reading"          -> PetState.IDLE_READING
    "idle-look"             -> PetState.IDLE_LOOK
    "idle-bubble"           -> PetState.IDLE_BUBBLE
    "idle-doze"             -> PetState.IDLE_DOZE
    "idle-collapse"         -> PetState.IDLE_COLLAPSE
    "idle-yawn"             -> PetState.IDLE_YAWN
    "idle-low-battery"      -> PetState.IDLE_LOW_BATTERY
    "thinking"              -> PetState.THINKING
    "typing"                -> PetState.TYPING
    "working-typing"        -> PetState.TYPING
    "working-typing-boss"   -> PetState.TYPING_BOSS
    "working-sweeping"      -> PetState.SWEEPING
    "working-conducting"    -> PetState.CONDUCTING
    "working-debugger"      -> PetState.DEBUGGER
    "working-juggling"      -> PetState.JUGGLING
    "working-building"      -> PetState.BUILDING
    "working-building-boxes"-> PetState.BUILDING_BOXES
    "working-carrying"      -> PetState.CARRYING
    "working-ultrathink"    -> PetState.ULTRATHINK
    "working-wizard"        -> PetState.WIZARD
    "sleeping"              -> PetState.SLEEPING
    "collapse-sleep"        -> PetState.COLLAPSE_SLEEP
    "happy"                 -> PetState.HAPPY
    "headphones-groove"     -> PetState.HEADPHONES
    "notification"          -> PetState.NOTIFICATION
    "wake"                  -> PetState.WAKE
    "error"                 -> PetState.ERROR
    "dizzy"                 -> PetState.DIZZY
    "aegyo-shy"             -> PetState.AEGYO_SHY
    "coffee-hand"           -> PetState.COFFEE_HAND
    "react-annoyed"         -> PetState.REACT_ANNOYED
    "react-double"          -> PetState.REACT_DOUBLE
    "react-double-jump"     -> PetState.REACT_DOUBLE_JUMP
    "react-drag"            -> PetState.REACT_DRAG
    "react-left"            -> PetState.REACT_LEFT
    "react-right"           -> PetState.REACT_RIGHT
    else                    -> PetState.IDLE
}
