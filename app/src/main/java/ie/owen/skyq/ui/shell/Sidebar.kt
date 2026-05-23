package ie.owen.skyq.ui.shell

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import ie.owen.skyq.BuildConfig
import ie.owen.skyq.R
import ie.owen.skyq.navigation.NavItem
import ie.owen.skyq.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private const val BORDER_WIDTH_PX = 13f

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun Sidebar(
    selectedNav: NavItem,
    onNavSelect: (NavItem) -> Unit,
    onPreviewBoundsChanged: (Rect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val config = LocalConfiguration.current
    val panelFont = config.screenHeightDp * 0.025f
    val leftPad = (config.screenWidthDp * 0.025f).dp

    Column(
        modifier = modifier
            .fillMaxHeight()
            .drawWithContent {
                // Panel body
                drawRect(color = SkyPanelBody)

                // Draw children (including video preview) before border
                drawContent()

                val borderPx    = BORDER_WIDTH_PX

                // Left-edge glass border — navy base tint + shadow, derived from Sky Q pixel samples
                val leftStart = 0f
                val leftEnd   = borderPx
                val leftSize  = Size(borderPx, size.height)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color(0xFF0012D3),
                        1f to Color.Transparent,
                        startX = leftStart,
                        endX   = leftEnd
                    ),
                    topLeft = Offset(leftStart, 0f),
                    size    = leftSize
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.39f),
                            0.21f to Color.Black.copy(alpha = 0.39f),
                            0.50f to Color.Black.copy(alpha = 0.28f),
                            1.00f to Color.Black.copy(alpha = 0.14f)
                        ),
                        startX = leftStart,
                        endX   = leftEnd
                    ),
                    topLeft = Offset(leftStart, 0f),
                    size    = leftSize
                )

                // Right-edge glass border drawn over content so it overlays the video
                val borderStart = size.width - borderPx
                val borderEnd   = size.width
                val rightSize   = Size(borderPx, size.height)

                // White specular — peaks at strip 3, gone by strip 7
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White.copy(alpha = 0.084f),
                            0.23f to Color.White.copy(alpha = 0.098f),
                            0.46f to Color.White.copy(alpha = 0.005f),
                            0.54f to Color.Transparent
                        ),
                        startX = borderStart,
                        endX   = borderEnd
                    ),
                    topLeft = Offset(borderStart, 0f),
                    size    = rightSize
                )

                // Black shadow — starts mid-border, peaks at right edge
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Transparent,
                            0.46f to Color.Transparent,
                            0.54f to Color.Black.copy(alpha = 0.05f),
                            1.00f to Color.Black.copy(alpha = 0.20f)
                        ),
                        startX = borderStart,
                        endX   = borderEnd
                    ),
                    topLeft = Offset(borderStart, 0f),
                    size    = rightSize
                )

                // Vertical "light source" spotlight — ramps up and down around 50% panel height,
                // brightening the border speculars as if a light is shining on the panel at that point
                val spotBrush = Brush.verticalGradient(
                    0.00f to Color.Transparent,
                    0.38f to Color.Transparent,
                    0.50f to Color.White.copy(alpha = 0.65f),
                    0.62f to Color.Transparent,
                    1.00f to Color.Transparent
                )
                drawRect(brush = spotBrush, topLeft = Offset(leftStart, 0f), size = leftSize)
                drawRect(brush = spotBrush, topLeft = Offset(borderStart, 0f), size = rightSize)
            }
    ) {
        // Logo + clock — 14% of sidebar height
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.14f)
                .padding(start = leftPad, end = 20.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.schoolhouse_logo),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(0.75f).offset { IntOffset(-5, 0) },
                contentScale = ContentScale.FillWidth,
                alignment = Alignment.CenterStart
            )
            Spacer(Modifier.height(6.dp))
            Clock(fontSize = panelFont)
        }

        // Placeholder for the video overlay — VideoOverlay is positioned here via onPreviewBoundsChanged
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(SkyDarkest)
                .onGloballyPositioned { onPreviewBoundsChanged(it.boundsInRoot()) }
        )

        Spacer(Modifier.height(24.dp))

        // Nav items
        NavItem.values().forEach { item ->
            SidebarNavItem(
                label = item.label,
                selected = item == selectedNav,
                fontSize = panelFont,
                leftPad = leftPad,
                onClick = { onNavSelect(item) }
            )
        }

        Spacer(Modifier.weight(1f))

        Text(
            text = "build ${BuildConfig.BUILD_NUMBER}",
            color = SkyTextDim.copy(alpha = 0.35f),
            fontSize = 10.sp,
            fontFamily = InterFontFamily,
            fontWeight = FontWeight.Light,
            modifier = Modifier.padding(start = leftPad, bottom = 10.dp)
        )
    }
}

@Composable
fun SidebarBorderOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val borderPx  = BORDER_WIDTH_PX
        val leftStart = 0f
        val leftSize  = Size(borderPx, size.height)

        drawRect(
            brush = Brush.horizontalGradient(
                0f to Color(0xFF0012D3),
                1f to Color.Transparent,
                startX = leftStart,
                endX   = borderPx
            ),
            topLeft = Offset(leftStart, 0f),
            size    = leftSize
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Black.copy(alpha = 0.39f),
                    0.21f to Color.Black.copy(alpha = 0.39f),
                    0.50f to Color.Black.copy(alpha = 0.28f),
                    1.00f to Color.Black.copy(alpha = 0.14f)
                ),
                startX = leftStart,
                endX   = borderPx
            ),
            topLeft = Offset(leftStart, 0f),
            size    = leftSize
        )

        val borderStart = size.width - borderPx
        val rightSize   = Size(borderPx, size.height)

        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color.White.copy(alpha = 0.084f),
                    0.23f to Color.White.copy(alpha = 0.098f),
                    0.46f to Color.White.copy(alpha = 0.005f),
                    0.54f to Color.Transparent
                ),
                startX = borderStart,
                endX   = size.width
            ),
            topLeft = Offset(borderStart, 0f),
            size    = rightSize
        )
        drawRect(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    0.46f to Color.Transparent,
                    0.54f to Color.Black.copy(alpha = 0.05f),
                    1.00f to Color.Black.copy(alpha = 0.20f)
                ),
                startX = borderStart,
                endX   = size.width
            ),
            topLeft = Offset(borderStart, 0f),
            size    = rightSize
        )

        val spotBrush = Brush.verticalGradient(
            0.00f to Color.Transparent,
            0.38f to Color.Transparent,
            0.50f to Color.White.copy(alpha = 0.65f),
            0.62f to Color.Transparent,
            1.00f to Color.Transparent
        )
        drawRect(brush = spotBrush, topLeft = Offset(leftStart, 0f), size = leftSize)
        drawRect(brush = spotBrush, topLeft = Offset(borderStart, 0f), size = rightSize)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SidebarNavItem(
    label: String,
    selected: Boolean,
    fontSize: Float,
    leftPad: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit
) {
    val margin = (LocalConfiguration.current.screenWidthDp * 0.015f).dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .selectable(selected = selected, onClick = onClick)
            .focusable()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = margin)
                .background(if (selected) Color(0xFF011C80) else Color.Transparent),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = label,
                color = SkyText,
                fontSize = fontSize.sp,
                fontFamily = InterFontFamily,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Light,
                modifier = Modifier.padding(start = leftPad, end = 12.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Clock(fontSize: Float) {
    var time by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("h:mma", Locale.getDefault())
        while (true) {
            time = fmt.format(Date()).lowercase().replace(".", "")
            kotlinx.coroutines.delay(30_000)
        }
    }
    Text(
        text = time,
        color = SkyTextDim,
        fontSize = fontSize.sp,
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Light
    )
}
