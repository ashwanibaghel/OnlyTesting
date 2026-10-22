# Design Tokens & Theming Specification
**Document 01 • FinTrack Mobile Design System**
*Material 3 • Jetpack Compose • Dual Theme Palette • Indian Rupee Typography*

---

## 1. Color System Architecture

FinTrack uses a dual-palette Material 3 design system meticulously tuned for high contrast, emotional calm, and instant financial comprehension. Financial interfaces often overwhelm users with aggressive reds and garish greens; FinTrack employs an editorial, restrained palette anchored by deep forest emeralds, balanced mineral slates, and warm paper surfaces.

### 1.1 Core Semantic Financial Spectrum

Financial figures in FinTrack belong to distinct conceptual tiers. Color is applied with strict semantic discipline:

| Semantic Role | Concept | Light Theme Hex | Dark Theme Hex | Meaning in Context |
| :--- | :--- | :--- | :--- | :--- |
| **Safe-to-Spend / Available** | Purchasing Power | `#11694E` (Forest Emerald) | `#48D597` (Bright Mint) | Unencumbered funds ready for immediate deployment. |
| **Liquid Assets** | Actual Bank / Cash | `#1E5144` (Deep Teal Green) | `#79D3B8` (Soft Spearmint) | Physical, reconciled balances in accounts. |
| **Virtual Reserved** | Buckets / Goals / Emergency | `#344C64` (Deep Slate Blue) | `#97BCE8` (Soft Periwinkle) | Money committed to a purpose, untouched in the bank. |
| **Deficit / Overspend** | Financial Shortfall | `#BA1A1A` (Ruby Crimson) | `#FFB4AB` (Coral Red) | Negative Safe-to-Spend, category overspend, or account overdraft. |
| **Discrepancy / Warning** | Collision / Statement Gap | `#8C6200` (Rich Amber) | `#FFBA28` (Warm Topaz) | Statement discrepancy, priority goal collision, or diversion warning. |
| **Pending / In-Flight** | Uncleared Postings | `#625B71` (Muted Mauve) | `#CBC2DB` (Muted Lavender) | Cheques, authorized card holds, or unposted transfers. |
| **Investment / Long-Term** | Tracked Non-Liquid | `#3E5F8A` (Ocean Cobalt) | `#A2C9FE` (Sky Ice) | Tracked external assets not accessible for daily spending. |

---

### 1.2 Material 3 Theme Color Palettes

#### Light Theme Palette (Paper Clean & Focused)
```kotlin
// Light Theme Palette (WCAG 2.1 AAA Compliant)
val LightPrimary              = Color(0xFF196852) // Forest Emerald
val LightOnPrimary            = Color(0xFFFFFFFF)
val LightPrimaryContainer     = Color(0xFFA5F2D2)
val LightOnPrimaryContainer   = Color(0xFF002117)

val LightSecondary            = Color(0xFF344C64) // Slate Navy
val LightOnSecondary          = Color(0xFFFFFFFF)
val LightSecondaryContainer   = Color(0xFFD6E4F7)
val LightOnSecondaryContainer = Color(0xFF001D32)

val LightTertiary             = Color(0xFF555F71) // Neutral Slate
val LightOnTertiary           = Color(0xFFFFFFFF)
val LightTertiaryContainer    = Color(0xFFD9E3F8)
val LightOnTertiaryContainer  = Color(0xFF121C2B)

val LightError                = Color(0xFFBA1A1A) // Ruby Red
val LightOnError              = Color(0xFFFFFFFF)
val LightErrorContainer       = Color(0xFFFFDAD6)
val LightOnErrorContainer     = Color(0xFF410002)

val LightWarning              = Color(0xFF8C6200) // Rich Amber
val LightOnWarning            = Color(0xFFFFFFFF)
val LightWarningContainer     = Color(0xFFFFDF9E)
val LightOnWarningContainer   = Color(0xFF2C1D00)

val LightBackground           = Color(0xFFF8F9FA) // Soft Snow
val LightOnBackground         = Color(0xFF191C1E) // Deep Onyx
val LightSurface              = Color(0xFFFFFFFF) // Pure Paper
val LightOnSurface            = Color(0xFF191C1E)
val LightSurfaceVariant       = Color(0xFFE1E8E5) // Muted Warm Grey
val LightOnSurfaceVariant     = Color(0xFF404944)
val LightOutline              = Color(0xFF707974)
val LightOutlineVariant       = Color(0xFFC0C9C3)

// Financial Semantic Tokens
val LightSafeToSpend          = Color(0xFF11694E)
val LightSafeToSpendContainer = Color(0xFFD0F8E3)
val LightReserved             = Color(0xFF344C64)
val LightReservedContainer    = Color(0xFFE2ECF8)
val LightDiscrepancy          = Color(0xFF8C6200)
val LightDiscrepancyContainer = Color(0xFFFFE0A4)
```

#### Dark Theme Palette (Deep Obsidian & Luminous Tones)
```kotlin
// Dark Theme Palette (Optimized for OLED & Low-Light Reading)
val DarkPrimary               = Color(0xFF48D597) // Luminous Mint
val DarkOnPrimary             = Color(0xFF003828)
val DarkPrimaryContainer      = Color(0xFF00513B)
val DarkOnPrimaryContainer    = Color(0xFFA5F2D2)

val DarkSecondary             = Color(0xFF97BCE8) // Soft Periwinkle
val DarkOnSecondary           = Color(0xFF003353)
val DarkSecondaryContainer    = Color(0xFF1C354D)
val DarkOnSecondaryContainer  = Color(0xFFD6E4F7)

val DarkTertiary              = Color(0xFFBDC7DC) // Cool Silver
val DarkOnTertiary            = Color(0xFF273141)
val DarkTertiaryContainer     = Color(0xFF3E4758)
val DarkOnTertiaryContainer   = Color(0xFFD9E3F8)

val DarkError                 = Color(0xFFFFB4AB) // Coral Red
val DarkOnError               = Color(0xFF690005)
val DarkErrorContainer        = Color(0xFF93000A)
val DarkOnErrorContainer      = Color(0xFFFFDAD6)

val DarkWarning               = Color(0xFFFFBA28) // Warm Topaz
val DarkOnWarning             = Color(0xFF4A3300)
val DarkWarningContainer      = Color(0xFF6A4A00)
val DarkOnWarningContainer    = Color(0xFFFFDF9E)

val DarkBackground            = Color(0xFF111415) // Deep Obsidian
val DarkOnBackground          = Color(0xFFE1E3E4)
val DarkSurface               = Color(0xFF191C1D) // Elevation 0
val DarkSurface1              = Color(0xFF202526) // Elevation 1 (Cards)
val DarkSurface2              = Color(0xFF262C2E) // Elevation 2 (Sheets)
val DarkSurface3              = Color(0xFF2D3437) // Elevation 3 (Dialogs)
val DarkOnSurface             = Color(0xFFE1E3E4)
val DarkSurfaceVariant        = Color(0xFF404944)
val DarkOnSurfaceVariant      = Color(0xFFBFC9C3)
val DarkOutline               = Color(0xFF8A938E)
val DarkOutlineVariant        = Color(0xFF404944)

// Financial Semantic Tokens
val DarkSafeToSpend           = Color(0xFF48D597)
val DarkSafeToSpendContainer  = Color(0xFF003927)
val DarkReserved              = Color(0xFF97BCE8)
val DarkReservedContainer     = Color(0xFF162D42)
val DarkDiscrepancy           = Color(0xFFFFBA28)
val DarkDiscrepancyContainer  = Color(0xFF473300)
```

---

## 2. Typography System Specification

FinTrack enforces an **uncompromising prohibition against cursive, handwriting, or decorative typefaces**. Typography must project institutional trust, surgical precision, and effortless readability under outdoor sunlight and small screen sizes.

### 2.1 Font Families
- **Primary Typeface:** `Inter` (or Android platform `Roboto` fallback). Neutral, geometric grotesque with tall x-height and clear punctuation marks.
- **Monetary & Numerical Typeface:** `Inter` with OpenType tabular figures (`fontFeatureSettings = "tnum"`) and slash zero (`"zero"`). Tabular numerals guarantee that changing amounts do not shift column alignments or induce layout jumps.

### 2.2 Material 3 Type Scale

| Role | Font Size | Line Height | Tracking | Weight | OpenType Features | Usage in FinTrack |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Display Large** | $57\,\text{sp}$ | $64\,\text{sp}$ | $-0.25\,\text{sp}$ | SemiBold ($600$) | `tnum, zero` | Onboarding hero numbers, Safe-to-Spend major balance |
| **Display Medium** | $45\,\text{sp}$ | $52\,\text{sp}$ | $0.0\,\text{sp}$ | SemiBold ($600$) | `tnum, zero` | Account Net Worth hero figure |
| **Display Small** | $36\,\text{sp}$ | $44\,\text{sp}$ | $0.0\,\text{sp}$ | SemiBold ($600$) | `tnum, zero` | Large modal confirmation figures |
| **Headline Large** | $32\,\text{sp}$ | $40\,\text{sp}$ | $0.0\,\text{sp}$ | Medium ($500$) | `normal` | Screen titles (Home, Budgets, Reports) |
| **Headline Medium** | $28\,\text{sp}$ | $36\,\text{sp}$ | $0.0\,\text{sp}$ | Medium ($500$) | `normal` | Major section headers, card modal headers |
| **Headline Small** | $24\,\text{sp}$ | $32\,\text{sp}$ | $0.0\,\text{sp}$ | Medium ($500$) | `normal` | Account balance list headers, goal target headers |
| **Title Large** | $22\,\text{sp}$ | $28\,\text{sp}$ | $0.0\,\text{sp}$ | Medium ($500$) | `normal` | Top app bar titles, category group titles |
| **Title Medium** | $16\,\text{sp}$ | $24\,\text{sp}$ | $+0.15\,\text{sp}$ | SemiBold ($600$) | `normal` | Transaction payee names, account row titles |
| **Title Small** | $14\,\text{sp}$ | $20\,\text{sp}$ | $+0.1\,\text{sp}$ | SemiBold ($600$) | `normal` | Card subheadings, breakdown item titles |
| **Body Large** | $16\,\text{sp}$ | $24\,\text{sp}$ | $+0.5\,\text{sp}$ | Regular ($400$) | `normal` | Explanatory paragraphs, policy descriptions |
| **Body Medium** | $14\,\text{sp}$ | $20\,\text{sp}$ | $+0.25\,\text{sp}$ | Regular ($400$) | `normal` | Standard descriptions, transaction memos |
| **Body Small** | $12\,\text{sp}$ | $16\,\text{sp}$ | $+0.4\,\text{sp}$ | Regular ($400$) | `normal` | Timestamps, cleared/pending status, reconciliation dates |
| **Label Large** | $14\,\text{sp}$ | $20\,\text{sp}$ | $+0.1\,\text{sp}$ | Medium ($500$) | `normal` | Primary buttons, tab bar items, action chips |
| **Label Medium** | $12\,\text{sp}$ | $16\,\text{sp}$ | $+0.5\,\text{sp}$ | Medium ($500$) | `normal` | Filter pills, status badges, column headers |
| **Label Small** | $11\,\text{sp}$ | $16\,\text{sp}$ | $+0.5\,\text{sp}$ | Medium ($500$) | `tnum` | Overdue tags, mini-metric labels, micro-badges |

---

## 3. Indian Rupee (₹) Formatting Standard

FinTrack operates strictly with Indian Rupees (INR) with 2 decimal places (paise) represented as exact 64-bit signed integers in `Money(minor, currency)`.

### 3.1 Indian Numbering Grouping Rule
Unlike Western thousands grouping (`100,000,000`), the Indian numbering system groups the last three digits, and thereafter groups in pairs of two digits (Lakhs and Crores):

```
       100           ->  ₹100.00
     1,000           ->  ₹1,000.00
    10,000           ->  ₹10,000.00
  1,00,000           ->  ₹1,00,000.00      (1 Lakh)
 10,00,000           ->  ₹10,00,000.00     (10 Lakhs)
1,00,00,000          ->  ₹1,00,00,000.00   (1 Crore)
```

### 3.2 Visual Typography of the Rupee Glyph (₹)
To prevent visual dominance of the ₹ symbol when displaying prominent balance numbers:
1. **Size Ratio:** The ₹ glyph is scaled to **$75\%$ of the font size** of the accompanying amount, with a baseline shift alignment to sit comfortably along the optical text baseline.
2. **Decimal Sizing:** The decimal point and paise digits (`.00`) in primary hero displays are rendered at **$70\%$ font size** and slightly reduced opacity ($80\%$) to emphasize the whole rupee magnitude while maintaining exact mathematical precision.
3. **Negative Numbers:** Always formatted with an explicit leading minus sign immediately preceding the currency symbol: `-₹12,450.00`. In warning/deficit cards, it is accompanied by the semantic error color container. Parenthetical notation `(₹12,450.00)` is reserved strictly for accounting report tables.

---

## 4. Spacing, Grid, and Elevation

### 4.1 8dp Baseline Grid
All layout margins, paddings, and component sizes snap to an **8dp baseline grid**, with a 4dp micro-step for compact icon and tag paddings.

| Token | Dimension | Common Use Case |
| :--- | :--- | :--- |
| `Spacing.xxs` | $2\,\text{dp}$ | Micro-spacers, divider borders, stroke widths |
| `Spacing.xs` | $4\,\text{dp}$ | Tag internal padding, badge margin, icon-to-text micro gap |
| `Spacing.sm` | $8\,\text{dp}$ | Chip padding, list item internal vertical spacing, card gaps |
| `Spacing.md` | $12\,\text{dp}$ | Card internal compact padding, nested row spacing |
| `Spacing.lg` | $16\,\text{dp}$ | Standard screen edge gutters, card internal padding, button height offsets |
| `Spacing.xl` | $24\,\text{dp}$ | Section vertical separation, header-to-content gap |
| `Spacing.xxl` | $32\,\text{dp}$ | Hero card top/bottom margins, modal sheet top separation |
| `Spacing.xxxl` | $48\,\text{dp}$ | Primary action button spacing, bottom content breathing room |

### 4.2 Corner Radius Hierarchy

| Shape Token | Radius | Applied Components |
| :--- | :--- | :--- |
| `Shape.none` | $0\,\text{dp}$ | Full-bleed dividers, edge-to-edge banners |
| `Shape.small` | $8\,\text{dp}$ | Action chips, filter tags, text input fields |
| `Shape.medium` | $12\,\text{dp}$ | Transaction cards, sub-account tiles, dropdown menus |
| `Shape.large` | $16\,\text{dp}$ | Major summary cards, warning banners, alert dialogs |
| `Shape.extraLarge` | $24\,\text{dp}$ | Hero Safe-to-Spend card, bottom sheets, full modals |
| `Shape.full` | $1000\,\text{dp}$ | Primary FAB, pill navigation buttons, circular avatars |

### 4.3 Surface Elevation & Tonal Tinting
FinTrack avoids harsh drop shadows in favor of Material 3 **Tonal Surface Elevation**. Higher elevation surfaces in Dark Theme receive subtle primary-color tints:
- **Level 0 (Background):** `DarkSurface` ($0\,\text{dp}$)
- **Level 1 (Cards, Account rows):** `DarkSurface1` ($1\,\text{dp}$ / $5\%$ primary tint)
- **Level 2 (Navigation bar, Search bar):** `DarkSurface2` ($3\,\text{dp}$ / $8\%$ primary tint)
- **Level 3 (Bottom sheets, Dropdowns):** `DarkSurface3` ($6\,\text{dp}$ / $11\%$ primary tint)
- **Level 4 (Dialogs, Prompts):** `DarkSurface4` ($8\,\text{dp}$ / $12\%$ primary tint)

---

## 5. Haptic Feedback Matrix

Physical tactility builds confidence in financial actions. FinTrack utilizes Android's `HapticFeedbackConstants` / `Vibrator` API to provide nuanced tactile feedback:

| User Action | Haptic Cue | Technical Token | Emotional Intent |
| :--- | :--- | :--- | :--- |
| **Number keypad tap** | Light micro-tick | `KEYBOARD_TAP` | Effortless input confirmation |
| **Transaction posted** | Satisfying crisp pulse | `CONFIRM` | Sense of financial permanence |
| **Split added / removed** | Soft click | `CLOCK_TICK` | Mechanical precision |
| **Swipe-to-action trigger** | Boundary bump | `GESTURE_THRESHOLD_ACTIVATE`| Deliberate threshold cross |
| **Goal collision detected** | Distinct double-pulse | `WARNING` | Urgent yet polite friction |
| **Account reconciliation balanced** | Euphoric double-click | `CONFIRM` + `CLOCK_TICK` | Closure and achievement |
| **Deficit / Overdraft entered** | Heavy blunt buzz | `REJECT` | Cautionary friction |
