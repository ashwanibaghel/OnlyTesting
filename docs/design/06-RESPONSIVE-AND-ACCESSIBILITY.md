# Responsive Design & Accessibility (a11y)
**Document 06 • FinTrack Mobile Design System**
*Screen Adaptation • Dynamic Font Scaling (200%) • TalkBack Semantics • WCAG AAA*

---

## 1. Responsive Screen Breakpoint Strategy

Android devices present an immense spectrum of screen widths, from compact budget devices ($320\text{--}360\,\text{dp}$) to mainstream flagships ($390\text{--}430\,\text{dp}$), foldable devices, and tablets ($600\,\text{dp}+$):

| Window Size Class | Width Range | Common Devices | FinTrack Layout Policy |
| :--- | :--- | :--- | :--- |
| **Compact (< 360dp)** | $320\text{--}359\,\text{dp}$ | Galaxy A0x, Older 4.5"–5" phones | Single-column; stack metric tiles vertically; reduce horizontal screen padding to $12\,\text{dp}$; compact card headers. |
| **Standard (360–599dp)** | $360\text{--}599\,\text{dp}$ | Pixel 7/8/9, Galaxy S23/S24 | Single-column baseline; $16\,\text{dp}$ margins; horizontal account carousel; side-by-side metric tiles. |
| **Expanded (>= 600dp)** | $600\,\text{dp}+$ | Foldable inner display, Pixel Tablet | Two-column split pane: Master list on left ($40\%$), Detail/Editor on right ($60\%$); dual-card dashboard grid. |

---

## 2. Dynamic Font Scaling (85% to 200%)

In financial management, **clipping or ellipsizing numbers is a catastrophic design failure**. Truncating `₹1,00,000.00` to `₹1,00...` leads to misinformed spending and loss of user trust.

### 2.1 Rules for Non-Breaking Financial Text
1. **Zero Fixed-Height Text Containers:** Never apply `.height(X.dp)` to rows or cards containing monetary values or descriptions. Always use `.wrapContentHeight()`.
2. **Auto-Wrapping Breakdown Rows:** In cards displaying triplets (e.g. `Liquid | Reserved | Uncovered`), if the user enables $>130\%$ system font scale, the row transforms dynamically into a vertical column:
   ```kotlin
   val fontScale = LocalDensity.current.fontScale
   if (fontScale > 1.3f) {
       // Stack vertically to prevent horizontal truncation
       Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { ... }
   } else {
       // Standard 3-column row
       Row(horizontalArrangement = Arrangement.SpaceBetween) { ... }
   }
   ```
3. **Number Prioritization:** In list rows where space is constrained, the payee name and category truncate (`maxLines = 1, overflow = TextOverflow.Ellipsis`), but the **amount is strictly guaranteed full width and zero truncation**.

---

## 3. Accessibility & TalkBack Semantics

Every financial element in FinTrack provides rich semantic information to screen readers.

### 3.1 Currency Pronunciation Semantics
Standard screen readers frequently stumble on `₹` or parse commas inconsistently. FinTrack uses `Modifier.semantics` to emit spoken phonetic descriptions:

```kotlin
@Composable
fun AccessibleMoneyDisplay(
    money: Money,
    label: String,
    modifier: Modifier = Modifier
) {
    val spokenAmount = formatSpokenRupees(money) // "Forty two thousand five hundred rupees"
    
    Box(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$label: $spokenAmount"
        }
    ) {
        MoneyText(money = money)
    }
}
```

### 3.2 Heading Hierarchy & Accessibility Traversal
- Major screen titles are annotated with `Modifier.semantics { heading() }`, allowing TalkBack users to jump directly across sections using the navigation rotor.
- Form fields explicitly link their error messages to the input field using `error = "..."` semantics.

---

## 4. Touch Target & Contrast Standards

### 4.1 Minimum Interactive Touch Targets
- All buttons, icon buttons, filter chips, and interactive list items have a **minimum interactive target of $48 \times 48\,\text{dp}$** (enforced by Compose `minimumInteractiveComponentSize()`).
- Dense dropdowns or table action icons expand their touch target through outer padding even when the visible glyph is $24\,\text{dp}$.

### 4.2 WCAG 2.1 Contrast Ratios
- **Body & Numerical Text:** Minimum $7.0:1$ (exceeding WCAG AAA) against their respective surface containers in both Light and Dark modes.
- **Large Headlines & Metrics ($\ge 24\,\text{sp}$):** Minimum $4.5:1$ (WCAG AA).
- **Secondary Metadata & Inactive Icons:** Minimum $4.5:1$ against surface.
