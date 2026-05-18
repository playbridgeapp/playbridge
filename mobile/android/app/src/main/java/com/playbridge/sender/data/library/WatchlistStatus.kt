package com.playbridge.sender.data.library

enum class WatchlistStatus(val value: String, val displayName: String) {
    PLAN_TO_WATCH("plan_to_watch", "Plan to Watch"),
    WATCHING("watching", "Watching"),
    COMPLETED("completed", "Completed"),
    ON_HOLD("on_hold", "On Hold"),
    DROPPED("dropped", "Dropped");

    companion object {
        fun from(value: String): WatchlistStatus =
            entries.find { it.value == value } ?: PLAN_TO_WATCH
    }
}
