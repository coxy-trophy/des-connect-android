/*
 * SPDX-FileCopyrightText: 2024 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */

package org.kde.kdeconnect.ui.compose

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.kde.kdeconnect_tp.R

val DesDisplayFont = FontFamily(
    Font(R.font.chakra_petch_medium, FontWeight.Normal),
    Font(R.font.chakra_petch_semibold, FontWeight.Medium),
    Font(R.font.chakra_petch_semibold, FontWeight.SemiBold),
    Font(R.font.chakra_petch_semibold, FontWeight.Bold)
)
val DesMonoFont = FontFamily(Font(R.font.jetbrains_mono))

private val desTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = DesDisplayFont),
        displayMedium = base.displayMedium.copy(fontFamily = DesDisplayFont),
        displaySmall = base.displaySmall.copy(fontFamily = DesDisplayFont),
        headlineLarge = base.headlineLarge.copy(fontFamily = DesDisplayFont),
        headlineMedium = base.headlineMedium.copy(fontFamily = DesDisplayFont),
        headlineSmall = base.headlineSmall.copy(fontFamily = DesDisplayFont),
        titleLarge = base.titleLarge.copy(fontFamily = DesDisplayFont),
        titleMedium = base.titleMedium.copy(fontFamily = DesDisplayFont),
        titleSmall = base.titleSmall.copy(fontFamily = DesDisplayFont),
        bodyLarge = base.bodyLarge.copy(fontFamily = DesDisplayFont),
        bodyMedium = base.bodyMedium.copy(fontFamily = DesDisplayFont),
        bodySmall = base.bodySmall.copy(fontFamily = DesDisplayFont),
        labelLarge = base.labelLarge.copy(fontFamily = DesDisplayFont),
        labelMedium = base.labelMedium.copy(fontFamily = DesDisplayFont),
        labelSmall = base.labelSmall.copy(fontFamily = DesDisplayFont)
    )
}

@Suppress("UNUSED_PARAMETER") // Preserve the upstream call-site API.
@Composable
fun KdeTheme(context: Context, content: @Composable () -> Unit) {
    // Resource qualifiers resolve the same palette used by the AppCompat host.
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    val colorScheme = base.copy(
        primary = colorResource(R.color.des_accent),
        onPrimary = colorResource(R.color.des_on_accent),
        primaryContainer = colorResource(R.color.des_accent_container),
        onPrimaryContainer = colorResource(R.color.des_text),
        secondary = colorResource(R.color.des_accent),
        onSecondary = colorResource(R.color.des_on_accent),
        secondaryContainer = colorResource(R.color.des_track),
        onSecondaryContainer = colorResource(R.color.des_text),
        tertiary = colorResource(R.color.des_accent),
        onTertiary = colorResource(R.color.des_on_accent),
        tertiaryContainer = colorResource(R.color.des_accent_container),
        onTertiaryContainer = colorResource(R.color.des_text),
        background = colorResource(R.color.des_ground),
        onBackground = colorResource(R.color.des_text),
        surface = colorResource(R.color.des_panel),
        onSurface = colorResource(R.color.des_text),
        surfaceVariant = colorResource(R.color.des_track),
        onSurfaceVariant = colorResource(R.color.des_secondary),
        surfaceTint = colorResource(R.color.des_accent),
        surfaceBright = colorResource(R.color.des_track),
        surfaceDim = colorResource(R.color.des_ground),
        surfaceContainer = colorResource(R.color.des_panel),
        surfaceContainerLowest = colorResource(R.color.des_ground),
        surfaceContainerLow = colorResource(R.color.des_panel),
        surfaceContainerHigh = colorResource(R.color.des_track),
        surfaceContainerHighest = colorResource(R.color.des_track),
        inverseSurface = colorResource(R.color.des_text),
        inverseOnSurface = colorResource(R.color.des_panel),
        inversePrimary = colorResource(R.color.des_on_accent),
        outline = colorResource(R.color.des_outline),
        outlineVariant = colorResource(R.color.des_line),
        error = colorResource(R.color.des_error),
        onError = colorResource(R.color.des_on_error),
        errorContainer = colorResource(R.color.des_error_container),
        onErrorContainer = colorResource(R.color.des_on_error_container)
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = desTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(dimensionResource(R.dimen.des_control_radius)),
            small = RoundedCornerShape(dimensionResource(R.dimen.des_control_radius)),
            medium = RoundedCornerShape(dimensionResource(R.dimen.des_surface_radius)),
            large = RoundedCornerShape(dimensionResource(R.dimen.des_surface_radius)),
            extraLarge = RoundedCornerShape(dimensionResource(R.dimen.des_surface_radius))
        ),
        content = content
    )
}
