# Nutraherbal Android apps — NH CRM + NH Calling

Is folder me **do Android app** ka poora source hai:

| App | Kaam | Kiske liye |
|---|---|---|
| **NH CRM** (`crm/`) | Aapka poora CRM mobile par app ki tarah (login, clients, orders, tasks, attendance selfie, PDF download) | Poori team |
| **NH Calling** (`calling/`) | Aaj ki call-list, ek click me SIM se call, call ke baad outcome / note / voice note, next follow-up — sab CRM me apne aap | Sales team |

Dono app ek hi CRM server se jude hain. **NH Calling** ko CRM se *6-digit code* se jodte hain (password phone par type nahi karna padta).

## APK kaise banayein (sabse aasan: GitHub, free, laptop par kuch install nahi)
1. github.com par free account banayein → **New repository** (Private rakhein) → naam `nutraherbal-apps`.
2. Is folder ke **andar ki saari files aur folders** (`calling`, `crm`, `.github`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `nh-release.jks`, `.gitignore`) repository me upload karein (*Add file → Upload files*). `.github` folder zaroor jaye.
3. Repository me **Actions** tab → *Build Android apps (APK)* → **Run workflow**. 4–6 minute lagte hain.
4. Hara tick aane par us run me **NH-apks** artifact download karein. Zip me `NH-CRM.apk` aur `NH-Calling.apk` milengi.
5. Agar lal ❌ aaye, to us step ka error text copy karke mujhe bhejein — main theek kar dunga.

*(Dusra tareeka: Android Studio me yeh folder kholein → Build → Build APK(s).)*

## Phone par install
1. APK phone me bhejein (WhatsApp / Drive / USB).
2. APK par tap karein → "Install unknown apps" allow karein → Install.
3. **NH CRM**: kholen → CRM ka address daalein (`https://nutraherbalwellness.com/crm`) → apne email/password se login.
4. **NH Calling**: kholen → permissions allow karein (Phone, Call logs, Microphone).

## NH Calling ko CRM se jodna (auto-connect)
1. CRM me **Calls → My phone → Connect my phone** dabayein → 6-digit code dikhega (10 minute valid).
2. NH Calling me server address (`https://nutraherbalwellness.com/crm`) aur code daalein → **Connect**.
3. Bas. Phone jud gaya. Kisi ka phone gum ho jaye to CRM me **Calls → My phone → Disconnect** se turant band kar sakte hain.

## Roz ka use
- **Today's calls**: aaj ke aur overdue follow-up. **Call** dabayein → normal SIM se call lagti hai.
- Call kaatne ke baad app khul kar poochta hai: *Outcome*, *short note* ya *voice note*, *next follow-up*. **Save** → CRM me client ki history me "Call" entry, duration ke saath, aur next follow-up task ban jata hai.
- App ke bahar (phone ke dialer se) ki gayi ya aayi hui call bhi "Calls not yet saved in the CRM" me dikhti hai (sirf wo numbers jo CRM me client hain) — **Save** karein.
- Internet na ho to call "queue" me rukti hai aur net aate hi upload ho jati hai.
- Manager: CRM → **Calls → Team report** me har agent ki calls, connect %, talk time.

## Zaroori sachchai: recording
Normal SIM call ki **dono taraf ki awaaz** koi bhi app har phone par record nahi kar sakti (Android rokta hai). Isliye yeh version **recording nahi karta**. Iske badle: call log (kitni der baat hui) + agent ka 20–60 second ka **voice note** (Hindi/English/Hinglish). Agle phase me us voice note ko text/summary me badalna (AI transcription) jod sakte hain. Asli recording ke liye pehle aapke phone model par chhota feasibility test chahiye.

## Suraksha
- Phone me password nahi rehta; sirf ek private device token. CRM se kabhi bhi *Disconnect*.
- Har agent sirf apni calls dekhta hai; manager sabhi.
- `nh-release.jks` is app ki signing key hai (password `nutraherbal`). Ye sirf aapki team ke andar ke APK ke liye theek hai. Play Store par daalna ho to nayi private key banayein.
- Customer ko batayein ki call ka record/note rakha ja raha hai (DPDP consent).

## Technical
- Server API: `api/callapp.php` (CRM v3.45+). Pehle CRM ka update-v3.45 zip install karna zaroori hai.
- NH Calling: Kotlin, koi external library nahi. NH CRM: WebView.
