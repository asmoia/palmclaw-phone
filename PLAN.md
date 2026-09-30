# PalmClaw Phone — نسخه‌ی ادغام‌شده

**یک اپ:** PalmClaw (عامل + UI + مهارت‌ها) + کنترل کامل گوشی (پورت‌شده از apkmcp، به‌صورت داخلی — بدون HTTP و بدون MCP).

```
PalmClaw (UI، حلقه‌ی agent، skills، providers)
   ├── tools/ ... ابزارهای داخلی خودش (web_search، فایل، تقویم، ...)
   └── phonecontrol/  ← ماژول جدید (پورت apkmcp)
        ├── control/AgentAccessibilityService + UiTreeReader   (چشمان و دست‌ها)
        ├── capture/ScreenCaptureService                        (اسکرین‌شات، اختیاری)
        ├── enhance/ShizukuEnhance + ShellService               (اجرای مطمئن اپ روی EMUI، اختیاری)
        ├── core/Prefs + Logs                                   (تنظیمات ماژول)
        └── server/ToolRegistry + PhoneControlTools             (۲۲ ابزار + آداپتر به Tool اینترفیس PalmClaw)
```

## چه شد؟ (خلاصه‌ی تغییرات)

| فایل | تغییر |
|---|---|
| `phonecontrol/**` (۹ فایل Kotlin + ۱ AIDL) | پورت مستقیم از apkmcp با تغییر پکیج به `com.palmclaw.phonecontrol` |
| `tools/ToolCatalog.kt` | `addAll(createPhoneControlToolSet())` — ابزارها مستقیم در رجیستری agent |
| `PalmClawApplication.kt` | init کردن `ApkMcpApp.appContext` |
| `AndroidManifest.xml` | پرمیشن‌های SYSTEM_ALERT_WINDOW / FOREGROUND_SERVICE_MEDIA_PROJECTION / QUERY_ALL_PACKAGES + دو سرویس + Shizuku provider |
| `res/xml/accessibility_service_config.xml` + strings | کانفیگ Accessibility با ارجاع به MainActivity خود PalmClaw |
| `app/build.gradle.kts` | minSdk 24→26 (کد apkmcp API 26+) + وابستگی‌های Shizuku |
| `.github/workflows/build-apk.yml` | بیلد APK با push — خروجی در Artifacts |

## ابزارهای جدید agent (همان اسم‌های skill قبلی — بدون تغییر)

`get_ui_tree`, `find_and_tap`, `tap`, `long_press`, `swipe`, `scroll`, `type_text`,
`press_key`, `home`, `back`, `recents`, `notifications`, `quick_settings`,
`launch_app`, `list_apps`, `current_app`, `open_url`, `screenshot`, `screen_size`,
`get_status`, `wait`

> یعنی skill به‌نام `phone-control` که قبلاً ساختی **بدون هیچ تغییری** کار می‌کنه.
> لایه‌ی MCP کلاً حذف شده — ابزارها صدا‌به‌صدا داخل همون پروسه اجرا می‌شن (سریع‌تر و پایدارتر از localhost HTTP).

## نحوه‌ی استفاده

1. push به ریپوی خودت → Actions → دانلود APK از Artifacts
2. نصب → مجوز Accessibility برای «PalmClaw Phone Control»
3. (اختیاری، مخصوص EMUI) نصب Shizuku → `launch_app` بدون محدودیت background-start
4. Provider همون Worker فعلی (`https://palmclaw-ai.heminacearbi.workers.dev/v1`)

## فاز ۲ — ✅ پیاده‌سازی و تست شد (DuckProvider)

### پروتکل (کامل reverse-engineer و روی سرور واقعی تست شد)

```
GET  /duckchat/v1/status   (x-vqd-accept: 1)  → هدر X-Vqd-Hash-1 = چالش (base64 JS)
حل چالش: atob → اجرا در iframe → SHA-256(client_hashes) → base64(JSON با meta{origin,stack,duration})
POST /duckchat/v1/chat     (x-vqd-hash-1: <حل‌شده>)  → SSE {message:"…"} + چالش جدید برای نوبت بعد
```

- فرمت پیام: user `{role,content:[{type:"text",text}]}` — assistant `{role,content:"",parts:[{type:"text",text}]}`
- body: `{model, messages, canUseTools:true, reasoningEffort:"none", …}`
- خطاها: 418 ERR_CHALLENGE / 429 RATE_LIMIT → استراتژی تأییدشده: صبر ۳ ثانیه + چالش تازه از status + retry (تا ۳ بار)

### نتایج تست زنده (مرورگر واقعی، همین پروژه سندباکس)

| تست | نتیجه |
|---|---|
| چت تک‌نوبته | ✅ 200 + جواب صحیح |
| Multi-turn با فرمت `parts` | ✅ حافظه کار کرد (سوال عدد ۴۲ → «42») |
| فارسی | ✅ «تهران، پایتخت پرجنب‌وجوش ایران…» |
| Tool-call متنی: «برو تلگرام رو باز کن» | ✅ `{"tool_calls":[{"name":"launch_app","arguments":{"app":"تلگرام"}}]}` |
| ادامه بعد از TOOL RESULT | ✅ «تلگرام باز شد.» |
| سوال ساده | ✅ متن ساده بدون JSON |
| پارسر Kotlin (۷ حالت) | ✅ ۷/۷ |
| حالت headless | ❌ 418 — چون fingerprint هدلس در چالش لو می‌ره → روی اندروید WebView واقعیه و مشکلی نداره |

### فایل‌های فاز ۲

| فایل | نقش |
|---|---|
| `assets/duck_bridge.js` | ترنسپورت تزریق‌شده در WebView: حل چالش JSA + POST چت + SSE + retry (دقیقاً همون کد تست‌شده) |
| `providers/duck/DuckWebViewBridge.kt` | WebView مخفی singleton + JavaScriptInterface برای دلتا/نتیجه + بازیابی از خطا |
| `providers/duck/DuckProvider.kt` | پیاده‌سازی LlmProvider + پروتکل tool-calling متنی + مدیریت کانتکست |
| `ProviderProtocol.kt` / `ProviderCatalog.kt` / `ProviderEndpointPlanner.kt` / `AdaptiveLlmProvider.kt` | افزودن پروتکل DuckAi + پروفایل «Duck.ai (free)» + delegate |

### مدیریت محدودیت‌های duck.ai (همون‌هایی که خودت تجربه کردی)

- **«کانتکس رو فراموش می‌کنه»** → DuckProvider فقط ۱۶ پیام آخر رو می‌فرسته + نتیجه‌ی ابزارها به ۸k کاراکتر و پیام‌ها به ۲۰k کاراکتر clip می‌شن (درخت UI صفحه‌های بزرگ دیگر کل کانتکست رو نمی‌خورن)
- **محدودیت نرخ anonymous** → فاصله‌ی اجباری ۳.۵ ثانیه بین درخواست‌ها + retry با چالش تازه (تا ۳ بار)
- **مدل‌ها**: `gpt-5.6-luna` (پیش‌فرض)، `gpt-5.6-sol`، `gpt-5.6-terra`، `gpt-5.1-thinking`، `gpt-4o-mini`، `claude-haiku-4-5`

### استفاده

Settings → Provider → **Duck.ai (free)** — بدون API Key، بدون لاگین. (اگر فیلد Key خالی نپذیرفت، هر متنی بگذار؛ نادیده گرفته می‌شود.) fallback هم می‌شود زد: Provider دیگری (Worker کلودفلر) را به‌عنوان provider دوم نگه دار و در صورت خطا سوییچ کنی.

## کارهای باقی‌مانده (به ترتیب)

- [ ] بیلد اول در Actions و رفع خطاهای احتمالی کامپایل (طبیعی است — پورت بدون بیلد محلی انجام شد)
- [ ] فاز ۲: DuckProvider با WebView bridge
- [ ] اضافه‌کردن descriptor ابزارهای phonecontrol به BuiltInToolCatalog (تا در UI تنظیمات قابل خاموش/روشن شدن باشند)
- [ ] راهنمای EMUI: خاموش‌کردن بهینه‌سازی باتری + Auto-launch برای پایداری پس‌زمینه
- [ ] امضای release (فعلاً debug کافیه)

## فاز ۲.۵ — مقاوم‌سازی (همه روی کد نهایی تست شد)

| حالت شکست | راه‌حل | وضعیت تست |
|---|---|---|
| Rate-limit (418/429) | pacing ۳.۵s + backoff نمایی + چالش تازه در هر retry (تا ۵ بار) | ✅ بازیابی پس از طوفان واقعی |
| ورودی بیش از حد (429 ERR_INPUT_LIMIT) | بودجه‌ی کل ۹k کاراکتر + حذف قدیمی‌ها (جدیدترین‌ها می‌مانند) + نردبان بودجه ۹k→۶k→۴k→۲.۵k؛ JS این خطا را retry نمی‌کند | ✅ شبیه‌سازی ۳ سناریو |
| امتناع مدل از tool call | few-shot instructions (۷۵٪) + escalation corrective turn | ✅ ۳/۴ مستقیم + escalation |
| عدم پایداری/قطعی duck | لایه‌ی ۳: fallback روی Worker کلودفلر با tool call بومی (llama-3.3-70b) | ✅ تست‌شده در فاز Worker |
| گم شدن کانتکست در مکالمه بلند | پنجره‌ی ۱۶ پیام + clip + فشرده‌سازی فاصله‌ها | ✅ ۱۲ نوبت، صفر خطا، حافظه ✓ |
| قطع استریم وسط کار | partial text برمی‌گردد (partial:true) به‌جای خطا | ✅ پیاده‌سازی شد |
| درخواست معلق/hang | AbortController در JS (۱۵۰s) + watchdog در Kotlin (۱۹۰s) | ✅ |
| کال‌های همزمان | Mutex در Kotlin + صف در JS | ✅ تست parallel: هر دو پاسخ درست |
| reload صفحه/مرگ WebView | __duckPing + تزریق مجدد bridge + reload | ✅ تست reload: بازیابی کامل |
| فقط gpt-5.6-luna بدون لاگین در دسترس است (sol/terra/gpt-5.1-thinking/gpt-4o-mini → 404) | لیست مدل‌ها به همان محدود شد | ✅ |
| پاسخ خالی مدل | retry ملایم + در نهایت fallback | ✅ |

## فاز ۳ — Keep-alive بکگراند (تغییر کوچک، فایل‌های دست‌نخورده از قبل)

| تغییر | فایل | توضیح |
|---|---|---|
| **جدید** | `runtime/AgentKeepAlive.kt` | object (شمارنده‌ی busy + linger ۹۰s + debounce) + `AgentKeepAliveService` (FGS specialUse + WakeLock + پیل شناور قابل‌درگ «● PalmClaw» با tap→بازگشت به چت) + `ensurePermissions` (overlay + battery-exempt + POST_NOTIFICATIONS، یک‌بار per install) |
| هوک | `AdaptiveLlmProvider` | chat()/chatStream() → مرزهای busy (تنها مسیر همه‌ی LLM call ها از همین می‌گذرد) |
| هوک | `MainActivity.onCreate` | `ensurePermissions` یک‌بار اجرا |
| فیکس | `AgentAccessibilityService` | `bestRoot()`: اگر پنجره‌ی فعال خود PalmClaw بود، از لیست windows ریشه‌ی اپ دیگر انتخاب می‌شود → get_ui_tree دیگر UI خود اپ را کپچر نمی‌کند |
| منیفست | سرویس جدید | foregroundServiceType=specialUse (پرمیشن‌ها از قبل موجود بودند) |

رفتار: اپ در بکگراند + ایجنت مشغول → نوتیف + پیل شناور + ویک‌لاک؛ ایجنت idle ≥۹۰s → همه‌چیز خودش خاموش می‌شود.

## نکته‌ی لایسنس

PalmClaw تحت **AGPL-3.0** و apkmcp تحت **MIT** است — استفاده‌ی شخصی آزاد؛ اگر APK را منتشر کردی سورس را هم باید در دسترس بگذاری.
