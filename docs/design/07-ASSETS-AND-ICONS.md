# Asset & Iconography Specifications
**Document 07 • FinTrack Mobile Design System**
*Vector Drawables • 24dp Optical Grid • Category Mapping • Adaptive App Icon*

---

## 1. Iconography System Rules

FinTrack uses a unified, geometric icon system based on **Material Symbols (Rounded Style)** or **Lucide Icons**, ensuring clean line weights and legible silhouette profiles on mobile displays.

### 1.1 Geometry & Optical Grid
- **Bounding Box:** $24 \times 24\,\text{dp}$ canvas.
- **Live Area:** $20 \times 20\,\text{dp}$ centered active area with a mandatory $2\,\text{dp}$ optical padding gutter.
- **Stroke Width:** Uniform $2.0\,\text{dp}$ stroke with rounded corner caps (`strokeLineCap="round"` and `strokeLineJoin="round"`).
- **Format:** Native Android XML Vector Drawables (`VectorDrawable`) or Compose `ImageVector` objects to eliminate raster pixelation across all display densities.

---

## 2. Category & Account Icon Mapping Table

| Semantic Concept | Compose Vector / Material Symbol | Meaning in FinTrack |
| :--- | :--- | :--- |
| **Bank Account** | `Icons.Outlined.AccountBalance` | Institutional checking/salary account |
| **Cash Wallet** | `Icons.Outlined.Payments` | Physical paper cash in pocket/safe |
| **Savings Reserve** | `Icons.Outlined.Shield` | Non-negotiable emergency cushion |
| **Investment Asset** | `Icons.Outlined.TrendingUp` | Tracked mutual fund/stocks portfolio |
| **Housing & Rent** | `Icons.Outlined.Home` | Monthly rent, property tax, maintenance |
| **Groceries** | `Icons.Outlined.ShoppingBasket` | Supermarket supplies and household food |
| **Dining Out** | `Icons.Outlined.Restaurant` | Restaurants, cafes, food delivery |
| **Utilities & Bills** | `Icons.Outlined.Bolt` | Electricity, water, gas, broadband |
| **Transportation** | `Icons.Outlined.DirectionsCar` | Fuel, public transit, cab rides |
| **Healthcare** | `Icons.Outlined.MedicalServices` | Doctor visits, pharmacy, insurance |
| **Salary / Earnings** | `Icons.Outlined.Work` | Professional fees, monthly salary |
| **Transfer** | `Icons.Outlined.SyncAlt` | Balanced movement between owned accounts |
| **Cleared Status** | `Icons.Filled.CheckCircle` | Fully reconciled with bank statement |
| **Pending Status** | `Icons.Outlined.Schedule` | In-flight cheque or card authorization hold |
| **Warning / Collision** | `Icons.Filled.Warning` | Funding shortfall, priority goal collision |

---

## 3. Density Bucket Coverage & Asset Packaging

While vector drawables scale automatically across Android screens, any embedded brand illustrations, splash icons, or mipmap assets must support standard Android density buckets:

| Bucket | Density Multiplier | Screen Resolution Example | Splash / App Icon Size |
| :--- | :--- | :--- | :--- |
| **mdpi** | $1.0\times$ ($160\,\text{dpi}$) | $320 \times 480\,\text{px}$ | $48 \times 48\,\text{px}$ |
| **hdpi** | $1.5\times$ ($240\,\text{dpi}$) | $480 \times 800\,\text{px}$ | $72 \times 72\,\text{px}$ |
| **xhdpi** | $2.0\times$ ($320\,\text{dpi}$) | $720 \times 1280\,\text{px}$ | $96 \times 96\,\text{px}$ |
| **xxhdpi** | $3.0\times$ ($480\,\text{dpi}$) | $1080 \times 1920\,\text{px}$ | $144 \times 144\,\text{px}$ |
| **xxxhdpi** | $4.0\times$ ($640\,\text{dpi}$) | $1440 \times 2560\,\text{px}$ | $192 \times 192\,\text{px}$ |

---

## 4. Adaptive App Icon & Splash Screen Specification

FinTrack implements Android 12+ Adaptive Icon specifications (`ic_launcher.xml`):

### 4.1 Icon Anatomy
- **Background Layer:** Deep obsidian slate (`#111415`) or forest emerald (`#196852`).
- **Foreground Layer:** Interlocking geometric vault leaf in bright mint (`#48D597`) with subtle inner gold accent (`#FFBA28`).
- **Safe Zone:** The active brand symbol resides strictly within a centered $66\,\text{dp}$ circular safe zone on the $108 \times 108\,\text{dp}$ canvas to prevent mask clipping across Samsung squircle, Google circle, and standard rounded square launchers.

### 4.2 System Splash Screen (`core-splashscreen`)
- Background color matches theme `LightBackground` (`#F8F9FA`) or `DarkBackground` (`#111415`).
- Center branding displays the FinTrack leaf symbol ($160 \times 160\,\text{dp}$) with zero branded splash delay, fading out instantly into `/home` when local Room database storage initializes.
