# Screen Inventory & Specifications (Phase 1)
**Document 03 • FinTrack Mobile Design System**
*All 16 Phase 1 Screens • Layout Wireframes • Domain Invariants • Micro-States*

---

> [!NOTE]
> All numerical figures presented in the wireframes and screen layouts below are explicitly labeled as **[Design Fixture: Sample Data]**. They demonstrate visual balance, typography, and edge-case behavior without asserting real-world financial records.

---

## Screen 01: Welcome and Financial Onboarding

### 1.1 Metadata
- **Route:** `/onboarding/welcome` -> `/onboarding/first-account` -> `/onboarding/opening-balance`
- **Primary Objective:** Welcome first-time users, establish local privacy guarantee (no cloud, no data harvesting), choose book currency (INR) and timezone, and record their first physical account with an explicit opening balance ledger event.

### 1.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [FinTrack Logo Glyph]                                  │
│                                                        │
│ Welcome to FinTrack                                    │
│ Your personal money command center.                    │
│ Completely private. Kept solely on this device.       │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🛡️ Privacy Guarantee                               │ │
│ │ • No cloud accounts or passwords required          │ │
│ │ • No bank scraping or SMS snooping                 │ │
│ │ • Exact double-entry arithmetic in Indian Rupees   │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ Step 1 of 2: Set Up Your Primary Account               │
│                                                        │
│ Account Name                                           │
│ [ HDFC Salary Account                              ]   │
│                                                        │
│ Account Type                                           │
│ [ (•) Bank Account    ( ) Cash Wallet    ( ) Savings ] │
│                                                        │
│ Opening Liquid Balance                                 │
│ [ ₹  75,000.00                                     ]   │
│ ℹ️ This creates a verifiable opening equity entry.     │
│                                                        │
│ Currency & Timezone                                    │
│ [ Indian Rupee (INR ₹) • Asia/Kolkata (IST)        ]   │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ [ Start Managing My Finances -> ]                  │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 1.3 Component & Domain Specifications
1. **Currency Lock:** Fixed to `INR` (exponent 2). In accordance with Phase 1 invariants, no currency conversion or FX guessing is permitted.
2. **Opening Equity Ledger Postings:** Entering `₹75,000.00` creates a balanced journal:
   - Asset leg: `+₹75,000.00` (HDFC Salary Account)
   - Equity leg: `-₹75,000.00` (Opening Balances Equity Control)
3. **Micro-States:**
   - **Initial Empty:** Button disabled until account name $\ge 2$ characters and valid non-negative decimal amount entered.
   - **Validation Error:** If user enters an unparseable amount or negative decimal, inline error message appears: *"Opening balance must be a valid non-negative rupee amount."*
   - **Success:** Haptic tick (`CONFIRM`), database commits opening journal within `withTransaction`, and navigates to `/home`.

---

## Screen 02: Home / Financial Dashboard

### 2.1 Metadata
- **Route:** `/home`
- **Primary Objective:** Provide instant clarity on financial readiness. Show Safe-to-Spend front and center, summarize actual liquid accounts, and present immediate upcoming commitments.

### 2.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [FinTrack Leaf] Personal INR ▼       [!] Alert   [👤]  │
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │ SAFE-TO-SPEND (Through Oct 31)                 ℹ️ │ │
│ │ ₹ 42,500.00                                        │ │
│ │ Ready for unencumbered spending today              │ │
│ │ ────────────────────────────────────────────────── │ │
│ │ Liquid Balances:  ₹1,20,000.00                     │ │
│ │ Reserved Funds:  -₹  65,000.00 (Goals & Envelopes) │ │
│ │ Uncovered Bills: -₹  12,500.00 (Due this month)    │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ MY ACCOUNTS (₹1,20,000.00)               [View All >]  │
│ ┌──────────────────┐  ┌──────────────────┐             │
│ │ HDFC Bank        │  │ Cash Wallet      │             │
│ │ ₹ 1,12,000.00    │  │ ₹   8,000.00     │             │
│ │ Cleared: ₹1.05L  │  │ Physical Cash    │             │
│ └──────────────────┘  └──────────────────┘             │
│                                                        │
│ ATTENTION REQUIRED (2)                                 │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ⚠️ Goal Collision Warning                          │ │
│ │ Capacity shortage of ₹3,000/mo. Emergency cushion  │ │
│ │ is falling behind schedule. [Resolve ->]           │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ RECENT ACTIVITY                          [See More >]  │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🛒 Nature's Basket Groceries           -₹ 2,450.00 │ │
│ │    HDFC Bank • Groceries Envelope • Today          │ │
│ │ ⚡ Electricity Bill (BESCOM)            -₹ 1,820.00 │ │
│ │    HDFC Bank • Utilities • Yesterday               │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│                          [ + Add Entry FAB ]           │
├────────────────────────────────────────────────────────┤
│ [⌂ Home]   [⇄ Activity]  [📋 Plan]  [🎯 Goals] [📊 Insights]│
└────────────────────────────────────────────────────────┘
```

### 2.3 Component & Domain Specifications
1. **Safe-to-Spend Hero Card:** Calls `com.fintrack.engine.safeToSpend(liquid, reserved, uncovered, buffer)`. Renders in `PrimaryContainer` when positive. Tapping the card opens the Safe-to-Spend explanation modal (Screen 12).
2. **Accounts Carousel:** Horizontally scrollable list of active (non-archived) accounts showing total posted balance and cleared balance.
3. **Deficit State (Negative Safe-to-Spend):** If $L < R + U + B$, hero card turns `ErrorContainer` (Ruby Red):
   - Hero Amount: `-₹ 8,400.00`
   - Label: *"Safe-to-Spend Deficit"*
   - Subtext: *"Your commitments exceed liquid money. Reallocate budget envelopes or adjust goal funding."*

---

## Screen 03: Bank Accounts and Cash Wallets

### 3.1 Metadata
- **Route:** `/accounts`
- **Primary Objective:** Central inventory of all institutional bank accounts, physical cash wallets, credit facilities, and non-liquid manual investment accounts.

### 3.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Back]  Accounts & Wallets            [+ New Account]│
├────────────────────────────────────────────────────────┤
│ Total Liquid Funds                                     │
│ ₹ 1,20,000.00                                          │
│ Available across 3 active liquid accounts              │
│                                                        │
│ [ (•) Active (4) ]   [ Archived (1) ]                  │
│                                                        │
│ BANK ACCOUNTS                                          │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🏦 HDFC Salary Account                             │ │
│ │    A/C •••• 4092                                   │ │
│ │    Actual: ₹ 1,12,000.00     Cleared: ₹1,05,000.00 │ │
│ │    Pending: ₹7,000.00 (1 unposted deposit)         │ │
│ └────────────────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🏦 ICICI Emergency Reserve Account                 │ │
│ │    A/C •••• 9811                                   │ │
│ │    Actual: ₹  50,000.00      Cleared: ₹ 50,000.00  │ │
│ │    Reconciled: Oct 15, 2026 (Balanced)             │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ CASH WALLETS                                           │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 💵 Physical Cash Wallet                            │ │
│ │    In Pocket                                       │ │
│ │    Actual: ₹   8,000.00      Cleared: ₹  8,000.00  │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ NON-LIQUID TRACKED ACCOUNTS                            │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📈 Zerodha Mutual Funds Portfolio                  │ │
│ │    Manual Cost Basis: ₹ 2,50,000.00                │ │
│ │    ℹ️ Excluded from liquid Safe-to-Spend           │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 3.3 Component & Domain Specifications
1. **Liquid vs. Non-Liquid Partition:** Visual separation between liquid cash accounts ($L$) and tracked non-liquid asset accounts. Tracked accounts are labeled with a clear information badge: *"Excluded from liquid Safe-to-Spend."*
2. **Archiving Flow:** Swiping left on an account reveals `[Archive]`. Archiving prevents future transactions but permanently preserves past ledger integrity.

---

## Screen 04: Account Details and Balance History

### 4.1 Metadata
- **Route:** `/accounts/{accountId}`
- **Primary Objective:** Deep dive into a single account, showing balance progression, pending transactions, statement reconciliation status, and account settings.

### 4.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Back]  HDFC Salary Account              [⚙️ Edit]   │
├────────────────────────────────────────────────────────┤
│ CURRENT BALANCE                                        │
│ ₹ 1,12,000.00                                          │
│ Cleared: ₹1,05,000.00  •  Pending: +₹7,000.00          │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ⚖️ Statement Reconciliation                         │ │
│ │ Last Reconciled: Oct 1, 2026 (Statement: ₹98,000)  │ │
│ │ [ Start Statement Reconciliation -> ]              │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ 30-DAY BALANCE TRAJECTORY (Actual Ledger Trend)        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ₹1.3L ┤      ╭──╮                                  │ │
│ │ ₹1.1L ┤──────╯  ╰────────                          │ │
│ │ ₹0.9L ┤                                            │ │
│ │       Oct 1     Oct 10     Oct 20     Oct 31       │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ACCOUNT TRANSACTIONS                                   │
│ [ All ]   [ Uncleared Only (1) ]   [ Search 🔍 ]      │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ⏳ Client Consulting Payment (Cheque)   +₹ 7,000.00│ │
│ │    Status: PENDING CLEARING • Effective Oct 22     │ │
│ │    [ Mark as Cleared ]                             │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ 🛒 Nature's Basket Groceries            -₹ 2,450.00│ │
│ │    Status: Cleared • Oct 21                        │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 4.3 Reconciliation Discrepancy Flow (Edge Case)
If the user performs a statement reconciliation and enters `Statement Balance: ₹1,00,000.00` while ledger cleared balance is `₹1,05,000.00`:
- **Discrepancy Banner (Amber Warning):**
  *"Discrepancy of ₹5,000.00 detected between bank statement and recorded ledger."*
- **Action Buttons:**
  - `[ Review Missing Entries ]` (Filters ledger to uncleared items)
  - `[ Create Reconciliation Adjustment ]` (Posts explicit adjustment journal against `Reconciliation Adjustment Equity Control` with mandatory audit reason).

---

## Screen 05: Add Income / Expense / Transfer Transaction Flow

### 5.1 Metadata
- **Route:** `/transaction/add` (Modal Bottom Sheet)
- **Primary Objective:** High-speed, frictionless transaction logging supporting single expense, categorized income, account-to-account transfer, and multi-category splits.

### 5.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│               [━━━ Drag Handle ━━━]                   │
│ New Transaction                             [✕ Close]  │
│                                                        │
│ [ (•) Expense ]        [ Income ]        [ Transfer ]  │
│                                                        │
│ Amount                                                 │
│ ₹ 3,450.00                                             │
│                                                        │
│ Paid From Account                                      │
│ [ 🏦 HDFC Salary Account (Bal: ₹1,12,000.00)       ▼ ] │
│                                                        │
│ Category Allocation                                    │
│ [ 🍽️ Dining Out (Available in envelope: ₹4,500.00) ▼ ] │
│ [ + Split into multiple categories ]                   │
│                                                        │
│ Date & Clearing Status                                 │
│ [ 📅 Today (Oct 23, 2026) ]   [ (•) Cleared ( ) Pending│
│                                                        │
│ Payee / Merchant                                       │
│ [ Toit Brewpub, Indiranagar                         ]  │
│                                                        │
│ Notes / Memo (Optional)                                │
│ [ Team dinner celebration                           ]  │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ [ Record Expense (₹3,450.00) ]                     │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 5.3 Split Category Sub-View (Conservation Invariant)
When user taps `[ + Split into multiple categories ]`:
```
┌────────────────────────────────────────────────────────┐
│ Category Splits (Total must equal ₹3,450.00)          │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 1. 🍽️ Dining Out                    ₹ 2,450.00 [✕] │ │
│ │ 2. 🚕 Transport (Uber cab)          ₹ 1,000.00 [✕] │ │
│ └────────────────────────────────────────────────────┘ │
│ Split Sum: ₹3,450.00 • Remaining: ₹0.00 (Balanced ✓)   │
│ [ + Add Another Split Leg ]                            │
└────────────────────────────────────────────────────────┘
```
- **Engine Invariant:** If `Split Sum != Parent Amount`, the `[ Record Expense ]` button is disabled, and an inline badge displays: *"Splits do not total parent expense. Difference: ₹X.XX"*.

---

## Screen 06: Transaction Activity, Search and Filters

### 6.1 Metadata
- **Route:** `/activity`
- **Primary Objective:** The authoritative chronological record of every financial event. Search by payee, filter across multiple dimensions, inspect splits, and execute reverse-and-replace corrections.

### 6.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ Activity & Ledger History                 [Export CSV] │
├────────────────────────────────────────────────────────┤
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🔍 Search payee, memo, amount...                   │ │
│ └────────────────────────────────────────────────────┘ │
│ [ All Accounts ▼ ]  [ Categories ▼ ]  [ Status: All ▼ ]│
│                                                        │
│ OCTOBER 2026                                           │
│ ────────────────────────────────────────────────────── │
│ TODAY, OCT 23                                          │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🍽️ Toit Brewpub                        -₹ 3,450.00 │ │
│ │    HDFC Salary • Split (2 categories) • Cleared    │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ 💼 Tech Consulting Client             +₹ 75,000.00 │ │
│ │    HDFC Salary • Income: Professional Fees         │ │
│ └────────────────────────────────────────────────────┘ │
│ YESTERDAY, OCT 22                                      │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ⇄ Transfer to ICICI Emergency          ₹ 15,000.00 │ │
│ │    HDFC Salary -> ICICI Emergency • Net Worth: ₹0  │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ ⚡ BESCOM Electricity Bill              -₹ 1,820.00 │ │
│ │    HDFC Salary • Utilities Envelope                │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ↩️ CORRECTION EVENT                                │ │
│ │ Original Entry #J-1082 Reversed: -₹500 (Wrong Acct) │ │
│ │ Replacement #J-1083 Posted: -₹500 (Cash Wallet)     │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 6.3 Reverse-and-Replace Correction Flow
Tapping any posted transaction presents a `[ Correct This Entry ]` option:
- FinTrack **never deletes or mutates** a committed journal row.
- Tapping correction prompts:
  1. Mandatory Correction Reason memo (e.g., *"Entered wrong bank account"*).
  2. Creates atomic reversal posting equal and opposite to original.
  3. Inserts replacement entry with corrected parameters.
  4. Both entries are linked via `reversal_of` foreign keys for audit permanence.

---

## Screen 07: Monthly Budgeting and Category Allocation

### 7.1 Metadata
- **Route:** `/plan/budgets`
- **Primary Objective:** Manage monthly envelope targets, fund category reserves, track real-time spending pace, and configure explicit end-of-month rollover rules.

### 7.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Sep]              October 2026             [Nov >]  │
├────────────────────────────────────────────────────────┤
│ MONTHLY ENVELOPE SUMMARY                               │
│ Total Income Posted:        ₹ 1,00,000.00              │
│ Total Allocated to Targets: ₹   80,000.00              │
│ Unassigned Liquid Funds:    ₹   20,000.00 (Ready to allocate)│
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ Allocation Bar: 80% Allocated (₹80K / ₹100K)       │ │
│ │ [████████████████████████████████░░░░░░░░]         │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ CATEGORY GROUPS                                        │
│                                                        │
│ ▼ ESSENTIALS & BILLS (4)          Spent ₹28K / ₹45K    │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🏠 House Rent & Maintenance             ₹ 25,000.00│ │
│ │    Spent: ₹25,000.00 • Remaining: ₹0.00 (100% used)│ │
│ │    Rollover: OFF                                   │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ ⚡ Electricity & Water                   ₹  3,500.00│ │
│ │    Spent: ₹1,820.00 • Remaining: ₹1,680.00         │ │
│ │    Rollover: POSITIVE REMAINDER (Carries to Nov)   │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ▼ DISCRETIONARY (3)               Spent ₹18K / ₹25K    │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🍽️ Dining Out & Cafes                   ₹  8,000.00│ │
│ │    Spent: ₹6,450.00 • Remaining: ₹1,550.00         │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ ⚠️ Groceries & Supplies (OVERSPENT)     ₹ 12,000.00│ │
│ │    Spent: ₹14,200.00 • Overspent: -₹2,200.00       │ │
│ │    ⚠️ Unfunded overspend reduces Safe-to-Spend!     │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 7.3 Domain Rules & Rollover Policies
1. **Rollover Options (`RolloverPolicy`):**
   - `OFF` (Default): Unspent category remainder is released back to unassigned funds at midnight on the last calendar day of the month.
   - `POSITIVE_REMAINDER`: Unspent remainder carries forward into next month's allowance: $\text{Allowance} = \text{Target} + \text{Carried}$.
2. **Overspend Handling:** If spent $>$ allowance, remaining is `₹0.00` and `overspent` is flagged in Ruby Crimson. Overspend immediately decreases overall unassigned liquidity.

---

## Screen 08: Savings Buckets and Emergency Reserves

### 8.1 Metadata
- **Route:** `/goals/savings-reserves`
- **Primary Objective:** Protect non-negotiable cash reserves (Emergency Fund, Medical Buffer) and manage virtual savings buckets separated cleanly from bank locations.

### 8.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Back]   Savings & Emergency Reserves                │
├────────────────────────────────────────────────────────┤
│ PROTECTED RESERVES SUMMARY                             │
│ ₹ 65,000.00 Protected                                  │
│ Total virtual reservations safeguarding your stability │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🛡️ 6-MONTH EMERGENCY RUNWAY                         │ │
│ │ Target: ₹ 1,50,000.00  •  Funded: ₹ 50,000.00 (33%)│ │
│ │ [██████████░░░░░░░░░░░░░░░░░░░░]                   │ │
│ │ Current Runway: 2.1 Months of essential expenses   │ │
│ │ Status: PROTECTED RESERVATION                      │ │
│ │ [ + Add Funds ]      [ Safe Diversion Preview ]    │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ VIRTUAL SAVINGS BUCKETS                                │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🩺 Medical & Dental Reserve                        │ │
│ │    Funded: ₹ 10,000.00  •  Target: ₹ 20,000.00     │ │
│ │    Protection: High                                │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ 🚗 Annual Car Insurance & Service                  │ │
│ │    Funded: ₹  5,000.00  •  Target: ₹ 15,000.00     │ │
│ │    Due: March 2027 (On Track)                      │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ [ + Create New Savings Bucket ]                        │
└────────────────────────────────────────────────────────┘
```

### 8.3 Domain Invariants Grounding
- **Location Agnostic:** A savings bucket is a virtual reservation against total liquid assets ($L$). It does not require funds to be trapped in a specific savings account.
- **Deficit Protection:** Tapping `[ Safe Diversion Preview ]` opens Screen 10 to simulate diversion without mutating balances.

---

## Screen 09: Financial Goals List and Individual Goal Detail

### 9.1 Metadata
- **Route:** `/goals` and `/goals/{goalId}`
- **Primary Objective:** Track prioritized financial targets with strict mathematical separation between *actually funded reserves* and *planned future projections*.

### 9.2 Visual Layout Wireframe (Goal Detail)
```
┌────────────────────────────────────────────────────────┐
│ [< Back]   Goal Details                    [✏️ Edit]   │
├────────────────────────────────────────────────────────┤
│ 🚗 Buy New Electric Car (Priority 1)                   │
│ Target: ₹ 6,00,000.00  •  Deadline: Dec 2027          │
│                                                        │
│ FUNDED PROGRESS (Actually Reserved Cash)               │
│ ₹ 1,80,000.00 / ₹ 6,00,000.00 (30.0%)                  │
│ [█████████░░░░░░░░░░░░░░░░░░░░░]                       │
│                                                        │
│ CONTRIBUTION PLAN                                      │
│ • Remaining Target:      ₹ 4,20,000.00                 │
│ • Remaining Periods:     14 Months                     │
│ • Required Monthly Sum:  ₹   30,000.00 / month         │
│ • Planned Contribution:  ₹   25,000.00 / month         │
│ ⚠️ Shortfall: -₹5,000.00/mo. Projected delay: 2.8 mos. │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📅 Monthly Contribution Anchor Day: 5th of month   │ │
│ │ Next Planned Allocation: Nov 5, 2026               │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ACTIONS                                                │
│ ┌────────────────────────────────────────────────────┐ │
│ │ [ 💰 Fund From Unassigned Reserves ]               │ │
│ │ [ ⚠️ Divert Reserved Money ]                       │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 9.3 Engine Invariants
1. `requiredContribution(target, funded, remainingPeriods)` executes:
   $$\text{remaining} = \max(0, \text{target} - \text{funded})$$
   $$\text{requiredMonthly} = \lceil \text{remaining} / \text{remainingPeriods} \rceil$$
2. **No Phantom Progress:** Planned future contributions are clearly marked as *projections*. Only actually committed journal allocations count toward the `30.0%` funded progress.

---

## Screen 10: Goal Funding, Money Diversion and Risk Warnings

### 10.1 Metadata
- **Route:** `/goals/{goalId}/divert`
- **Primary Objective:** Provide a rigorous, transparent consequence simulator when a user seeks to divert money out of a protected goal reserve for discretionary purchases.

### 10.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Cancel]   Divert Protected Goal Funds               │
├────────────────────────────────────────────────────────┤
│ ⚠️ PROTECTED FUNDS WARNING                             │
│ Diverting money from goals impairs your financial      │
│ milestones and projected completion dates.             │
│                                                        │
│ Source Goal                                            │
│ [ 🚗 Buy New Electric Car (Funded: ₹1,80,000.00)     ] │
│                                                        │
│ Amount to Divert                                       │
│ [ ₹  30,000.00                                     ]   │
│                                                        │
│ IMPACT ANALYSIS (finance-engine preview)               │
│ ┌────────────────────────────────────────────────────┐ │
│ │ Remaining Goal Reserve:   ₹ 1,50,000.00 (was 1.80L)│ │
│ │ New Required Monthly:     ₹   32,143.00 (+₹2,143)  │ │
│ │ Projected Delay:          + 1.8 Months             │ │
│ │ Revised Completion Date:  February 2028            │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ Destination of Diverted Funds                          │
│ [ (•) Unassigned Liquid Funds (Increase Safe-to-Spend) │
│   ( ) Move to Emergency Runway                         │
│   ( ) Move to Another Goal (Priority 2)              ] │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ⚠️ Explicit Confirmation Required                   │ │
│ │ [X] I understand this diversion pushes back my     │ │
│ │     Electric Car target by approximately 2 months.  │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ [ Confirm Diversion of ₹30,000.00 ]                │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

### 10.3 Engine Invariants & Safety Protocol
- Invokes `com.fintrack.engine.previewDiversion(funded, amount, protected)`.
- Validates that $0 < \text{amount} \le \text{funded}$.
- If `protected == true`, requires an explicit un-checked confirmation checkbox before the confirm button becomes clickable.
- Haptic feedback: `WARNING` double-pulse upon entering an amount that delays the deadline by $>30$ days.

---

## Screen 11: Investment Allocation and Manual Tracking

### 11.1 Metadata
- **Route:** `/plan/investments`
- **Primary Objective:** Plan monthly investment contributions and record manual cost basis updates without treating transfers to investments as household consumption expenses.

### 11.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Back]   Investments & Long-Term Capital             │
├────────────────────────────────────────────────────────┤
│ TRACKED PORTFOLIO COST BASIS                           │
│ ₹ 4,50,000.00                                          │
│ Cumulative contributions • Excluded from liquid funds  │
│                                                        │
│ MONTHLY INVESTMENT PLAN (October 2026)                 │
│ Planned Contributions:  ₹ 20,000.00                    │
│ Deposited This Month:   ₹ 20,000.00 (100% Completed ✓) │
│                                                        │
│ TRACKED ASSETS                                         │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📈 Nifty 50 Index Mutual Fund (SIP)                │ │
│ │    Monthly Plan: ₹10,000 • Transferred Oct 5       │ │
│ │    Total Invested Cost: ₹ 2,20,000.00              │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ 📈 Public Provident Fund (PPF)                     │ │
│ │    Monthly Plan: ₹10,000 • Transferred Oct 10      │ │
│ │    Total Invested Cost: ₹ 2,30,000.00              │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ℹ️ FinTrack records exact capital contributions.      │
│ To safeguard privacy and reliability, real-time market │
│ scraping and speculative trading are excluded.         │
│                                                        │
│ [ + Record Manual Investment Contribution ]            │
└────────────────────────────────────────────────────────┘
```

### 11.3 Ledger Accounting Principle
- A transfer from `HDFC Salary` to `Zerodha Mutual Funds` is an **Asset-to-Asset transfer**:
  - `HDFC Salary`: `-₹20,000.00`
  - `Zerodha Investment`: `+₹20,000.00`
- **Zero Expense Impact:** This transfer reduces liquid cash ($L$) but does **not** count as household consumption expense in income/expense reports.

---

## Screen 12: Safe-to-Spend Explanation

### 12.1 Metadata
- **Route:** `/home/safe-to-spend-breakdown` (Modal Sheet)
- **Primary Objective:** Demystify the Safe-to-Spend formula for everyday users through a transparent, visual mathematical bridge.

### 12.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│               [━━━ Drag Handle ━━━]                   │
│ Understanding Safe-to-Spend                 [✕ Close]  │
│                                                        │
│ Safe-to-Spend is your true disposable financial floor  │
│ through October 31, 2026.                              │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │  TOTAL LIQUID ASSETS (L)             ₹ 1,20,000.00 │ │
│ │  Actual cash in HDFC, ICICI, and Wallets           │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ - RESERVED FUNDS (R)                -₹   65,000.00 │ │
│ │   • Emergency Cushion:    ₹50,000                  │ │
│ │   • Car Goal Envelope:    ₹10,000                  │ │
│ │   • Unspent Category Budgets: ₹5,000               │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ - UNCOVERED BILLS (U)               -₹   12,500.00 │ │
│ │   Mandatory obligations due before next income     │ │
│ │   • Electricity & WiFi Bills:  ₹3,500              │ │
│ │   • Health Insurance Premium:  ₹9,000              │ │
│ └────────────────────────────────────────────────────┘ │
│ = SAFE-TO-SPEND (L - R - U)            ₹   42,500.00 │ │
│                                                        │
│ 💡 WHY THIS KEEPS YOU SAFE:                            │
│ Unlike simple bank balances, spending your Safe-to-   │
│ Spend never risks unpaid bills, depletes emergency     │
│ reserves, or disrupts your prioritized goals.          │
│                                                        │
│ [ Got It, Thanks! ]                                    │
└────────────────────────────────────────────────────────┘
```

---

## Screen 13: Cash-Flow Forecasting and Purchase Simulation

### 13.1 Metadata
- **Route:** `/plan/forecast`
- **Primary Objective:** Provide a dated forward-looking cash projection with conservative intraday liquidity risk (outflows before inflows), and enable non-posting purchase simulations.

### 13.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Back]  Cash-Flow Forecast & Simulation              │
├────────────────────────────────────────────────────────┤
│ 30-DAY LIQUIDITY TRAJECTORY (Oct 23 - Nov 22)          │
│ Opening Liquid: ₹1,20,000.00  •  Closing: ₹1,45,000.00 │
│ Minimum Projected Floor: ₹82,000.00 (on Nov 4)         │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ₹1.5L ┤                                   ╭────────│ │
│ │ ₹1.2L ┤──╮                           ╭────╯        │ │
│ │ ₹0.8L ┤  ╰───────╮              ╭────╯             │ │
│ │       Oct 23    Nov 1 (Rent)   Nov 5 (Salary)      │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🔮 SIMULATE A MAJOR PURCHASE                       │ │
│ │ Test if you can afford an expense without posting  │ │
│ │ Purchase Amount: [ ₹ 45,000.00                   ] │ │
│ │ Planned Date:    [ Nov 2, 2026                   ] │ │
│ │                                                    │ │
│ │ SIMULATION RESULT:                                 │ │
│ │ ⚠️ LIQUIDITY TROUGH WARNING                        │ │
│ │ On Nov 4, liquid balance would drop to ₹37,000.00. │ │
│ │ 1 Goal will experience a funding shortfall.        │ │
│ │ [ Clear Simulation ]                               │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ DISCLOSED ASSUMPTIONS (Engine Invariant)               │
│ • Assumes monthly salary of ₹75,000 posts on Nov 5     │
│ • Assumes recurring rent of ₹25,000 debits on Nov 1    │
│ • Outflows are processed before inflows on same dates  │
└────────────────────────────────────────────────────────┘
```

### 13.3 Engine Grounding
- Uses `com.fintrack.engine.forecast(asOf, through, opening, events, assumptions)`.
- **Simulation Isolation:** `simulatePurchase()` operates on an immutable memory snapshot. It **never writes a row to Room**.

---

## Screen 14: Reports and Financial Insights

### 14.1 Metadata
- **Route:** `/insights`
- **Primary Objective:** True ledger-derived analytical reporting: Income vs Expense trends, category concentration, savings rate, and reserve trajectories.

### 14.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ Financial Insights                        [Filter 📅]  │
├────────────────────────────────────────────────────────┤
│ [ This Month (Oct) ]   [ Last 3 Months ]   [ Year 2026]│
│                                                        │
│ NET CASH FLOW (True Operating Surplus)                 │
│ Total Income:    ₹ 1,00,000.00                         │
│ Total Expenses: -₹   52,450.00                         │
│ Net Saved:       ₹   47,550.00 (Savings Rate: 47.5%)   │
│                                                        │
│ INCOME VS EXPENSE (Ledger True)                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ Aug:  [██████ Income ₹95K ] [█████ Exp ₹50K ]      │ │
│ │ Sep:  [██████ Income ₹98K ] [██████ Exp ₹55K ]     │ │
│ │ Oct:  [███████ Income ₹100K] [█████ Exp ₹52K ]     │ │
│ └────────────────────────────────────────────────────┘ │
│ ℹ️ Transfers and opening balances are strictly excluded│
│                                                        │
│ EXPENSE BREAKDOWN BY CATEGORY                          │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🏠 Rent & Housing          ₹ 25,000.00 (47.7%)     │ │
│ │ 🛒 Groceries & Supplies    ₹ 14,200.00 (27.1%)     │ │
│ │ 🍽️ Dining & Social         ₹  6,450.00 (12.3%)     │ │
│ │ ⚡ Utilities & WiFi        ₹  3,500.00  (6.7%)     │ │
│ │ 🚕 Transportation          ₹  3,300.00  (6.2%)     │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

---

## Screen 15: Weekly Financial Review

### 15.1 Metadata
- **Route:** `/insights/weekly-review`
- **Primary Objective:** A guided 3-minute Sunday financial ritual to review uncleared transactions, reallocate budget overspends, verify goals, and plan the upcoming week.

### 15.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Exit Review]  Weekly Review (Week 43)      [Step 1/4│
├────────────────────────────────────────────────────────┤
│ STEP 1: REVIEW UNCLEARED TRANSACTIONS                  │
│ You have 2 transactions awaiting clearance.            │
│ Confirm if these have posted to your bank statement.   │
│                                                        │
│ ┌────────────────────────────────────────────────────┐ │
│ │ ⏳ Client Consulting Payment            +₹ 7,000.00 │ │
│ │    Cheque deposit • Oct 22                         │ │
│ │    [ (•) Cleared in Bank ]   [ Still Pending ]     │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ ⏳ Fuel Station Card Hold                -₹ 1,200.00 │ │
│ │    HPCL Auto fuel • Oct 21                         │ │
│ │    [ (•) Cleared in Bank ]   [ Still Pending ]     │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ [ Next: Review Category Envelopes -> ]                 │
└────────────────────────────────────────────────────────┘
```

---

## Screen 16: Settings, Privacy, Security and Backup

### 16.1 Metadata
- **Route:** `/settings`
- **Primary Objective:** Administrative control, biometric app lock, local encrypted backup export/restore, book properties, and database verification.

### 16.2 Visual Layout Wireframe
```
┌────────────────────────────────────────────────────────┐
│ [< Back]  Settings & Security                          │
├────────────────────────────────────────────────────────┤
│ ACTIVE FINANCIAL BOOK                                  │
│ Personal Book (INR ₹ • Asia/Kolkata)                   │
│                                                        │
│ SECURITY & PRIVACY                                     │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 🔒 Biometric App Lock                     [ Toggle ] │ │
│ │    Require fingerprint / face on app launch          │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ ⏱️ Lock Timeout                        [ Immediate ▼]│ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ DATA BACKUP & INTEGRITY                                │
│ ┌────────────────────────────────────────────────────┐ │
│ │ 📦 Export Encrypted Backup                           │ │
│ │    Password-protected AES-256 GCM archive            │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ 📥 Restore From Backup                               │ │
│ │    Validates full ledger balance before restore      │ │
│ ├────────────────────────────────────────────────────┤ │
│ │ 🛠️ Verify Database Invariants                       │ │
│ │    Runs engine integrity test on local Room records  │ │
│ └────────────────────────────────────────────────────┘ │
│                                                        │
│ ABOUT FINTRACK                                         │
│ FinTrack Native Android v1.0.0 (Phase 1 Milestone 2)   │
│ Local SQLite • Deterministic Double-Entry Engine       │
└────────────────────────────────────────────────────────┘
```
