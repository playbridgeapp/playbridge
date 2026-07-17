package com.playbridge.player.player;

import android.os.Bundle;

oneway interface IRendererCallback {
    void onRendererEvent(in Bundle event);
}
