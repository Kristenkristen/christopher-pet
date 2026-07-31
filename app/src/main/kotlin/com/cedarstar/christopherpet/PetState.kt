package com.cedarstar.christopherpet

enum class PetState(val gifName: String) {
    // Base states
    IDLE("clawd-idle"),
    IDLE_READING("clawd-idle-reading"),
    IDLE_LOOK("clawd-idle-reading"),          // SVG bad → use idle-reading
    IDLE_BUBBLE("clawd-bubble"),              // SVG bad → use bubble (good animated GIF)
    IDLE_DOZE("clawd-sleeping"),             // SVG bad → use sleeping
    IDLE_COLLAPSE("clawd-sleeping"),         // SVG bad → use sleeping
    IDLE_YAWN("clawd-idle"),                 // SVG bad → use idle
    IDLE_LOW_BATTERY("clawd-sleeping"),      // SVG bad → use sleeping

    // Activity / working — actual asset filenames omit "working-" prefix
    THINKING("clawd-thinking"),
    TYPING("clawd-typing"),
    TYPING_BOSS("clawd-typing"),
    SWEEPING("clawd-sweeping"),
    CONDUCTING("clawd-conducting"),
    DEBUGGER("clawd-debugger"),
    JUGGLING("clawd-juggling"),
    BUILDING("clawd-building"),
    BUILDING_BOXES("clawd-working-building-boxes"),  // this one keeps the prefix
    CARRYING("clawd-carrying"),
    ULTRATHINK("clawd-thinking"),
    WIZARD("clawd-thinking"),

    // Status
    SLEEPING("clawd-sleeping"),
    COLLAPSE_SLEEP("clawd-sleeping"),        // SVG bad → use sleeping
    HAPPY("clawd-happy"),
    HEADPHONES("clawd-headphones-groove"),
    NOTIFICATION("clawd-notification"),
    WAKE("clawd-happy"),                     // SVG bad → use happy

    // Reactions
    ERROR("clawd-error"),
    DIZZY("clawd-react-double-jump"),        // SVG bad → use double-jump (energetic)
    AEGYO_SHY("clawd-happy"),               // SVG bad → use happy
    COFFEE_HAND("clawd-idle"),              // SVG bad → use idle
    REACT_ANNOYED("clawd-react-annoyed"),
    REACT_DOUBLE("clawd-react-annoyed"),    // SVG bad → use annoyed
    REACT_DOUBLE_JUMP("clawd-react-double-jump"),
    REACT_DRAG("clawd-react-annoyed"),      // SVG bad → use annoyed
    REACT_LEFT("clawd-react-annoyed"),      // SVG bad → use annoyed
    REACT_RIGHT("clawd-react-annoyed"),     // SVG bad → use annoyed

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
