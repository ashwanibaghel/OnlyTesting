# Android Jetpack Compose Implementation Guide
**Document 05 • FinTrack Mobile Design System**
*Theme Architecture • Reusable Composables • State Hoisting • Preview Providers*

---

## 1. Theme Architecture & Custom Design Tokens

FinTrack extends Material 3 with dedicated financial tokens (`SafeToSpend`, `Reserved`, `Discrepancy`, `Deficit`) using Compose's `CompositionLocalProvider`.

### 1.1 Custom Financial Color Tokens Specification

```kotlin
package com.fintrack.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class FinTrackFinancialColors(
    val safeToSpend: Color,
    val onSafeToSpend: Color,
    val safeToSpendContainer: Color,
    val onSafeToSpendContainer: Color,
    val reserved: Color,
    val onReserved: Color,
    val reservedContainer: Color,
    val onReservedContainer: Color,
    val discrepancy: Color,
    val onDiscrepancy: Color,
    val discrepancyContainer: Color,
    val onDiscrepancyContainer: Color,
    val deficit: Color,
    val onDeficit: Color,
    val deficitContainer: Color,
    val onDeficitContainer: Color,
    val pendingBadge: Color,
    val onPendingBadge: Color
)

val LocalFinancialColors = staticCompositionLocalOf<FinTrackFinancialColors> {
    error("No FinTrackFinancialColors provided")
}
```

---

## 2. Production-Ready Reusable Compose Components

### 2.1 `MoneyText` / `MoneyDisplay` (Indian Rupee Formatting Standard)
Enforces exact integer decimal rendering, Indian number grouping (Lakhs/Crores), tabular numerals, and optically scaled currency glyphs.

```kotlin
package com.fintrack.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.fintrack.engine.Money
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Formats a Money instance according to the Indian Rupee grouping standard (e.g. ₹1,25,000.00).
 */
fun formatInrExact(money: Money): String {
    val symbols = DecimalFormatSymbols(Locale("en", "IN"))
    val decimal = money.toDecimal()
    val pattern = "##,##,##,##0.00"
    val formatter = DecimalFormat(pattern, symbols)
    return formatter.format(decimal)
}

@Composable
fun MoneyText(
    money: Money,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    showSign: Boolean = false,
    dimPaise: Boolean = true
) {
    val isNegative = money.minor < 0L
    val absoluteMoney = if (isNegative) -money else money
    val formattedNumber = formatInrExact(absoluteMoney)
    val parts = formattedNumber.split(".")
    val rupeesPart = parts[0]
    val paisePart = if (parts.size > 1) ".${parts[1]}" else ".00"

    val signPrefix = when {
        isNegative -> "-"
        showSign && money.minor > 0L -> "+"
        else -> ""
    }

    val currencyFontSize = (style.fontSize.value * 0.78f).sp
    val paiseFontSize = (style.fontSize.value * 0.75f).sp

    val annotated = buildAnnotatedString {
        if (signPrefix.isNotEmpty()) {
            append(signPrefix)
        }
        // Optically scaled ₹ symbol
        withStyle(
            SpanStyle(
                fontSize = currencyFontSize,
                baselineShift = BaselineShift(0.08f)
            )
        ) {
            append("₹ ")
        }
        // Major Rupee part
        append(rupeesPart)
        // Scaled & dimmed paise part
        if (dimPaise) {
            withStyle(
                SpanStyle(
                    fontSize = paiseFontSize,
                    color = color.copy(alpha = 0.8f),
                    baselineShift = BaselineShift(0.05f)
                )
            ) {
                append(paisePart)
            }
        } else {
            append(paisePart)
        }
    }

    Text(
        text = annotated,
        modifier = modifier,
        style = style.copy(
            fontFeatureSettings = "tnum",
            fontWeight = FontWeight.SemiBold
        ),
        color = color
    )
}
```

---

### 2.2 `SafeToSpendCard` (Hero Dashboard Card)
Displays unencumbered purchasing power or surfaces deficit risk with full mathematical breakdown.

```kotlin
package com.fintrack.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fintrack.engine.SafeToSpend
import com.fintrack.engine.Money
import com.fintrack.ui.theme.LocalFinancialColors

@Composable
fun SafeToSpendCard(
    safeToSpend: SafeToSpend,
    liquid: Money,
    reserved: Money,
    uncovered: Money,
    horizonLabel: String,
    onExplainClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val financialColors = LocalFinancialColors.current
    val hasDeficit = safeToSpend.deficit.minor > 0L

    val containerColor = if (hasDeficit) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        financialColors.safeToSpendContainer
    }

    val contentColor = if (hasDeficit) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        financialColors.onSafeToSpendContainer
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (hasDeficit) "SAFE-TO-SPEND DEFICIT" else "SAFE-TO-SPEND ($horizonLabel)",
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor.copy(alpha = 0.85f)
                )
                IconButton(onClick = onExplainClick, modifier = Modifier.size(24.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Safe-to-Spend explanation",
                        tint = contentColor
                    )
                }
            }

            // Primary Figure
            val displayMoney = if (hasDeficit) -safeToSpend.deficit else safeToSpend.available
            MoneyText(
                money = displayMoney,
                style = MaterialTheme.typography.displayMedium,
                color = contentColor
            )

            Text(
                text = if (hasDeficit) {
                    "⚠️ Commitments exceed liquid cash. Rebalance envelopes or goals."
                } else {
                    "Ready for unencumbered spending today"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor.copy(alpha = 0.9f)
            )

            HorizontalDivider(
                color = contentColor.copy(alpha = 0.2f),
                thickness = 1.dp
            )

            // Breakdown Footnote
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Liquid Balance", style = MaterialTheme.typography.labelSmall)
                    MoneyText(liquid, style = MaterialTheme.typography.titleSmall)
                }
                Column {
                    Text("Reserved Funds", style = MaterialTheme.typography.labelSmall)
                    MoneyText(-reserved, style = MaterialTheme.typography.titleSmall, color = financialColors.reserved)
                }
                Column {
                    Text("Uncovered Bills", style = MaterialTheme.typography.labelSmall)
                    MoneyText(-uncovered, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
```

---

### 2.3 `TransactionListItem` (Audit-Grade Ledger Row)

```kotlin
package com.fintrack.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fintrack.engine.Money

@Composable
fun TransactionListItem(
    title: String,
    subtitle: String,
    categoryName: String,
    amount: Money,
    isExpense: Boolean,
    isPending: Boolean,
    categoryIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Category Icon Circle
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = categoryIcon,
                contentDescription = categoryName,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Title and Subtitle Column
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Text(
                text = "$subtitle • $categoryName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        // Amount & Status Column
        Column(horizontalAlignment = Alignment.End) {
            MoneyText(
                money = amount,
                showSign = true,
                style = MaterialTheme.typography.titleMedium,
                color = if (isExpense) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
            )
            if (isPending) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "PENDING",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}
```

---

## 3. UI State Hoisting & Design Preview Fixtures

Every screen composable receives an immutable `UiState` representation and emits event lambdas.

### 3.1 Design Preview Parameter Provider
To eliminate fake production claims while enabling instant Android Studio Previews, use explicit `@PreviewParameter` providers labeled as fixtures:

```kotlin
package com.fintrack.ui.preview

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.fintrack.engine.CurrencySpec
import com.fintrack.engine.Money
import com.fintrack.engine.SafeToSpend

val InrCurrency = CurrencySpec("INR", 2)

data class DashboardUiFixture(
    val liquidTotal: Money,
    val reservedTotal: Money,
    val uncoveredBills: Money,
    val safeToSpend: SafeToSpend,
    val isDeficit: Boolean
)

class DashboardPreviewProvider : PreviewParameterProvider<DashboardUiFixture> {
    override val values = sequenceOf(
        // Healthy State Fixture
        DashboardUiFixture(
            liquidTotal = Money(12000000L, InrCurrency), // ₹1,20,000.00
            reservedTotal = Money(6500000L, InrCurrency), // ₹65,000.00
            uncoveredBills = Money(1250000L, InrCurrency), // ₹12,500.00
            safeToSpend = SafeToSpend(Money(4250000L, InrCurrency), Money(0L, InrCurrency)),
            isDeficit = false
        ),
        // Deficit Warning State Fixture
        DashboardUiFixture(
            liquidTotal = Money(4000000L, InrCurrency), // ₹40,000.00
            reservedTotal = Money(3500000L, InrCurrency), // ₹35,000.00
            uncoveredBills = Money(1500000L, InrCurrency), // ₹15,000.00
            safeToSpend = SafeToSpend(Money(0L, InrCurrency), Money(1000000L, InrCurrency)), // -₹10,000
            isDeficit = true
        )
    )
}
```
