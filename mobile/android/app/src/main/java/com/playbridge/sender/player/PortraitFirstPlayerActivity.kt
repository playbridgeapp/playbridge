package com.playbridge.sender.player

/**
 * [PlayerActivity] declared with `fullUser` orientation in the manifest, for "Landscape videos
 * open in: Portrait first". Choosing it at launch (see [PlayerActivity.activityClass]) avoids
 * opening in landscape and then turning.
 */
open class PortraitFirstPlayerActivity : PlayerActivity()
