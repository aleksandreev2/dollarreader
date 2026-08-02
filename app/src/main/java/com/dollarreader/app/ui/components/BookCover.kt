package com.dollarreader.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val coverGradients = listOf(
    listOf(Color(0xFF24133F), Color(0xFF7135C9), Color(0xFFA75CFF)),
    listOf(Color(0xFF111B38), Color(0xFF2F4F9B), Color(0xFF7B68D8)),
    listOf(Color(0xFF172038), Color(0xFF355083), Color(0xFF706BB3)),
)

@Composable
fun BookCover(
    title: String,
    seed: Int,
    modifier: Modifier = Modifier,
) {
    val colors = coverGradients[seed.mod(coverGradients.size)]
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(colors)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxSize(0.66f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.24f), Color.Transparent),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.86f),
            )
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
            )
            Text(
                text = "DOLLARREADER",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                fontSize = 7.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}
