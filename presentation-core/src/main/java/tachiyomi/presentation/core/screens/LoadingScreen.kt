package tachiyomi.presentation.core.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LoadingScreen(
    modifier: Modifier = Modifier,
    message: String? = null,
) {
    val darkBackground = Color(0xFF0F0B13)
    val surfaceDark = Color(0xFF18121D)
    val goldPrimary = Color(0xFFFFB800)
    val lavenderPrimary = Color(0xFFA78BFA)
    val strokeSubtle = BorderStroke(1.dp, Color(0x33A78BFA))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(darkBackground),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = surfaceDark,
            border = strokeSubtle,
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(
                    color = goldPrimary,
                    trackColor = lavenderPrimary.copy(alpha = 0.2f),
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(44.dp),
                )

                if (!message.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            textDirection = TextDirection.Content,
                        ),
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
