# Nag Killer — monetisation fact sheet

Researched 18 Oct 2026 with web search and page extraction. This is not legal, tax or financial advice. Items marked **[UNVERIFIED]** come from search snippets only, or I could not confirm them on the primary page.

---

## 1. Demand

### Owner complaints (quotes with sources)

- **Drive.com.au, 18 Oct 2025: "BYD, GWM owners using covers to silence 'annoying' safety features."** https://www.drive.com.au/news/byd-gwm-owners-using-covers-to-silence-annoying-safety-features/
  - Covers sell on eBay for "$5 to $16.99" (Shark 6). One website charges "$35" for a cover it says also fits the Sealion 7.
  - An eBay review quoted in the article: *"Works instantaneously and stops the driver being continuously monitored by 'BYD big brother'… I was getting over, at every roundabout, being reprimanded for taking my eyes off the road."*
- **Whirlpool, BYD Sealion 7 EV thread.** I only have the search snippets; the page failed to load. **[UNVERIFIED wording]**
  - https://forums.whirlpool.net.au/archive/3vw46lj5-4: *"It's widely viewed that the driver attention/monitor is overzealous and distracting (not just BYD but many brands)."*
  - https://forums.whirlpool.net.au/thread/3vw46lj5?p=121: *"No new car sold in 2025 can have these ADAS stuff permanently disabled… I have a routine to turn off ELKA, LKA, AEB, driver monitoring and child monitoring every time…"*
- **Reddit.** Pages blocked the fetch, so these are snippets only. **[UNVERIFIED wording]**
  - r/BYD "ADAS reliability and shortcuts with Sealion 7", https://www.reddit.com/r/BYD/comments/1jp7z47/ : *"I have a sealion 7 in aus, and I need to turn the Lane Keeping Assist off every time I start the car… The drive monitoring you takes 2 or 3 [clicks]."*
  - r/BYD "BYD Tasker Automation", https://www.reddit.com/r/BYD/comments/1ka70zc/ : *"…wanting to find out the best way to turn off Emergency lane keeping assist, SLW, DMS and the child monitor every time I start the car… wondering if there's a quick and easy permission we could set via adb…"* This person is asking for exactly what Nag Killer does.
  - r/BYD "BYD Sealion 7 Driver Monitoring System Camera Cover?", https://www.reddit.com/r/BYD/comments/1mky4bd/ : *"…something I really don't like is the driver monitoring camera (and how it turns on every time you start…"*
  - r/BYDShark_AUS_owners "How to turn off driver monitoring?", https://www.reddit.com/r/BYDShark_AUS_owners/comments/1kiwc63/ : *"I am getting reminders to watch the road constantly. Even for a quick air con or music change."*
  - r/AustralianEV (GWM Ora 5), https://www.reddit.com/r/AustralianEV/comments/1wn1soz/ : *"…acts like my mother… Also can't be tuned or permanently disabled."*
- **"Would pay a serious amount for auto DMS off" posts: NOT FOUND.** Several searches turned up no post explicitly saying the writer would pay. Don't quote such a post unless the owner has the link. The closest evidence that people will pay comes from existing paid products: A$5–55 covers and the A$149 BaoLab service (see §2).
- **Industry acknowledgement.**
  - ANCAP's 2026 protocols penalise "intrusive or poorly calibrated" systems (CarExpert): https://www.carexpert.com.au/car-news/2026-ancap-safety-protocols-are-here-what-you-need-to-know
  - NRMA says "thousands of Aussie drivers are switching off ADAS features": https://www.mynrma.com.au/open-road/advice-and-how-to/road-safety/does-switching-off-adas-features-affect-your-car-insurance

### Sealion 7 sales in Australia

| Period | Figure | Source |
|---|---|---|
| Launch | Feb 2025 | CarExpert (below) |
| CY2025 | **13,410** (BYD AU total 52,415; Shark 6 18,073) | https://www.carexpert.com.au/car-news/byd-sealion-7-just-outside-top-three-in-december-sales-tidal-wave |
| Dec 2025 | 2,547 | same |
| Apr 2026 | 1,780 [snippet] | https://www.carsguide.com.au/car-news/byd-sales-skyrocket-in-australia-with-the-byd-sealion-7-the-best-selling-ev-as-2026-toyota |
| Jun 2026 | 4,730; ">25,000 since launch" [snippet] | https://thedriven.io/2026/08/02/byd-slashes-price-of-sealion-7-premium-model-as-it-chases-down-toyota-to-be-countrys-best-selling-car-company/ |
| Sep 2026 | 2,687 [snippet] | https://www.carexpert.com.au/car-news/vfacts-september-2026-evs-beat-petrol-diesel-models-once-again-in-new-vehicle-sales |

Rough installed base: about 30k+ Sealion 7s in Australia by Oct 2026. This is my extrapolation, not a published figure.

### Other BYD models that may be addressable

- Drive / OZI4X4: the Shark 6 and Sealion 7 use "a similar driver monitoring system".
- BaoLab's service covers BYD and Denza cars running the "5.0 UI or newer (often searched for as 'DiLink 5+')": https://shop.baolab.au/products/adb-unlocking-service
- Recent Australian launches: Atto 1, Atto 2, Sealion 5 and Sealion 8 (CarExpert link above).
- **[UNVERIFIED]** Whether each of these models has a camera DMS and uses the same DMS service/package as the Sealion 7. Test per model before claiming compatibility.

---

## 2. Price precedents

| Product | What it does | Price | Source |
|---|---|---|---|
| **BaoLab** remote ADB enablement | Remote session to enable ADB on BYD/Denza (DiLink 5+ UI); 6-month re-activation; money-back guarantee | **A$149** standard, **A$169** priority, **A$229** "Bring a Mate" (2 cars), GST-inclusive | https://shop.baolab.au/products/adb-unlocking-service |
| BaoLab disclaimer | Says the service does **not** include "changing safety systems"; warns it may affect "warranty, servicing, diagnostics, software updates" | — | same |
| **Beepaway** | VIN-specific USB config that mutes ISA/TSR audio and says it also mutes "Driver Drowsiness and Attention Warning and Lane Keep Assist" on BYD (incl. Sealion 7); visual alerts stay on | **Free** (account required). I saw no paid tier on the Sealion 7 page | https://beepaway.com/manufacturers/byd/sealion-7 |
| **OverDrive** (BYD sentry/dashcam app) | Open source under MIT, sideloaded | "**Free Forever**"; has a GitHub Sponsors section | https://github.com/yash-srivastava/Overdrive-release |
| DMS camera covers (eBay) | Plastic cover for the camera | A$5–16.99 | Drive article above |
| OZI4X4 DMS cover | Same | A$35 (listed down from A$55) [snippet] | https://ozi4x4australia.com.au/collections/byd-sealion-7-2025 |
| SimuVerse Sealion 7 DMS cover | 2-pack, PETG-CF | price not captured | https://simuverse.au/products/byd-sealion-7-dms-cover |
| **Autopilot Buddy** (Tesla, US, 2018) | Steering-wheel weight to defeat the hands-on nag | US$199 | https://www.theverge.com/2018/6/19/17479316/tesla-autopilot-buddy-aftermarket-nhtsa |

**What this suggests:** people already pay A$149+ for ADB access alone and A$5–55 for a physical DMS cover. A free competitor (Beepaway) already mutes some DMS audio on BYD.

---

## 3. Business models for selling builds of open-source software

- **MIT licence** (https://opensource.org/license/mit): grants anyone the right "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software". The only condition is keeping the copyright and permission notice.
  - So under MIT, **anyone (including BaoLab or others) may also build and sell Nag Killer.** You can't stop them through the licence. Only trademark (the name/logo) or the convenience of your service sets you apart.
  - MIT also disclaims all warranty ("AS IS"). Whether that disclaimer holds against Australian Consumer Law for paying Australian buyers is a separate question; see §5.
- **Aseprite** (https://www.aseprite.org/faq/): source is public but under a proprietary EULA. You may compile it for personal use, but "you cannot redistribute Aseprite to third parties". Paying gets you signed installers. This is **source-available, not MIT**. It only works because the EULA forbids redistribution.
- **Ardour** (GPL): source is free; the official pre-built binaries need a payment (minimum about US$1, or a subscription).
  - https://tylerdavis.xyz/linux/ardour-provides-a-good-model-for-profiting-off-of-foss/
  - https://discourse.ardour.org/t/the-paywall-and-related-matters/105989 (this thread shows users mirroring the binaries for free, which the GPL allows)
- **Patterns that fit an MIT project:**
  1. Sell convenience: a one-click installer, a signed APK, updates, support, and a guarantee like BaoLab's re-activation. The code stays MIT.
  2. Pay-what-you-want or a minimum price on the binary (Ardour style). Lemon Squeezy has a PWYW feature (pricing page footer).
  3. Donations or sponsorships (OverDrive uses GitHub Sponsors).
  4. Changing only the installer's licence (e.g. to source-available) would be possible for code the owner fully owns. Code already released under MIT stays MIT.

---

## 4. Payment platforms (A$5–20 digital download, seller in Australia)

| Platform | Fee (headline) | Merchant of Record (handles GST/VAT)? | Licence keys | Notes | Source |
|---|---|---|---|---|---|
| **Lemon Squeezy** | 5% + 50¢ (+ surcharges for international/PayPal per 3rd-party reviews) | Yes | Yes: License API (activate/validate/deactivate, activation limits) | Owned by Stripe since 2024. A Jan 2026 CEO post admits "slower support responses" while the team builds Stripe Managed Payments. Prohibited list includes "services of any kind" and anything restricted by Stripe | https://www.lemonsqueezy.com/pricing · https://www.lemonsqueezy.com/blog/2026-update · https://docs.lemonsqueezy.com/api/license-api · https://docs.lemonsqueezy.com/help/getting-started/prohibited-products |
| **Gumroad** | 10% + 50¢ direct (30% via Discover) | Yes, since 1 Jan 2025 | Yes: `/v2/licenses/verify` API | Prohibits "illegal or illicit goods" | https://gumroad.com/pricing · https://gumroad.com/help/article/76-license-keys |
| **Paddle** | 5% + 50¢; says to contact them about "bespoke pricing" for products under $10 | Yes | Built-in licensing **[UNVERIFIED]** | AUP bans "Human services" and anything that "Results in or causes a significant risk of… harm and liability"; does extra due diligence on restricted categories. A product that disables a safety system could fail this review **[judgement, unverified]** | https://www.paddle.com/pricing · https://www.paddle.com/help/start/intro-to-paddle/what-am-i-not-allowed-to-sell-on-paddle |
| **Stripe Payment Links** (direct) | 1.65% + A$0.30 domestic, 3.5% + A$0.30 international (page as of Oct 2026) | **No.** You are the merchant, so you handle GST yourself (Stripe offers Stripe Tax and a separate "Managed Payments" MoR product) | No native keys; build your own | Cheapest per sale | https://stripe.com/AU/pricing · https://docs.stripe.com/payments/managed-payments |
| **Ko-fi** | Shop: 0% + processor fees with Gold (US$12/mo), otherwise 5% + processor fees | Pays out to your PayPal/Stripe; MoR status **[UNVERIFIED, likely not MoR]** | No native licence API **[UNVERIFIED]** | Simple tip jar or shop | https://ko-fi.com/pricing |
| **GitHub Sponsors** | 0% on sponsorships from personal accounts; up to 6% from organisation accounts | Not a store | No | Supports one-time payments. Suits donations, not gated downloads | https://docs.github.com/en/sponsors/sponsoring-open-source-contributors/about-sponsorships-fees-and-taxes |

**Fees on an A$10 sale (approximate):**
- Lemon Squeezy / Paddle: about A$1.00 (≈10%), more if the 50¢ is charged in USD.
- Gumroad: about A$1.50 (≈15%).
- Stripe domestic: about A$0.47 (≈4.7%), but GST is then yours to handle.

**GST and ABN:**
- GST registration is required once GST turnover reaches **A$75,000** in a 12-month period. You must register within 21 days of knowing you'll exceed it.
  - https://www.ato.gov.au/businesses-and-organisations/gst-excise-and-indirect-taxes/gst/registering-for-gst
  - https://business.gov.au/registrations/register-for-taxes/register-for-goods-and-services-tax-gst
- With an MoR, the platform is the seller of record to buyers and collects the tax.
- **[UNVERIFIED / ask an accountant]:**
  - whether the MoR payouts count towards your own GST turnover;
  - whether an ABN is required. An ABN is generally needed if you are "carrying on an enterprise", and is a prerequisite for GST registration. A hobby may not need one. The MoR platforms mainly ask for a tax form (e.g. W-8BEN) and a bank account.

---

## 5. Legal and liability facts (not advice)

### Is DMS mandatory?

- **Australia, ADRs: not a legal requirement.** Drive.com.au (Oct 2025) states: *"Driver monitoring systems are not a legal requirement in new vehicles, and can be freely turned off via the touchscreen, so covering the cameras… is not understood to be illegal."* (link in §1)
  - The ADRs approved in 2026 cover Advanced Emergency Braking (ADR 98/01, 98/02) and Emergency Lane Keeping (ADR 107/00), not DMS: https://igarr.com/2026/04/07/australia-new-active-safety-system-requirements/ · https://igarr.com/2026/03/11/australia-updates-of-several-australian-design-rules-adrs/
  - **[UNVERIFIED]** I did not find a primary-source confirmation that no ADR covers DMS (e.g. the Federal Register of Legislation). Check before relying on it.
- **ANCAP: needed for top scores, and must default on.**
  - ANCAP CEO Carla Hoorweg (CarsGuide, on the Deepal S07 OTA update): *"ANCAP protocols require DMS to default on at the start of each journey and remain functional whenever the vehicle is in forward motion at speeds of 10km/h or above."*
  - Letting DMS stay off after the next ignition cycle "would be inconsistent with ANCAP protocols and may have an effect on the published safety rating."
  - Source: https://www.carsguide.com.au/car-news/exclusive-chinese-brands-safety-switch-off-clipped-by-ancap-2026-deepal-s07s-five-star
  - ANCAP's 2023+ protocols reward a direct (camera) DMS in Safety Assist: https://www.ancap.com.au/media-and-gallery/media-releases/mitsubishi-triton-lines-up-as-next-dual-cab-to-offer-five-star-safety
  - The 2026 protocols also penalise intrusive systems (CarExpert link in §1).
  - ANCAP's statement on aftermarket workarounds: *"ANCAP strongly advises against modifications that disable or interfere with a vehicle's built-in safety systems, including driver monitoring systems."* (Drive link in §1)
  - Nag Killer does what OEM Deepal did by OTA, and ANCAP publicly questioned that. This is the main reputational risk.
- **EU, GSR (Reg. (EU) 2019/2144):**
  - Driver Drowsiness and Attention Warning (DDAW): required on new vehicle types from 6 Jul 2022 and on all new vehicles from 7 Jul 2024.
  - Advanced Driver Distraction Warning (ADDW): required on new types from 7 Jul 2024 and on all new vehicles from 7 Jul 2026.
  - Sources: https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=PI_COM%3AC%282023%294523 · https://eur-lex.europa.eu/legal-content/EN/TXT/HTML/?uri=PI_COM%3AAres%282021%291075107
  - These are requirements on manufacturers and type approval. Rules on aftermarket defeat devices in EU member states were not researched **[gap]**.

### Australian Consumer Law

- The ACL definition of "goods" expressly includes computer software:
  - https://www.hsfkramer.com/insights/2016-04/the-long-arm-of-the-australian-consumer-law-reaches-offshore
  - https://theconversation.com/australias-consumer-laws-still-dont-cover-e-books-and-many-other-digital-products-91831
- Consumer guarantees apply to goods sold to consumers: acceptable quality, fit for any disclosed purpose, matching their description. https://www.accc.gov.au/consumers/buying-products-and-services/consumer-rights-and-guarantees
- These guarantees generally can't be excluded by a licence disclaimer such as MIT's "AS IS" **[UNVERIFIED for this specific case]**.
- With an MoR (Lemon Squeezy, Paddle, Gumroad), the platform is the legal seller to the buyer. How ACL liability then splits between platform and developer was not researched **[gap]**.
- The ACCC also has product-safety powers (warnings, bans, recalls) over consumer goods. Whether these have ever been used on software that disables vehicle features: **not found**.

### Warranty and insurance

- **BYD Australia Warranty & Service Handbook (2025)** excludes "Modifications, additions or alterations (including to software)… which result in damage to, or failure of, the vehicle" and "Damage from unauthorized modifications or third-party conversions." https://bydautomotive.com.au/public/vehicle-warranty-2025.pdf
  - Note the wording: the exclusion covers damage *resulting from* modifications. It is not a blanket warranty void.
  - BaoLab separately warns that ADB and third-party apps "may affect warranty".
- **Insurance.** NRMA Open Road says switching off ADAS features does not affect your insurance; insurers it contacted said vehicle data isn't used in claims. https://www.mynrma.com.au/open-road/advice-and-how-to/road-safety/does-switching-off-adas-features-affect-your-car-insurance
  - That article covers turning features off through the car's own menus. Undisclosed *modifications* can be grounds to refuse a claim (Budget Direct): https://www.budgetdirect.com.au/car-insurance/articles/modified-car-insurance.html
  - Whether a sideloaded app counts as a "modification" for insurance purposes: **[UNVERIFIED / no source found]**.

### Enforcement precedents

- **NHTSA vs Autopilot Buddy (US, 19 Jun 2018).** NHTSA sent a cease-and-desist letter. The device was "Marketed as a 'Tesla autopilot nag reduction device'". NHTSA said aftermarket devices "are motor vehicle equipment regulated by NHTSA". The company had to certify by 29 Jun 2018 that "all U.S. marketing, sales, and distribution" had ended. Quote: "A product intended to circumvent motor vehicle safety and driver attentiveness is unacceptable." https://www.nhtsa.gov/press-releases/consumer-advisory-nhtsa-deems-autopilot-buddy-product-unsafe
  - This involved a physical device sold in the US. Whether US authority would reach software sold from Australia was not researched.
- **Australia:** no lawsuit, warning or regulator action against sellers of DMS covers or nag-defeat software was found. Sellers of the covers add "off-road and track use only" disclaimers (Drive).

---

## Gaps and next checks

1. Find a real "would pay" owner post (Facebook groups such as BYD Sealion 7 Owners Australia weren't searchable).
2. Confirm on legislation.gov.au that no ADR or in-service state rule (e.g. QLD vehicle standards regulations) requires DMS to stay working.
3. Before launch, ask Lemon Squeezy, Paddle and Gumroad in writing whether a "disables driver monitoring" product is allowed.
4. Get professional advice on ACL liability under an MoR, the ABN/GST position, and product-liability insurance.
