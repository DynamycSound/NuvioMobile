package com.nuvio.app.core.sync

import com.nuvio.app.desktop.DesktopWindowState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal actual object AppForegroundMonitor {
    actual fun events(): Flow<AppVisibility> =
        DesktopWindowState.visible
            .map { visible -> if (visible) AppVisibility.Foreground else AppVisibility.Background }
            .distinctUntilChanged()
}
