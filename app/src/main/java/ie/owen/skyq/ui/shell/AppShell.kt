package ie.owen.skyq.ui.shell

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import ie.owen.skyq.navigation.NavItem

@Composable
fun AppShell(
    selectedNav: NavItem,
    onNavSelect: (NavItem) -> Unit,
    onPreviewBoundsChanged: (Rect) -> Unit = {},
    isFullscreen: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val panelAlpha by animateFloatAsState(
        targetValue = if (isFullscreen) 0f else 1f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "panelAlpha"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0f to Color(0xFF011799),
                    1f to Color(0xFF0051FB)
                )
            )
    ) {
        val leftGap    = maxWidth * 0.015f
        val panelWidth = maxWidth * 0.23f

        Row(modifier = Modifier.fillMaxSize().drawWithContent {
            drawContent()
            // Drop shadow cast by the panel onto the left gap
            val edgePx   = leftGap.toPx()
            val shadowPx = 5f
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.35f),
                    startX = edgePx - shadowPx,
                    endX   = edgePx
                ),
                topLeft = Offset(edgePx - shadowPx, 0f),
                size    = Size(shadowPx, size.height)
            )
        }) {
            Spacer(modifier = Modifier.width(leftGap))

            Sidebar(
                selectedNav = selectedNav,
                onNavSelect = onNavSelect,
                onPreviewBoundsChanged = onPreviewBoundsChanged,
                modifier = Modifier.width(panelWidth).graphicsLayer { alpha = panelAlpha }
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawWithContent {
                        // Background shine — large soft glow from upper-right
                        val shineCenter = Offset(size.width * 0.92f, -size.height * 0.05f)
                        val shineRadius = size.height * 0.75f
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to Color.White.copy(alpha = 0.10f),
                                1f to Color.Transparent,
                                center = shineCenter,
                                radius = shineRadius
                            ),
                            radius = shineRadius,
                            center = shineCenter
                        )
                        // Lens flare 1 (larger)
                        val f1 = Offset(size.width * 0.93f, size.height * 0.18f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to Color.White.copy(alpha = 0.50f),
                                0.35f to Color.White.copy(alpha = 0.18f),
                                1f to Color.Transparent,
                                center = f1,
                                radius = size.height * 0.06f
                            ),
                            radius = size.height * 0.06f,
                            center = f1
                        )
                        // Lens flare 2 (smaller)
                        val f2 = Offset(size.width * 0.96f, size.height * 0.30f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to Color.White.copy(alpha = 0.40f),
                                0.35f to Color.White.copy(alpha = 0.12f),
                                1f to Color.Transparent,
                                center = f2,
                                radius = size.height * 0.04f
                            ),
                            radius = size.height * 0.04f,
                            center = f2
                        )
                        drawContent()
                        // Panel drop-shadow on content left edge
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colorStops = arrayOf(
                                    0.00f to Color.Black.copy(alpha = 0.37f),
                                    0.25f to Color.Black.copy(alpha = 0.26f),
                                    0.75f to Color.Black.copy(alpha = 0.13f),
                                    1.00f to Color.Black.copy(alpha = 0.11f)
                                ),
                                startX = 0f,
                                endX = 15.dp.toPx()
                            )
                        )
                    },
                content = content
            )
        }
    }
}
