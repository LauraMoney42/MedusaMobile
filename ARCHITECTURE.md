# Medusa Mobile — Architecture Plan

> mm-003 | Dev2 | 2026-04-04
> Branding: Dark green (#1B5E20), electric green (#00E676), glassy frosted cards

---

## System Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         UI LAYER                                │
│  Jetpack Compose + Material 3 + Medusa Dark Green Theme         │
│                                                                 │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐   │
│  │ChatScreen│ │SettingsS.│ │PermissS. │ │ToolChips/Bubbles │   │
│  └────┬─────┘ └──────────┘ └──────────┘ └──────────────────┘   │
│       │                                                         │
│  ┌────▼──────────────────────────────────────────────────────┐  │
│  │              ChatViewModel (StateFlow)                     │  │
│  │  messages: List<UIMessage>  |  isThinking: Boolean         │  │
│  │  inputText: String          |  toolCalls: List<UIToolCall> │  │
│  └────┬──────────────────────────────────────────────────────┘  │
└───────┼─────────────────────────────────────────────────────────┘
        │
┌───────▼─────────────────────────────────────────────────────────┐
│                      AGENT LAYER                                │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │              AgentOrchestrator                           │    │
│  │  Drives the agentic loop:                               │    │
│  │    1. Send user message → Claude (streaming)            │    │
│  │    2. If stop_reason = "tool_use" → dispatch to tools   │    │
│  │    3. Inject tool_result → send back → repeat           │    │
│  │    4. If stop_reason = "end_turn" → done                │    │
│  └────┬────────────────────┬───────────────────────────────┘    │
│       │                    │                                    │
│  ┌────▼──────┐     ┌──────▼──────────────────────────────┐     │
│  │ ClaudeApi │     │          ToolDispatcher              │     │
│  │ Service   │     │  switch(name) → route to Tool class  │     │
│  │ (OkHttp   │     │  Serializes ToolResult → JSON        │     │
│  │  SSE)     │     │  Reports status back to ViewModel    │     │
│  └───────────┘     └──────┬───────────────────────────────┘     │
└────────────────────────────┼────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                       TOOL LAYER                                │
│                                                                 │
│  ┌──────────┐ ┌────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │ SmsTool  │ │CallHistory │ │Notification │ │ ContactsTool│  │
│  │ (mm-006) │ │Tool(mm-007)│ │Tool(mm-008) │ │             │  │
│  │ READ_SMS │ │READ_CALL   │ │Listener     │ │READ_CONTACTS│  │
│  └──────────┘ └────────────┘ └─────────────┘ └─────────────┘  │
│  ┌──────────┐ ┌────────────┐ ┌─────────────┐ ┌─────────────┐  │
│  │Calendar  │ │ Location   │ │ AppLauncher │ │  WebTool    │  │
│  │Tool      │ │ Tool       │ │ Tool        │ │             │  │
│  │R/W_CAL   │ │FINE_LOC    │ │Intent.ACTION│ │ OkHttp GET  │  │
│  └──────────┘ └────────────┘ └─────────────┘ └─────────────┘  │
│  ┌──────────┐ ┌────────────┐ ┌─────────────┐                  │
│  │ FileTool │ │AccessibSvc │ │  MailTool   │                  │
│  │          │ │(Phase 3)   │ │ Intent.SEND │                  │
│  │Storage   │ │UI taps     │ │             │                  │
│  └──────────┘ └────────────┘ └─────────────┘                  │
└─────────────────────────────────────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────────┐
│                    PERSISTENCE LAYER                            │
│                                                                 │
│  ┌──────────────────┐  ┌────────────────┐  ┌────────────────┐  │
│  │  Room Database   │  │  DataStore     │  │  EncryptedSP   │  │
│  │  - conversations │  │  - settings    │  │  - API key     │  │
│  │  - memories      │  │  - preferences │  │                │  │
│  │  - notifications │  │  - model pref  │  │                │  │
│  └──────────────────┘  └────────────────┘  └────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Package Structure

```
com.medusa.mobile/
├── MainActivity.kt                     # Single-activity host
├── MedusaMobileApp.kt                  # Application class (DI root)
│
├── agent/
│   ├── AgentOrchestrator.kt    ✅     # Agentic loop: stream → tool → resubmit
│   ├── ToolDispatcher.kt       ✅     # Routes tool_use.name → tool instance
│   └── tools/
│       ├── SmsTool.kt           ✅     # mm-006 — READ_SMS ContentProvider
│       ├── CallHistoryTool.kt   ✅     # mm-007 — READ_CALL_LOG ContentProvider
│       ├── NotificationTool.kt  ✅     # mm-008 — reads from NotificationStore
│       ├── ContactsTool.kt             # READ_CONTACTS ContentProvider
│       ├── CalendarTool.kt             # READ/WRITE_CALENDAR ContentProvider
│       ├── LocationTool.kt             # FusedLocationProvider + Geocoder
│       ├── AppLauncherTool.kt          # Intent.ACTION_MAIN + PackageManager
│       ├── WebTool.kt                  # OkHttp GET + HTML strip
│       ├── FileTool.kt                 # Storage Access Framework
│       └── MailTool.kt                 # Intent.ACTION_SEND
│
├── api/
│   └── ClaudeApiService.kt     ✅     # mm-005 — OkHttp SSE streaming + tool_use
│
├── models/
│   └── ToolResult.kt           ✅     # Shared ToolResult + DTOs (SMS, Calls, etc.)
│
├── services/
│   ├── NotificationReaderService.kt ✅ # mm-008 — NotificationListenerService
│   └── AccessibilityService.kt         # Phase 3 — UI automation
│
├── persistence/
│   ├── MedusaDatabase.kt               # Room DB (conversations, memories, notifs)
│   ├── MemoryStore.kt                  # BM25 retrieval over conversation history
│   └── SettingsStore.kt                # DataStore Preferences wrapper
│
└── ui/
    ├── screens/
    │   ├── ChatScreen.kt                # Main chat interface
    │   ├── SettingsScreen.kt            # API key, model, permissions
    │   └── PermissionsScreen.kt         # Guided permission grant flow
    ├── components/
    │   ├── GlassCard.kt         ✅     # Frosted glass card effect
    │   ├── ChatBubble.kt        ✅     # Message bubble (user/agent)
    │   ├── InputBar.kt          ✅     # Text input + send button
    │   ├── ThinkingIndicator.kt ✅     # Animated thinking dots
    │   └── ToolChip.kt                 # Tool execution status pill
    └── theme/
        ├── Color.kt             ✅     # MedusaColors: #1B5E20 / #00E676
        ├── Theme.kt             ✅     # MedusaMobileTheme (dark-first)
        └── Type.kt              ✅     # Typography
```

**✅ = already implemented by team**

---

## Key Architecture Decisions

### 1. Single-Activity + Compose Navigation
- One `MainActivity`, Compose `NavHost` for ChatScreen ↔ SettingsScreen
- Edge-to-edge with dark green status bar + navigation bar
- No Fragments — pure Compose

### 2. AgentOrchestrator (new — needs build)
Central coordinator that drives the agentic loop:

```kotlin
class AgentOrchestrator(
    private val apiService: ClaudeApiService,
    private val toolDispatcher: ToolDispatcher,
    private val memoryStore: MemoryStore
) {
    fun chat(userText: String): Flow<AgentEvent> = flow {
        // 1. Augment system prompt with retrieved memories
        // 2. Stream response from Claude
        // 3. If stop_reason == "tool_use":
        //      a. Execute tool via ToolDispatcher
        //      b. Inject tool_result into conversation
        //      c. Re-send to Claude (loop)
        // 4. If stop_reason == "end_turn": emit final text, save to memory
    }
}
```

**Why separate from ViewModel?** The orchestrator is a pure-Kotlin coroutine pipeline — testable without Android, reusable if we add a widget or overlay. The ViewModel just observes its Flow.

### 3. ToolDispatcher (new — needs build)
Decoupled routing layer:

```kotlin
class ToolDispatcher(private val context: Context) {
    private val smsTool = SmsTool(context)
    private val callTool = CallHistoryTool(context)
    private val notifTool = NotificationTool()
    // ... all tools

    suspend fun execute(name: String, input: JSONObject): String {
        val result: ToolResult = when (name) {
            "get_sms"           -> smsTool.getMessages(...)
            "get_call_history"  -> callTool.getCalls(...)
            "get_notifications" -> notifTool.getRecent(...)
            else -> ToolResult.failure("Unknown tool: $name")
        }
        return Json.encodeToString(result)
    }
}
```

### 4. Persistence: Room + DataStore + EncryptedSharedPreferences
| Data | Storage | Why |
|------|---------|-----|
| API key | EncryptedSharedPreferences | Hardware-backed keystore on Android |
| Conversation history | Room SQLite | Structured queries, FTS5 search |
| Notification log | Room SQLite | NotificationListenerService runs in background |
| User preferences | DataStore Preferences | Async, type-safe, no ANR risk |
| Memory retrieval | Room + BM25 Kotlin | Same pattern as iAgent MemoryRetriever |

### 5. Permission Flow
Android runtime permissions require careful UX:
```
PermissionsScreen (guided walkthrough)
  ├── SMS + Contacts     → requestPermissions([READ_SMS, READ_CONTACTS])
  ├── Call Log           → requestPermissions([READ_CALL_LOG])
  ├── Calendar           → requestPermissions([READ_CALENDAR, WRITE_CALENDAR])
  ├── Location           → requestPermissions([ACCESS_FINE_LOCATION])
  ├── Notification Access→ Settings intent (special permission, not runtime)
  └── Accessibility      → Settings intent (Phase 3, special permission)
```

Tools gracefully degrade: if permission not granted, tool returns `ToolResult.denied(...)` with instructions.

---

## Android-Only Superpowers (vs iAgent iOS)

| Capability | Android API | iOS Equivalent |
|---|---|---|
| **Read all SMS** | `Telephony.Sms.CONTENT_URI` ContentProvider | ❌ Impossible (sandboxed, Shortcuts hack only) |
| **Read call history** | `CallLog.Calls.CONTENT_URI` ContentProvider | ❌ No API (CallKit = active calls only) |
| **Read ALL notifications** | `NotificationListenerService` | ❌ Impossible (no user-app access) |
| **UI automation / taps** | `AccessibilityService` | ❌ Impossible (sandboxed) |
| **Read any app's mail** | `ContentProvider` or `AccountManager` | ❌ Sandboxed per-app |
| **Background agent** | `ForegroundService` + notification | Limited background (15s) |
| **Open any app with data** | `Intent` with extras | URL schemes only (lossy) |

---

## Data Flow: User → Claude → Tool → Response

```
User: "Who texted me today?"
  │
  ▼
ChatViewModel.send(text)
  │
  ▼
AgentOrchestrator.chat(text)
  │  ├── systemPrompt = basePrompt + MemoryStore.retrieve(text)
  │  ├── tools = ToolDispatcher.allToolDefinitions()
  │  └── ClaudeApiService.streamMessage(history, systemPrompt, tools)
  │
  ▼
Claude streams: { tool_use: { name: "get_sms", input: { filter: "today" } } }
  │
  ▼
ToolDispatcher.execute("get_sms", input)
  │  └── SmsTool.getMessages(sinceHours = 24)
  │       └── ContentResolver.query(Telephony.Sms.CONTENT_URI, ...)
  │            └── Returns ToolResult { success: true, data: SmsListDTO }
  │
  ▼
AgentOrchestrator injects tool_result → re-sends to Claude
  │
  ▼
Claude streams: "You got 3 texts today: Mom at 9am said..."
  │
  ▼
ChatViewModel updates UI: assistant bubble + tool chip
```

---

## Phase Plan

### Phase 1: MVP (Current Sprint)
| ID | Task | Owner | Status |
|----|------|-------|--------|
| mm-002 | Gradle project + Compose setup | Dev1 | ✅ Done |
| mm-003 | Architecture plan (this doc) | Dev2 | ✅ Done |
| mm-004 | UI shell: ChatScreen, dark green theme | Dev1 | ✅ Done |
| mm-005 | ClaudeApiService (SSE + tool loop) | Dev3 | ✅ Done |
| mm-006 | SmsTool (READ_SMS) | Dev4 | ✅ Done |
| mm-007 | CallHistoryTool (READ_CALL_LOG) | Dev4 | ✅ Done |
| mm-008 | NotificationReaderService | Dev3 | ✅ Done |
| mm-009 | **AgentOrchestrator** — wire API ↔ Tools ↔ UI | Dev2 | ✅ Done |
| mm-010 | **ToolDispatcher** — route tool_use → tool classes | Dev2 | ✅ Done |
| mm-011 | **ChatViewModel** — StateFlow, send/receive | Dev2 | ✅ Done |
| mm-012 | **SettingsScreen** — API key (EncryptedSP), model select | **Unassigned** | Pending |
| mm-013 | **PermissionsScreen** — guided permission grant flow | **Unassigned** | Pending |

### Phase 2: Full Tool Suite
| ID | Task | Status |
|----|------|--------|
| mm-014 | ContactsTool — READ_CONTACTS ContentProvider | Pending |
| mm-015 | CalendarTool — READ/WRITE_CALENDAR ContentProvider | Pending |
| mm-016 | LocationTool — FusedLocationProviderClient + Geocoder | Pending |
| mm-017 | AppLauncherTool — PackageManager + Intent.ACTION_MAIN | Pending |
| mm-018 | WebTool — OkHttp GET + HTML→text stripping | Pending |
| mm-019 | MailTool — Intent.ACTION_SEND (compose) | Pending |
| mm-020 | FileTool — Storage Access Framework (SAF) | Pending |
| mm-021 | Room Database + MemoryStore (BM25 retrieval) | Pending |

### Phase 3: Android Superpowers
| ID | Task | Status |
|----|------|--------|
| mm-030 | AccessibilityService — UI automation / taps | Pending |
| mm-031 | Overlay agent — floating chat bubble | Pending |
| mm-032 | ForegroundService — persistent background agent | Pending |
| mm-033 | Widget — home screen quick-chat | Pending |
| mm-034 | Intent intercept — share-to-Medusa from any app | Pending |

---

## Tech Stack Summary

| Layer | Technology |
|-------|------------|
| Language | Kotlin 2.0+ |
| UI | Jetpack Compose + Material 3 |
| Navigation | Compose Navigation |
| Networking | OkHttp 4.12 + SSE |
| JSON | kotlinx.serialization (DTOs) + org.json (API wire) |
| Persistence | Room (SQLite) + DataStore Preferences |
| Security | EncryptedSharedPreferences (API key) |
| Async | Kotlin Coroutines + StateFlow |
| Min SDK | 26 (Android 8.0) — 95%+ device coverage |
| Target SDK | 36 |
| Build | Gradle KTS + AGP |

---

## Critical Path to First Working Demo

```
mm-009 (AgentOrchestrator)  ← BLOCKING — nothing works without this
    └── mm-010 (ToolDispatcher)
         └── mm-011 (ChatViewModel wires orchestrator ↔ UI)
              └── mm-012 (SettingsScreen — API key entry)
                   └── DEMO: "Who texted me?" works end-to-end
```

**Estimated time to demo: 2-3 dev-days with 2 devs parallel on mm-009/010 and mm-011/012.**
