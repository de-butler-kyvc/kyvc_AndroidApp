package com.example.kyvc_androidapp.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.kyvc_androidapp.R

val PretendardFontFamily = FontFamily(
    Font(R.font.pretendard_regular, FontWeight.Normal),
    Font(R.font.pretendard_medium, FontWeight.Medium),
    Font(R.font.pretendard_semibold, FontWeight.SemiBold),
    Font(R.font.pretendard_bold, FontWeight.Bold),
    Font(R.font.pretendard_extrabold, FontWeight.ExtraBold)
)

private fun TextStyle.withPretendard(): TextStyle = copy(fontFamily = PretendardFontFamily)

// Set of Material typography styles to start with
private val BaseTypography = Typography()

val Typography = BaseTypography.copy(
    displayLarge = BaseTypography.displayLarge.withPretendard(),
    displayMedium = BaseTypography.displayMedium.withPretendard(),
    displaySmall = BaseTypography.displaySmall.withPretendard(),
    headlineLarge = BaseTypography.headlineLarge.withPretendard(),
    headlineMedium = BaseTypography.headlineMedium.withPretendard(),
    headlineSmall = BaseTypography.headlineSmall.withPretendard(),
    titleLarge = BaseTypography.titleLarge.withPretendard(),
    titleMedium = BaseTypography.titleMedium.withPretendard(),
    titleSmall = BaseTypography.titleSmall.withPretendard(),
    bodyLarge = TextStyle(
        fontFamily = PretendardFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = BaseTypography.bodyMedium.withPretendard(),
    bodySmall = BaseTypography.bodySmall.withPretendard(),
    labelLarge = BaseTypography.labelLarge.withPretendard(),
    labelMedium = BaseTypography.labelMedium.withPretendard(),
    labelSmall = BaseTypography.labelSmall.withPretendard()
    /* Other default text styles to override
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
    */
)
