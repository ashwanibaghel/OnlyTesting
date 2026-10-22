# Navigation & Information Architecture
**Document 02 • FinTrack Mobile Design System**
*5-Tab Navigation Bar • Modal Workflows • Predictive Back • Deep Hierarchy*

---

## 1. Information Architecture Map

FinTrack organizes personal money management into five intuitive top-level destinations mapped directly to how people think about money: **Where I Stand (Home)**, **What Happened (Activity)**, **How I Allocate (Plan)**, **What I Am Building (Goals)**, and **What It Means (Insights)**.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                                 APP ROOT CONTAINER                                     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  TOP APP BAR: [Active Book: Personal INR ▼]             [Reconcile Alert] [Profile 👤] │
├────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                        │
│  DESTINATIONS (Bottom Navigation Bar)                                                  │
│                                                                                        │
│  1. HOME (/home)                                                                       │
│     ├── Hero: Safe-to-Spend Card ──> [Safe-to-Spend Explanation Sheet]                 │
│     ├── Liquid Accounts Horizontal Carousel ──> [All Accounts List]                    │
│     │   └── Account Card Tap ──> [Account Detail & Balance History]                    │
│     │       └── "Reconcile" Action ──> [Reconciliation Flow]                           │
│     ├── Quick Health Indicators (Uncovered Bills, Monthly Spend Pace)                  │
│     └── Recent Transactions Preview ──> [Activity Tab Full View]                       │
│                                                                                        │
│  2. ACTIVITY (/activity)                                                               │
│     ├── Sticky Search & Filter Bar ──> [Advanced Filter Sheet]                         │
│     ├── Grouped Transaction Timeline (Today, Yesterday, October 2026...)               │
│     │   └── Transaction Item Tap ──> [Transaction Detail & Split Modal]                │
│     │       ├── "Reverse & Replace" ──> [Auditable Correction Flow]                    │
│     │       └── "Mark Cleared / Pending" ──> [Clearing Toggle]                         │
│     └── Batch Actions Bar (Multi-select transactions for clearing)                     │
│                                                                                        │
│  3. PLAN (/plan)                                                                       │
│     ├── Month Selector Strip ([< September] [October 2026 ▼] [November >])             │
│     ├── Monthly Budget Envelope Overview (Total Income, Allocated, Unassigned)         │
│     ├── Category Allocation List (Needs, Wants, Subscriptions, Utilities)              │
│     │   └── Category Tap ──> [Category Allocation & Rollover Editor]                   │
│     ├── Segmented Switch: [Monthly Budgets | Cash-Flow Forecast]                       │
│     └── Sub-View: Cash-Flow Forecast                                                   │
│         ├── 30-Day / 90-Day Liquidity Projection Curve                                 │
│         ├── Intraday Risk Points (Outflows before Inflows)                             │
│         └── [Simulate Major Purchase] ──> [Purchase Simulation Flow]                   │
│                                                                                        │
│  4. GOALS (/goals)                                                                     │
│     ├── Emergency Reserves Protected Banner (3-6 Months Cushion)                       │
│     ├── Priority Ranked Goal Buckets (Target, Funded, Deadline, Monthly Requirement)   │
│     │   └── Goal Card Tap ──> [Goal Detail & Projection Timeline]                      │
│     │       ├── [Fund Goal Now] ──> [Transfer from Unassigned Bucket]                  │
│     │       └── [Divert Funds] ──> [Diversion Calculator & Protected Warning]          │
│     ├── Collision Warning Card (Triggered if Capacity < Required Monthly Sum)          │
│     └── Manual Investment Tracking Summary                                             │
│         └── Investment Account Tap ──> [Contribution History & Asset Ledger]           │
│                                                                                        │
│  5. INSIGHTS (/insights)                                                               │
│     ├── Time Period Selector (This Month, Last 3 Months, Year-to-Date)                │
│     ├── Ledger-True Income vs Expense Bar Chart (Excluding Transfers & Opening Equity) │
│     ├── Spending Category Concentration Donut & Breakdown Table                        │
│     ├── Savings Rate & Reserve Growth Metrics                                          │
│     └── Weekly Review Banner ──> [Weekly Financial Review Interactive Wizard]          │
│                                                                                        │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  PERSISTENT FLOATING ACTION BUTTON (FAB)                                               │
│  [ + Quick Add ] ──> Launches [Add Transaction Modal Sheet]                            │
│                      ├── Mode 1: Expense (Single or Multi-Split)                       │
│                      ├── Mode 2: Income                                                │
│                      └── Mode 3: Transfer (Between Owned Accounts)                     │
├────────────────────────────────────────────────────────────────────────────────────────┤
│  OVERLAY WORKFLOWS & MODALS                                                            │
│  ├── Profile & Settings (/settings)                                                    │
│  │   ├── Active Book Currency & Timezone Metadata                                      │
│  │   ├── Accounts Archive & Re-ordering                                                │
│  │   ├── Biometric App Lock & PIN Configuration                                        │
│  │   ├── Database Diagnostics & Ledger Invariant Verification                          │
│  │   └── Encrypted Backup Export & Restore Wizard                                      │
│  └── Discrepancy Correction Modal (/reconcile/adjust)                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Navigation Component Specifications

### 2.1 Five-Destination Bottom Navigation Bar
FinTrack employs a standard Material 3 Navigation Bar docked to the bottom edge, respecting the system navigation bar insets (edge-to-edge).

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│     [⌂]              [⇄]              [📋]             [🎯]             [📊]       │
│    Home            Activity           Plan            Goals           Insights    │
└───────────────────────────────────────────────────────────────────────────────────┘
```

- **Height:** $80\,\text{dp}$ total container height (plus system navigation bar inset).
- **Active Indicator:** Material 3 pill shape ($64 \times 32\,\text{dp}$) colored in `PrimaryContainer` with `OnPrimaryContainer` icon tint.
- **Labels:** Always shown (`NavigationData.ALWAYS_SHOW_LABELS`) in `LabelSmall` typography to eliminate guesswork for non-expert users.
- **Badge Support:**
  - `Activity`: Small badge dot if uncleared transactions require review.
  - `Plan`: Amber warning dot if a category has an uncovered overspend.
  - `Goals`: Amber warning dot if priority goal collisions exist.

### 2.2 Primary Floating Action Button (FAB)
The action to record finances must be effortlessly accessible from the user's natural thumb zone.
- **Positioning:** Docked in the lower-right corner ($16\,\text{dp}$ from right, $96\,\text{dp}$ from bottom to clear the navigation bar) on `Home`, `Activity`, and `Plan`.
- **Dimensions:** $56 \times 56\,\text{dp}$ Large Surface FAB with a $16\,\text{dp}$ corner radius.
- **Icon:** Bold `Add` (`+`) glyph with `OnPrimary` tint over a `Primary` emerald container.
- **Behavior on Scroll:** Extends on idle (`[+ Add Entry]`), collapses to icon-only upon scrolling down in `Activity` or `Plan`, and smoothly expands when scroll halts or reverses.

---

## 3. Modal Bottom Sheet System

Modals in FinTrack use a unified bottom sheet architecture (`ModalBottomSheet` with drag handle) offering two snap positions: **Half-Expanded ($60\%$ screen height)** and **Fully-Expanded ($95\%$ screen height)**.

### 3.1 Sheet Hierarchy & Dismissal Safety
1. **Add Transaction Sheet:** Opens at half-height for rapid single expense entry; expands to full height when user opens the category selector or split editor.
2. **Dirty Form Guard:** If a user enters an amount or payee and attempts to swipe down or tap the scrim, the sheet halts dismiss and triggers an alert dialog:
   ```
   [ Discard unposted transaction? ]
   "You have entered an amount of ₹2,450.00. Unposted entries will be lost."
   [ Continue Editing ]          [ Discard ]
   ```
3. **Safe-to-Spend Breakdown Sheet:** Read-only explanatory sheet launched directly from the Safe-to-Spend hero card. Provides a visual mathematical bridge of $L - R - U - B = S$.

---

## 4. Back Stack & Predictive Back Navigation (Android 14+)

FinTrack strictly implements Android's modern predictive back design contract:

### 4.1 Root Destination Tab Switching
- Tapping any of the 5 bottom navigation tabs replaces the current root screen using a **crossfade fade-through transition ($200\,\text{ms}$)**.
- Pressing Android System Back on any tab (`Activity`, `Plan`, `Goals`, `Insights`) returns to the `Home` tab before exiting the app.
- Re-tapping the active tab smoothly scrolls the active LazyColumn to the top ($0\,\text{dp}$ offset).

### 4.2 Deep Screen Hierarchy (Push & Pop)
When navigating into an Account Detail or Goal Detail screen:
- **Forward Animation:** Material 3 Shared Z-Axis forward scale transition ($300\,\text{ms}$ cubic bezier `(0.05, 0.7, 0.1, 1.0)`).
- **Predictive Back (Android 14+):** The child screen scales down subtly ($90\%$) with rounded corners to reveal the parent screen underneath, allowing the user to cancel or complete the pop gesture smoothly with their thumb.

---

## 5. Top App Bar & Contextual Action Bar

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│ [FinTrack Leaf] Personal INR ▼                    [!] Reconcile    [Profile 👤]   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

1. **Book Selector Dropdown:** Tap to view book properties (Currency: INR, Exponent: 2, Timezone: Asia/Kolkata). In future multi-book milestones, this switches active ledger contexts.
2. **Reconciliation Attention Pill:** Appears in warning amber if an active bank account has not been reconciled in $>30$ days or has an active statement difference.
3. **Profile Avatar:** Leads directly to the Settings, Security, App Lock, and Encrypted Backup hub.
