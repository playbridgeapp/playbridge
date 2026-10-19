package com.playbridge.sender.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPermissionPolicyTest {
    @Test fun disabledNotificationsPromptAtARelevantForegroundMoment() {
        assertTrue(shouldPromptForNotifications(false, true, true, false, false))
    }

    @Test fun enabledNotificationsNeverPrompt() {
        assertFalse(shouldPromptForNotifications(true, true, true, false, false))
    }

    @Test fun noPlaybackOrCastOpportunityDoesNotPrompt() {
        assertFalse(shouldPromptForNotifications(false, false, true, false, false))
    }

    @Test fun backgroundEventsWaitForAForegroundActivity() {
        assertFalse(shouldPromptForNotifications(false, true, false, false, false))
        assertTrue(shouldPromptForNotifications(false, true, true, false, false))
    }

    @Test fun aPromptAlreadyShownInThisProcessSuppressesEveryLaterEvent() {
        assertFalse(shouldPromptForNotifications(false, true, true, true, false))
    }

    @Test fun aSystemPermissionRequestMustFinishBeforePrompting() {
        assertFalse(shouldPromptForNotifications(false, true, true, false, true))
        assertTrue(shouldPromptForNotifications(false, true, true, false, false))
    }

    @Test fun firstRequestOrDismissedDialogCanRequestRuntimePermission() {
        for (api in listOf(33, 36, 37)) {
            assertEquals(NotificationPermissionAction.REQUEST_PERMISSION,
                notificationPermissionAction(api, false, false, false))
        }
    }

    @Test fun deniedButRequestablePermissionCanBeRequestedAgain() {
        assertEquals(NotificationPermissionAction.REQUEST_PERMISSION,
            notificationPermissionAction(33, false, true, true))
    }

    @Test fun permanentDenialGoesToSettings() {
        assertEquals(NotificationPermissionAction.OPEN_SETTINGS,
            notificationPermissionAction(33, false, true, false))
    }

    @Test fun systemSettingsDisablementOnOlderAndroidGoesToSettings() {
        for (api in listOf(26, 30, 32)) {
            assertEquals(NotificationPermissionAction.OPEN_SETTINGS,
                notificationPermissionAction(api, false, false, false))
        }
    }

    @Test fun settingsDisablementWithGrantedPermissionDoesNotRerequestIt() {
        assertEquals(NotificationPermissionAction.OPEN_SETTINGS,
            notificationPermissionAction(36, true, true, false))
    }
}
