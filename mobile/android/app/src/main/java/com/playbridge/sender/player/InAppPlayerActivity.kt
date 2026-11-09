package com.playbridge.sender.player

/**
 * [PlayerActivity] in the app's own task, used unless "When you leave the player" is Keep playing.
 * Back to picture-in-picture leaves the browser underneath, and Recents shows one PlayBridge entry.
 * For Keep playing, [PlayerActivity] keeps its own task so Back can send it behind the browser
 * while it keeps playing.
 */
class InAppPlayerActivity : PlayerActivity()

/** [PortraitFirstPlayerActivity] in the app's own task; see [InAppPlayerActivity]. */
class InAppPortraitFirstPlayerActivity : PortraitFirstPlayerActivity()
