# The 3rd Birthday — Android Port (`arm64-v8a` Static Recompilation)

Port nativo para **Android (`arm64-v8a`)** de **The 3rd Birthday** (PSP, versão US `ULUS10567`), baseado na recompilação estática de MIPS para C (`BasedYuki/the-3rd-birthday-recomp` v0.9.16), rodando diretamente em código nativo ARM64 com renderizador **Vulkan**, **AAudio** e decodificação de vídeo por hardware (**AMediaCodec**).

> **Aviso:** Nenhuma ISO ou dado protegido por direitos autorais do jogo está incluído neste repositório. Você precisa fornecer sua própria cópia (`ULUS10567.iso`).

---

## Principais Recursos do Port Android

- **Renderização Vulkan + Adrenotools:** suporte a driver Vulkan nativo ou driver Turnip/Adreno customizado em GPUs Qualcomm Snapdragon.
- **AMD FSR 1.0 (EASU + RCAS) & Cor:** upscaling por shader Vulkan com controle de nitidez, brilho, contraste e saturação.
- **Áudio Nativo AAudio:** stream `44100 Hz` estéreo `PCM_I16` em modo `LOW_LATENCY` diretamente via `<aaudio/AAudio.h>` (subsistema de áudio do SDL3 desativado com `SDL_AUDIO=OFF`).
- **FMVs por Hardware (`AMediaCodec` + ARM64 NEON):** decodificação H.264 por hardware (`libmediandk`), conversão NV12 → RGBA8888 vetorizada em NEON SIMD e proteção anti-rollback com cache de frame.
- **Controles Táteis & Controle Físico:**
  - HUD temática crimson com analógico esquerdo flutuante, mira 1:1 nativa por toque na metade direita da tela e editor de layout arrastável in-game.
  - Detecção automática de controle físico (oculta o HUD tátil automaticamente).
- **Mod Menu & Perfis de Hardware (`⚡ Desempenho`):**
  - Perfis de hardware usando `sched_setaffinity`, prioridade de thread e Android `PerformanceHintManager` (ADPF): **Padrão (0%)**, **Light (30%)**, **Medium (60%)** e **High (85%)**.
  - Resolução interna (`Auto` e `1x`–`5x`), Widescreen 16:9, Glow/Bloom, Frame Gen 60 FPS, xBRZ, Texture Packs HD, Prompts PSP/Xbox e contador de FPS.
- **Saves Persistentes & Diálogo Nativo:**
  - Diálogo nativo de Save/Load em estilo Crimson CTI com seleção por toque e suporte a controle.
  - Migração e armazenamento persistente em `/sdcard/The3rdBirthday/memstick` (`SR_MEMSTICK`), preservando seus saves entre atualizações e reinstalações.

---

## Como Compilar (Android `arm64-v8a`)

### Pré-requisitos
- Android SDK (`compileSdk 34`, `minSdk 26`) + **NDK 26+** + CMake 3.22.1+
- JDK 17+ e Gradle
- Arquivos recompilados gerados em `build/ULUS10567/` a partir da sua própria `ULUS10567.iso`

### Build via Gradle
```powershell
cd android
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
gradle assembleDebug
```
Para instalar diretamente via ADB em um aparelho conectado:
```powershell
gradle installDebug
```

---

## Créditos
- Projeto base de recompilação estática: [BasedYuki/the-3rd-birthday-recomp](https://github.com/BasedYuki/the-3rd-birthday-recomp)
- Port Android, otimizações ARM64/AAudio/AMediaCodec/FSR e interface: **Seports (Gemfinja)**
