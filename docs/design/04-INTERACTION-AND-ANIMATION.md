# Interaction & Animation Specification
**Document 04 • FinTrack Mobile Design System**
*Motion Tokens • Spring Physics • Number Tickers • Haptic Tactility*

---

## 1. Motion Principles & Philosophy

FinTrack’s animation system is designed around **physical weight, institutional reliability, and functional reassurance**. In a personal finance app, animation must never feel frivolous, slow, or gamified. Every motion serves one of three purposes:
1. **Express Spatial Relationships:** Clarify where an account, sheet, or split came from.
2. **Confirm Mathematical State:** Visually anchor value mutations (e.g. balance recalculations) with smooth rolling digits.
3. **Prevent Accidental Data Loss:** Provide physical friction and tactile warnings when altering protected reserves or discarding dirty forms.

---

## 2. Motion Tokens & Jetpack Compose Spring Physics

FinTrack uses physics-based spring animations (`spring()`) rather than linear or ease curves wherever interactive gestures occur.

### 2.1 Spring Specification Tokens

```kotlin
// FinTrack Spring Motion Tokens
object FinTrackMotion {
    // Crisp, immediate response for buttons, icons, and toggles
    val FastSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy, // 1.0f (Critically Damped)
        stiffness = Spring.StiffnessMedium // ~1500f
    )

    // Smooth, organic response for cards, sheets, and expanding panels
    val ResponsiveSpring = spring<Float>(
        dampingRatio = 0.85f, // Slight physical settle without cartoon bounce
        stiffness = Spring.StiffnessMediumLow // ~400f
    )

    // Weighty response for full-screen dialogs and hero metrics
    val GentleSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessLow // ~200f
    )

    // Standard durations for tween/crossfade transitions
    const val DurationFast = 150
    const val DurationNormal = 250
    const val DurationMedium = 350
    const val DurationLong = 450
}
```

---

## 3. Navigation Transitions & Predictive Back

### 3.1 Primary Tab Switching
When transitioning between the 5 primary tabs (`Home`, `Activity`, `Plan`, `Goals`, `Insights`), the navigation host executes a **Crossfade with subtle scale**:
- **Entering Screen:** Alpha $0.0 \to 1.0$, Scale $0.98 \to 1.0$ ($200\,\text{ms}$, `FastOutSlowInEasing`).
- **Exiting Screen:** Alpha $1.0 \to 0.0$ ($150\,\text{ms}$, `FastOutLinearInEasing`).

### 3.2 Master-Detail Push/Pop (Shared Z-Axis)
When opening an Account Detail or Goal Detail screen:
- **Push Forward:**
  - Enter: Scale $0.92 \to 1.0$, Alpha $0.0 \to 1.0$ ($300\,\text{ms}$).
  - Exit Parent: Scale $1.0 \to 1.05$, Alpha $1.0 \to 0.0$ ($250\,\text{ms}$).
- **Predictive Back Gesture (Android 14+):**
  - As the user swipes from the left edge, the current screen scales down smoothly to $0.90$ with a corner radius interpolating from $0\,\text{dp} \to 24\,\text{dp}$, revealing the parent list beneath.
  - If the user releases past the $35\%$ screen width threshold, the pop completes with `FastSpring`; if abandoned, the screen springs back to full width.

---

## 4. Component Micro-Interactions

### 4.1 Monetary Value Ticker (`AnimatedContent`)
Whenever a displayed financial figure changes (e.g. after adding an expense or filtering by date), the whole rupee amount animates vertically like a physical mechanical counter:
- **Value Increases (e.g. Income Added):** Digits roll smoothly **downwards** into place.
- **Value Decreases (e.g. Expense Posted):** Digits roll smoothly **upwards** into place.

```kotlin
@Composable
fun AnimatedMoneyCounter(
    targetAmount: Money,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.displaySmall
) {
    AnimatedContent(
        targetState = targetAmount,
        transitionSpec = {
            if (targetState.minor > initialState.minor) {
                // Incoming value is greater: slide in from top
                slideInVertically { height -> -height } + fadeIn() togetherWith
                        slideOutVertically { height -> height } + fadeOut()
            } else {
                // Incoming value is smaller: slide in from bottom
                slideInVertically { height -> height } + fadeIn() togetherWith
                        slideOutVertically { height -> -height } + fadeOut()
            }.using(SizeTransform(clip = false))
        },
        label = "MoneyCounterAnimation"
    ) { amount ->
        Text(
            text = formatInrExact(amount),
            style = textStyle.copy(
                fontFeatureSettings = "tnum",
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
```

### 4.2 Allocation & Progress Bar Interpolation
Budget envelope and goal progress indicators animate whenever their underlying ratio changes:
- `val animatedProgress by animateFloatAsState(targetValue = ratio, animationSpec = FinTrackMotion.ResponsiveSpring)`
- Linear progress tracks feature rounded end caps ($4\,\text{dp}$) and a subtle shine gleam upon reaching $100\%$ completion.

### 4.3 Swipe-to-Action Gestures
In the `Activity` transaction list and `Accounts` list:
- Swiping right-to-left reveals contextual action pills (`[Archive]` for accounts, `[Mark Cleared]` for transactions).
- Cross-threshold haptic feedback: As the drag offset crosses $72\,\text{dp}$, a `GESTURE_THRESHOLD_ACTIVATE` haptic tick triggers, and the background icon snaps from muted grey to active emerald.

---

## 5. Modal Bottom Sheet Physics

The `ModalBottomSheet` component in FinTrack features two defined resting positions:
1. **Half-Sheet ($60\%$ height):** Used for initial transaction entry and quick filters.
2. **Full-Sheet ($95\%$ height):** Snaps up smoothly when keyboard is focused or category split table expands.
- **Velocity threshold:** Flinging down with velocity $>1200\,\text{dp/s}$ dismisses the sheet immediately (provided the form is clean).
- **Scrim fade:** Background scrim transitions from `#00000000` to `#66000000` ($40\%$ black opacity) synchronized with the sheet offset.
