package io.github.univers629.millet.ui.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.univers629.millet.ui.model.AppIconSource
import io.github.univers629.millet.ui.util.AppIconCache
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AppIconImage(
    modifier: Modifier = Modifier,
    iconSource: AppIconSource?,
    label: String,
) {
    if (iconSource == null) {
        PlaceHolderBox(modifier)
        return
    }
    val density = LocalDensity.current
    val context = LocalContext.current
    val targetSizePx = with(density) { 48.dp.roundToPx() }

    Box(modifier = modifier) {
        val initiallyCached = remember(iconSource, targetSizePx) {
            AppIconCache.getCached(iconSource, targetSizePx) != null
        }

        var appBitmap by remember(iconSource, targetSizePx) {
            mutableStateOf(AppIconCache.getCached(iconSource, targetSizePx))
        }

        if (!initiallyCached) {
            LaunchedEffect(iconSource, targetSizePx) {
                appBitmap = AppIconCache.loadIcon(context, iconSource, targetSizePx)
            }
        }

        val icon = appBitmap
        if (initiallyCached && icon != null) {
            val imageBitmap = remember(icon) { icon.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
                contentDescription = label,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Crossfade(
                targetState = icon,
                animationSpec = tween(durationMillis = 150),
                label = "IconFade"
            ) { bitmap ->
                if (bitmap != null) {
                    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = label,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    PlaceHolderBox(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun PlaceHolderBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer)
    )
}
