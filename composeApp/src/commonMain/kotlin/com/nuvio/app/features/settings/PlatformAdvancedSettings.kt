package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope

/** Platform-only sections at the end of Settings > Advanced (desktop: controller and power). */
internal expect fun LazyListScope.platformAdvancedSettingsContent(isTablet: Boolean)
