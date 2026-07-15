# BrawlDrafter

AI-powered Brawl Stars draft assistant with floating overlay.

## How It Works

1. **Floating Overlay** — A draggable "BD" button floats over Brawl Stars
2. **Screen OCR** — Tap the button to capture & OCR the draft screen (ML Kit, on-device)
3. **Live Meta Data** — Fetches current brawler win rates, map stats from Brawlify API
4. **AI Recommendations** — Sends structured draft data to LLM (GPT-4o-mini / Gemini Flash / Claude Haiku)
5. **Instant Results** — Shows top picks with scores, counter analysis, synergy info, and reasoning

## Architecture

```
┌─────────────────────────────────────────────┐
│         Floating Overlay (WindowManager)     │
│  ┌──────────┐         ┌──────────────────┐  │
│  │ BD Button │  tap    │  Results Panel   │  │
│  └─────┬────┘ ──────> │  Grade / Score   │  │
│        │               │  Reasoning       │  │
│        v               │  Counter/Synergy │  │
│  ┌──────────┐         └──────────────────┘  │
│  │ Screen   │                               │
│  │ Capture  │                               │
│  └─────┬────┘                               │
│        v                                     │
│  ┌──────────┐         ┌──────────────────┐  │
│  │ ML Kit   │ parsed  │  Recommendation  │  │
│  │ OCR      │ ──────> │  Engine          │  │
│  └──────────┘         └────────┬─────────┘  │
│                                │             │
│              ┌─────────────────┼─────────┐  │
│              v                 v         v  │
│       ┌──────────┐    ┌──────────┐ ┌─────┐ │
│       │ Brawlify │    │ LLM API  │ │Local│ │
│       │ API      │    │ (GPT-4o  │ │Data │ │
│       │ (Meta)   │    │  mini)   │ │     │ │
│       └──────────┘    └──────────┘ └─────┘ │
└─────────────────────────────────────────────┘
```

## Setup

1. Open in Android Studio
2. Get an LLM API key:
   - **OpenAI** (recommended): [platform.openai.com](https://platform.openai.com) — GPT-4o-mini is fast & cheap
   - **Google Gemini**: [aistudio.google.com](https://aistudio.google.com) — Free tier available
   - **Anthropic Claude**: [console.anthropic.com](https://console.anthropic.com)
3. Build & run on your Android device (min SDK 26 / Android 8.0)
4. Grant overlay permission + screen capture permission
5. Start overlay → open Brawl Stars → tap BD button during draft

## Tech Stack

- **Kotlin** + **Jetpack Compose** (UI)
- **ML Kit** (on-device OCR)
- **Retrofit** (API networking)
- **Brawlify API** (live meta data)
- **OpenAI / Gemini / Claude** (AI recommendations)
- **Room** (local caching)
- **Koin** (dependency injection)

## Features

- Draggable floating overlay button
- On-device OCR (no internet required for text recognition)
- Live meta data from Brawlify (win rates, pick rates, ban rates per map)
- AI-powered pick analysis with reasoning
- Fuzzy brawler name matching (handles OCR errors)
- Spatial draft parsing (team vs enemy detection by screen position)
- Data-only fallback if LLM API is unavailable
- Support for 3 LLM providers (OpenAI, Gemini, Claude)
- Dark gaming-themed UI