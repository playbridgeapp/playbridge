package com.playbridge.sender.player

/**
 * Which player screen to open. Portrait first applies unless a website asked for an orientation.
 * Keep playing needs the player in its own task, so Back can leave it playing behind the browser;
 * Pause and Picture-in-picture use the InApp screens, which share the app's task (Back to PiP
 * lands in the app, and Recents shows one PlayBridge entry).
 */
internal fun playerActivityClass(
    landscapeOpen: PhoneLandscapeOpen,
    leaveAction: PhoneLeaveAction,
    websiteOrientation: PhonePlayerOpeningOrientation? = null,
): Class<out PlayerActivity> {
    val portraitFirst = websiteOrientation == null && landscapeOpen == PhoneLandscapeOpen.PORTRAIT_FIRST
    val ownTask = leaveAction == PhoneLeaveAction.KEEP_PLAYING
    return when {
        portraitFirst && ownTask -> PortraitFirstPlayerActivity::class.java
        portraitFirst -> InAppPortraitFirstPlayerActivity::class.java
        ownTask -> PlayerActivity::class.java
        else -> InAppPlayerActivity::class.java
    }
}
