# FinTrack Mobile Design System (Phase 1)
**Principal Mobile Product Designer Handoff Specification**
*Native Android • Jetpack Compose • Material 3 • Offline-First Double-Entry Ledger*

---

## 1. Executive Summary & Product Vision

FinTrack is a premium, privacy-first, offline native personal finance management system designed specifically for Android. Unlike conventional budgeting apps that rely on superficial balance scraping, approximate categories, or cloud sync, FinTrack is built upon a deterministic double-entry bookkeeping engine (`finance-engine`) executing exact integer arithmetic in Indian Rupees (INR, minor unit paise).

The app's fundamental mission is to eliminate financial anxiety by providing complete mathematical certainty over a user's money:
- **Actual Account Balances ($L$):** Liquid cash held in physical and digital institutions.
- **Virtual Reservations ($R$):** Money ear-marked for specific goals, categories, or emergencies that never leaves the bank until spent.
- **Uncovered Mandatory Obligations ($U$):** Near-term bills not yet reserved.
- **Safety Buffer ($B$):** Protected discretionary floor.
- **Safe-to-Spend ($\max(0, L - R - U - B)$):** Real, unencumbered purchasing power that the user can deploy today without jeopardizing upcoming commitments or future goals.

### Design Tenets
1. **Financial Honesty Over Artificial Euphoria:** No fabricated seed money, no false positive projections, and zero hidden assumptions. Deficits are surfaced honestly, directly, and constructively.
2. **Clarity Over Clutter:** Modern, restrained, content-first typography using professional sans-serif typefaces (system Roboto / Inter) with strict tabular numerals for all financial figures. No decorative or cursive fonts.
3. **Glanceable Tri-State Mental Model:** Users must immediately distinguish between **What I Have** (Liquid Balances), **What I Owe / Reserved** (Commitments & Buckets), and **What I Can Safely Spend** (Safe-to-Spend).
4. **Delightful Tactility:** Subtle spring physics, predictive back animations, smooth number tickers, and refined haptic ticks make every financial transaction feel tangible and secure.
5. **Universal Accessibility:** Strict WCAG 2.1 AA/AAA contrast ratios, generous touch targets ($\ge 48\,\text{dp}$), dynamic font scaling up to $200\%$ without horizontal clipping, and complete TalkBack semantics.

---

## 2. Document Hierarchy & Handoff Index

This documentation suite constitutes the single source of truth for design, engineering, and QA across Phase 1 implementation.

| Document | File Name | Scope & Purpose |
| :--- | :--- | :--- |
| **System Overview** | [`README.md`](file:///d:/FinTrack/docs/design/README.md) | Vision, architectural principles, navigation anatomy, and directory index. |
| **Tokens & Theming** | [`01-DESIGN-TOKENS-AND-THEME.md`](file:///d:/FinTrack/docs/design/01-DESIGN-TOKENS-AND-THEME.md) | Light/dark Material 3 color palettes, semantic financial spectrum, typography scale, spacing grids, shapes, and INR formatting rules. |
| **Navigation & IA** | [`02-NAVIGATION-AND-IA.md`](file:///d:/FinTrack/docs/design/02-NAVIGATION-AND-IA.md) | 5-tab navigation bar, modal sheets, back-stack rules, predictive back gestures, and deep-link flows. |
| **Screen Inventory** | [`03-SCREEN-INVENTORY-AND-SPECS.md`](file:///d:/FinTrack/docs/design/03-SCREEN-INVENTORY-AND-SPECS.md) | Complete specifications for all 16 Phase 1 screens: ASCII wireframes, visual hierarchies, micro-states (empty, loading, error, conflict), and data mappings. |
| **Interaction & Motion** | [`04-INTERACTION-AND-ANIMATION.md`](file:///d:/FinTrack/docs/design/04-INTERACTION-AND-ANIMATION.md) | Motion tokens, transition choreography, spring physics, sheet behaviors, numeric tickers, and haptic feedback maps. |
| **Compose Implementation** | [`05-COMPOSE-IMPLEMENTATION-GUIDE.md`](file:///d:/FinTrack/docs/design/05-COMPOSE-IMPLEMENTATION-GUIDE.md) | Concrete Jetpack Compose code: `FinTrackTheme`, atomic reusable components (`SafeToSpendCard`, `MoneyText`, `WarningBanner`), UI models, and preview providers. |
| **Responsive & A11y** | [`06-RESPONSIVE-AND-ACCESSIBILITY.md`](file:///d:/FinTrack/docs/design/06-RESPONSIVE-AND-ACCESSIBILITY.md) | Screen size handling ($360\,\text{dp}$ to $600\,\text{dp}+$ foldables), font scale adaptability ($85\%\text{--}200\%$), touch targets, and accessibility semantics. |
| **Assets & Iconography** | [`07-ASSETS-AND-ICONS.md`](file:///d:/FinTrack/docs/design/07-ASSETS-AND-ICONS.md) | Material Symbols / Lucide vector specifications, density bucket guidelines, category icons, and app icon assets. |

---

## 3. High-Level Navigation Architecture

FinTrack uses a **five-destination Navigation Bar** optimized for single-thumb navigation on modern tall Android displays ($19:9$ to $21:9$ aspect ratios), complemented by a persistent **Primary Floating Action Button (FAB)** for recording transactions.

```
                     ┌────────────────────────────────────────────────────────┐
                     │                  FinTrack Top App Bar                  │
                     │  [FinTrack Logo / Book]        [Reconciliation] [Profile] │
                     └────────────────────────────────────────────────────────┘
                                                  │
         ┌───────────────────┬────────────────────┼───────────────────┬───────────────────┐
         ▼                   ▼                    ▼                   ▼                   ▼
    ┌─────────┐         ┌──────────┐        ┌───────────┐       ┌───────────┐       ┌───────────┐
    │  HOME   │         │ ACTIVITY │        │   PLAN    │       │   GOALS   │       │ INSIGHTS  │
    └─────────┘         └──────────┘        └───────────┘       └───────────┘       └───────────┘
    • Safe-to-Spend     • All Entries       • Monthly Budgets   • Goal Buckets      • Net Worth Trend
    • Liquid Balances   • Search & Filter   • Category Limits   • Emergency Fund    • Income vs Expense
    • Quick Metrics     • Splits & Transfers• Allocation Table  • Collision Warning • Category Breakdown
    • Urgent Actions    • Reversals         • Safe-to-Spend Deep• Diversion Preview • Weekly Review
    • Recent Activity   • Clear/Pending     • Cash-flow Forecast• Manual Invest     • Anomaly Detection
                                            • Purchase Simulator
                                                  ▲
                                                  │
                                       [ + QUICK ADD TRANSACTION ]
                                     (Income / Expense / Transfer)
```

### Primary Destinations
1. **Home (`/home`):** The financial command center. Features the dynamic Safe-to-Spend hero card, liquid account carousels, rapid-action cards, and recent posted activity.
2. **Activity (`/activity`):** The immutable ledger viewer. Search, filter by account/category/clearing status, view parent/split breakdowns, and execute auditable reverse-and-replace corrections.
3. **Plan (`/plan`):** Budgeting and forecasting control room. Monthly envelope allocation, rollover rules, cash-flow projections with explicit assumptions, and non-posting purchase simulations.
4. **Goals (`/goals`):** Goal reserves, emergency cushion, and priority collision tracker. Clear separation between funded reserves and planned projections. Interactive diversion feasibility calculators.
5. **Insights (`/insights`):** Visual financial intelligence. True income vs. expense reports (excluding transfers and opening equity), category concentration analysis, and weekly guided reviews.
6. **Settings & Admin (`/settings`):** Accessible via the top profile icon. Manage books, account archiving, biometric lock, database integrity checks, and encrypted backups.

---

## 4. Key Financial UX Invariants Grounding

Every visual component in FinTrack strictly enforces the mathematical contracts codified in [`docs/FINANCE_RULES.md`](file:///d:/FinTrack/docs/FINANCE_RULES.md):

1. **Conservation of Money:**
   - In any transaction or split, the sum of debits and credits must balance to zero.
   - The UI never permits fractional paise or unallocated remainders.
2. **Visual Separation of Three Monetary Tiers:**
   - **Tier 1: Physical Cash & Bank Balance ($L$):** What exists right now in banks and wallets.
   - **Tier 2: Virtual Reservations ($R$):** What has already been assigned to categories, emergencies, and goals.
   - **Tier 3: Safe-to-Spend ($S = \max(0, L - R - U - B)$):** Truly uncommitted money.
3. **No Phantom Income:**
   - Future expected salary or planned income is never added to Safe-to-Spend or funded goal progress.
   - Cash-flow forecasts process outflows before inflows on any given date to conservatively expose intraday liquidity risk.
4. **No Destructive Edits:**
   - Posted journal entries are immutable. Any error correction is executed via an auditable reverse-and-replace action with a mandatory reason memo.
5. **Discrepancy Transparency:**
   - Bank statement reconciliation never silently auto-adjusts. Any discrepancy is rendered in warning amber with an explicit "Reconcile Difference" flow.
