package com.android.systemui.qs.tiles.impl.networkmode

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.android.systemui.common.networkmode.NetworkModeController
import com.android.systemui.common.networkmode.NetworkModeWidget
import com.android.systemui.qs.panels.ui.compose.infinitegrid.CommonTileDefaults.TileHeight

@Composable
fun QSTileNetworkMode(
    iconOnly: Boolean = false,
    border: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember { NetworkModeController.get(context) }

    NetworkModeWidget(
        controller = controller,
        modifier = Modifier.fillMaxWidth(),
        border = border,
        tileHeight = TileHeight,
        iconOnly = iconOnly,
    )
}
