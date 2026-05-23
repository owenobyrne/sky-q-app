package ie.owen.skyq.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import ie.owen.skyq.ui.theme.SkyDarkest
import ie.owen.skyq.ui.theme.SkyTextDim

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen() {
    Box(
        modifier = Modifier.fillMaxSize().background(SkyDarkest),
        contentAlignment = Alignment.Center
    ) {
        Text("Home — coming soon", color = SkyTextDim, fontSize = 18.sp)
    }
}
