# AGUS VR — Ambiente Operacional Espacial para Android

![Build AGUS VR](https://github.com/neresbielxx2-ai/Lunar-VR-project-on/actions/workflows/build.yml/badge.svg?branch=arena%2F01a0a1e5-lunar-vr-project-on)

**AGUS VR** é um ambiente operacional de VR/AR para Android construído do zero: câmera traseira
como passthrough do mundo real, interface flutuante em 3D (janelas, tiles e HUD com profundidade,
perspectiva e sombras), rastreamento real de mãos (MediaPipe), um sistema de apontar/selecionar por
aproximidade, loja de apps, biblioteca de jogos HTML, navegador, gerenciador de arquivos, um lab de
modelos 3D (Filament) e um lab de testes de mão — tudo rodando como **views Android reais em espaço 3D**,
não como telas 2D planas nem simulações visuais.

> Nothing here is faked: if the device lacks a rear camera, gyro, WebView or the hand model, the
> system degrades gracefully and **says so** — every limitation is reported honestly in PT-BR.

---

## Índice

1. [O que está implementado](#o-que-está-implementado)
2. [Arquitetura (módulos)](#arquitetura-módulos)
3. [Como o modo VR funciona](#como-o-modo-vr-funciona)
4. [Apps internos](#apps-internos)
5. [Estrutura de armazenamento](#estrutura-de-armazenamento)
6. [Build (local e CI)](#build-local-e-ci)
7. [Assinatura](#assinatura)
8. [Limitações conhecidas (honestas)](#limitações-conhecidas-honestas)
9. [Licenças e créditos](#licenças-e-créditos)

---

## O que está implementado

### Câmera traseira (passthrough) — nunca a selfie
- CameraX com `LENS_FACING_BACK` obrigatório; preview em `FILL_CENTER` atrás de toda a UI espacial.
- Fluxo de permissão completo em PT-BR: explicação → permissão → negação → caminhos
  ("tentar novamente", "abrir configurações do app", **"continuar sem câmera"**).
- Sem câmera (ou permissão negada): o **Ambiente Virtual** (céu estrelado, nebulosa e grade de
  horizonte animada) assume o fundo — e o app informa que o rastreamento de mãos fica indisponível.
- Três níveis de resolução (low/medium/high) configuráveis; rebind automático ao mudar.

### Rastreamento real de mãos
> **Status desta build (UI-first):** o motor de mãos está completo e compilado, mas nasce
> **desativado por gate de build** (`runtime/BuildFlags.HAND_TRACKING = false`) por decisão de
> produto: primeiro a interface espacial 3d curva, mãos na atualização seguinte. O app diz isso
> com todas as letras (HUD, Configurações, Hand Lab) — nada de mão falsa.
- MediaPipe **Hand Landmarker** (modelo `hand_landmarker.task`, baixado no build com sha256 pinado),
  LIVE_STREAM, GPU com fallback CPU, até 2 mãos, 21 landmarks cada.
- Gestos reconhecidos: **aberta, apontar (index), pinça, grab (punho), thumbs-up**.
- Lado esquerdo/direito corrigido para câmera traseira (não espelhada).
- Frames de análise divididos pelo divisor do perfil de performance (auto-degradação).

### Apontar e selecionar (AgusPointInteraction)
- Raio saindo da ponta do indicador + **círculo na ponta do raio** + destaque do alvo sob o raio.
- **Seleção por aproximação** com debounce (300–400 ms), distância mínima, tempo mínimo de dwell
  (configurável), exigência de estabilidade do raio e **cancelamento ao sair do alvo**.
- Pinça = seleção instantânea; punho = arrastar (janelas, tiles, sliders, objetos 3D) via eventos
  de toque sintetizados — o mesmo pipeline que o dedo usa.
- Toque real sempre tem prioridade: enquanto o dedo está na tela, o fluxo sintético pausa.

### UI espacial flutuante (v2 — curva e imersiva)
- **Sala 3D imersiva** (`EnvironmentRoom`): panorama cilíndrico de 18 fatias com estrelas/nebulosa/
  horizonte, piso em perspectiva com grid convergente e poço de luz, brilho de teto e pedestal —
  tudo posicionado em 3D real ao redor do usuário; em passthrough o panorama some e piso/brilhos
  ficam translúcidos sobre a câmera traseira.
- **Janelas curvas de verdade** (`CurvedSurface`): o conteúdo do painel é renderizado em um bitmap
  e apresentado por 14 fatias verticais dispostas num cilindro (cada fatia com rotationY/
  translationZ próprios), então a tela **envolve o usuário** como um display de cockpit — não é
  um retângulo plano colado na tela. O toque de cada fatia é mapeado de volta às coordenadas do
  conteúdo e despachado à hierarquia oculta: botões, scrolls e WebViews continuam funcionais.
- Tiles e janelas posicionados num arco cilíndrico (`yaw/pitch/dist`) com rotação em perspectiva,
  escala por profundidade, sombras e escurecimento por distância — `SpatialMath.applyCylinder`.
- **Paralaxe por giroscópio** (rotation vector, com fallback em acelerômetro) move o mundo suavemente.
- Atmosfera: partículas, brilho de horizonte e vinheta — desligáveis por perfil/bateria.
- Notificações espaciais discretas (cards que entram/saem no mundo) + notificações de sistema reais.

### Home VR — 8 ícones com identidade própria
Model Lab · Biblioteca · Hand Lab (arco superior) — AGUS Store · [holograma central] · Navegador
(meio) — Performance · Arquivos · Configurações (arco inferior). Visual dark/futurista/minimalista
(Orbitron + Manrope + JetBrains Mono, ciano/violeta/menta), inspirado em UX de headsets mas com
identidade própria — sem cópia de Meta Quest.

### Menus espaciais 3DOF (`vr3d/` — Spatial3dActivity)
Sistema completo de menus flutuantes em 3D real (Filament), **somente 3DOF** — sem 6DOF, sem
SLAM, sem ARCore, sem posicionamento por câmera:
- **WorldRoot → CameraRig → Camera** (mono ou SBS) e menus como **irmãos da câmera**, nunca
  filhos: a orientação da cabeça (quaternion do rotation-vector, suavizado com slerp crítico +
  deadzone anti-jitter, recenter explícito) move **só o rig**; cada painel guarda posição +
  quaternion + escala no referencial virtual e fica parado enquanto você olha ao redor.
- **Barra inicial** flutuante a ~1,7 m (INICIAR · CONFIGURAÇÕES · SAIR), vidro discreto, cantos
  arredondados, sombra suave — linguagem visual limpa estilo Quest/Zentra, sem neon exagerado.
- **Menus flutuantes** (MainMenu, Settings, System + painéis de apps reais) como malhas curvas
  texturizadas (render-to-texture das views Android reais, totalmente interativas por toque no
  modo mono e por **gaze-dwell ~1,2 s** no modo SBS).
- **SBS / VR Box**: dois viewports + dois scissors implícitos por viewport, câmeras esquerda/
  direita offsetadas por **IPD configurável (52–76 mm)** na mesma cena e no mesmo frame —
  profundidade estereoscópica real, sem duplicar ou desalinhar menus.
- **Abrir/fechar** com escala 0.92→1.0 + fade + aproximação/afastamento (~220 ms).
- **Desempenho**: materiais unlit, 1 malha curva por painel (6–10 colunas), 1 textura de sombra
  compartilhada, budget de 2 uploads de textura/frame, fallback por modo de qualidade
  (resolução de textura e colunas reduzidas em "desempenho").

### Janelas VR (AgusWindowSystem)
- Abrir/fechar/minimizar/focar; **arrastáveis em 3D** (pela barra de título, com dedo; com punho
  quando o gate de mãos for ligado); redimensionáveis pelo canto; persistem na posição do espaço
  onde você as deixou; até 6 janelas simultâneas (a menos focada fecha automaticamente).
- Cada janela vive numa `CurvedSurface` no world layer; o conteúdo fica anexado a um host
  invisível (necessário para WebView) e redesenhado no bitmap da superfície: focada a ~30 fps,
  demais a ~3 fps + sob toque (ver Limitações).

---

## Arquitetura (módulos)

Cada módulo exigido vive num pacote próprio dentro de `app/src/main/java/com/agusvr/`:

| Módulo exigido        | Pacote / arquivos principais                                              |
|-----------------------|---------------------------------------------------------------------------|
| AgusVRRuntime         | `runtime/` (RuntimeCore, DeviceCapabilities/DeviceProbe, AgusEvents/AgusBus), `AgusApp` |
| AgusCamera            | `camera/CameraEngine.kt` (CameraX traseira + ImageAnalysis)               |
| AgusHandTracking      | `hand/` (HandTrackingEngine, GestureRecognizer, HandModels)               |
| AgusPointInteraction  | `point/PointInteraction.kt` (raio, hover, dwell, pinch, grab, synth touch)|
| AgusInteraction       | `point/` + `handlab/` (suíte de 7 testes reais)                           |
| AgusSpatialUI         | `spatial/` (SpatialMath, SpatialOverlayView, Atmosphere, Backdrop, Gyro), `ui/widgets`, `ui/dialogs`, `ui/Splash*`, `ui/ShellActivity` |
| AgusWindowSystem      | `windows/` (SpatialWindowManager, WindowFrameView, VrApp, DisposableView) |
| AgusHome              | `home/` (HomeController, TileView)                                        |
| AgusStore             | `store/` (StorePanel, StoreRepository, DownloadEngine)                    |
| AgusLibrary           | `library/` (LibraryPanel, GamePlayerPanel, LibraryRepository, ImportPipeline, CoverFactory, modelos) |
| AgusBrowser           | `browser/BrowserPanel.kt`                                                 |
| AgusFileManager       | `files/FilesPanel.kt` + `storage/FileOps.kt`                              |
| AgusModelLab          | `modellab/` (FilamentStage, ObjParser, ModelLabPanel, ProjectRepository)  |
| AgusStorage           | `storage/` (AgusPaths, FileOps)                                           |
| AgusPerformance       | `performance/` (PerformanceMonitor, PerfModels, PerformancePanel)         |
| AgusSettings          | `settings/` (SettingsRepo, SettingsPanel)                                 |
| AgusNotifications     | `notifications/` (SpatialToasts, SystemNotifier)                          |
| VR (integração)       | `vr/VRActivity.kt`, `vr/HudController.kt`                                 |

Toda a UI é construída **programaticamente** (views nativas + drawables vetoriais próprios), sem
XML de tela, sem Compose, sem bibliotecas de UI de terceiros.

---

## Como o modo VR funciona

```
SplashActivity (logo animado próprio + passos reais de boot)
   └─> ShellActivity (status honesto do dispositivo: câmera/mãos/sensores/storage + "INICIAR VR")
          └─> VRActivity  (sensorLandscape, imersivo)
                 camadas (fundo → frente):
                 1. PreviewView (câmera traseira)  |  EnvironmentBackdropView (ambiente virtual)
                 2. worldLayer — tiles do Home + janelas flutuantes (SpatialWindowManager)
                 3. hudLayer — chips (câmera · mãos · fps) + ações (home, mãos, status, sair)
                 4. AtmosphereView (partículas/vinheta)
                 5. toastLayer (notificações espaciais)
                 6. SpatialOverlayView (esqueleto das mãos, raio, círculo, dwell ring, holograma)
```

- O loop de render (Choreographer) chama `PerformanceMonitor.onFrame()`, alimenta o overlay com o
  último `HandFrame` e o estado do `PointInteraction`, e sincroniza HUD/home a ~4 Hz.
- `dispatchTouchEvent` marca toque real → o stream sintético da mão pausa 350 ms.
- Painéis são criados por um `PanelFactory` e falam com a activity só pela interface `PanelHost`
  (pickers SAF, permissão de notificação, abrir/fechar app, perfil de qualidade) — nunca seguram a Activity.
- Fechar todas as janelas traz o Home de volta; o holograma central some quando há janelas.

### Pipeline de mão
```
ImageAnalysis (RGBA) → Bitmap reutilizável → rotação (ImageInfo) → MPImage
→ HandLandmarker.detectAsync (GPU→CPU) → 21 landmarks → GestureRecognizer
→ HandFrame (coords de tela, mapeamento cover-fit igual ao PreviewView) → main thread
→ PointInteraction (raio/hover/dwell/pinch/grab) → SpatialOverlayView (desenho)
```

---

## Apps internos

### 🛒 AGUS Store (`store/`)
- Catálogo em `assets/store_catalog.json` com categorias, busca, cards com acento por categoria,
  tamanho, autor e licença.
- Instalação real por tipo: `builtin_html` (jogos embutidos → Library), `builtin_models` (modelos
  de exemplo → AgusVR/Models), `remote_zip` (download com **progresso por item**, validação de
  conteúdo/`index.html`, instalação via ImportPipeline) e `web` (web app → Library).
- **"Adicionar da internet"**: URL → classificação honesta (arquivo baixável vs página web) →
  download com progresso ou web app; **"Importar local"** via SAF.
- Erros reais em PT-BR (rede, zip inválido, zip sem HTML, formato não suportado).
- Apenas conteúdo legal: jogos embutidos próprios/CC, catálogo aponta apenas fontes lícitas;
  nada é baixado de repositórios de terceiros sem licença clara.

### 📚 Biblioteca (`library/`)
- Abas **Jogos/Modelos**; capas geradas automaticamente (`CoverFactory` — gradientes + glyph por
  tipo, ou capa do usuário via SAF); grid com nome/tamanho/data.
- Ações por item: abrir, renomear (renomeia o diretório junto), trocar capa, informações, excluir
  (com confirmação).
- Player de jogos: WebView seguro com acesso a arquivo **somente** para jogos locais, JavaScript
  habilitado, console logado; ZIPs são extraídos e o `index.html` detectado automaticamente.
- **Formatos não suportados** (ex.: `.java`, `.apk`) recebem a mensagem
  *"Formato não suportado pelo Agus VR."* + explicação honesta do porquê — nunca uma execução falsa.

### 🌐 Navegador (`browser/`)
- WebView endurecido (sem acesso a arquivos/conteúdo local, mixed-content bloqueado), barra com
  voltar/avançar/recarregar/home + campo de URL (sem esquema → busca Google), barra de progresso,
  schemes externos (mailto/tel/etc.) via intent, home page configurável nas Configurações.

### 📁 Arquivos (`files/`)
- Gerenciador real da árvore `AgusVR/`: navegar, breadcrumb, subir, nova pasta, importar (SAF),
  abrir por tipo (HTML→player, modelos→Model Lab, imagens→viewer, texto→viewer, ZIP→extrair/
  instalar/abrir com app externo), copiar/mover (modo destino com banner), renomear, excluir, infos.
- Atalho **legal** para ZArchiver (via `getLaunchIntentForPackage`, sem modificar/redistribuir o app);
  se não instalado, explica e oferece o chooser do sistema via FileProvider.

### 🧊 AGUS Model Lab (`modellab/`)
- Renderização **Filament real** num `TextureView` dentro da janela flutuante.
- Importa **GLB/glTF** (gltfio, recursos externos resolvidos quando `.gltf`) e **OBJ**
  (parser próprio: v/vn/vt/f, ngons em leque, índices negativos, normais flat quando ausentes,
  material LIT compilado em runtime via filamat).
- Múltiplos objetos na cena; modos **Órbita / Mover / Girar / Escala** — com toque (arrasto, pinça
  de zoom) **e com a mão** (pinça seleciona pelo AABB projetado, punho arrasta no modo ativo).
- Duplicar (OBJ), excluir, recentrar câmera, animações glTF em loop automático.
- **Projetos**: salvar com nome + **capta da cena** (bitmap do TextureView) + data + transforms
  completos (posição/rotação/escala por objeto) em `AgusVR/Projects`; reabrir reimporta os modelos
  e reaplica os transforms; excluir projeto.
- Modelos de exemplo: 3 gerados e validados (`assets/samples/`) + downloads do catálogo da Store.

### ✋ AGUS Hand Lab (`handlab/`)
7 testes **reais** ligados ao `PointInteraction.TestListener` (nada é simulado):
1. **Apontar** — mão apontando estável (~3 s) com coords vivas da ponta do dedo;
2. **Aproximação** — progresso de dwell até 100%;
3. **Seleção** — 3 alvos coloridos selecionáveis por pinça/dwell/toque;
4. **Grab** — punho fechado detectado;
5. **Mover** — ≥600 px de arrasto contínuo;
6. **Release** — soltar após ≥100 px de movimento;
7. **Menu** — alvo `hl_menu` ativado por gesto (hook de menu espacial).

### 📈 Performance (`performance/`)
- FPS (gráfico ao vivo), ms/quadro, memória app + heap nativo + sistema, CPU do app, bateria
  (nível/carregando), temperatura (sensor térmico quando existe), estado do hand tracking, auto-nível.
- Modos **Performance / Balanceado / Qualidade** + economia de bateria; **auto-degradação real**:
  o monitor ajusta partículas/sombras/paralaxe/divisor de análise conforme FPS e memória, e loga
  cada ajuste no painel ("eventos automáticos").

### ⚙️ Configurações (`settings/`)
Distância e tamanho da interface, sensibilidade do ponto, distância de seleção, tempo de dwell,
intensidade/comprimento do raio, hand tracking (on/off + delegate GPU/CPU), haptics, sons, efeitos,
sombras, partículas, modo de performance, auto-qualidade, economia de bateria, resolução da câmera,
overlay de debug, home do navegador — tudo aplicado **ao vivo** no mundo VR. Reset + exportação
`settings.json`.

### 🩺 Status (`status/`)
Janela de diagnóstico: capacidades do dispositivo, telemetria ao vivo, estado dos subsistemas e
uso de armazenamento de cada pasta `AgusVR/`.

---

## Estrutura de armazenamento

Tudo dentro do sandbox do app (`getExternalFilesDir`, sem permissões legadas de storage; importações
via **SAF**):

```
Android/data/com.agusvr/files/AgusVR/
├── Library/     itens instalados da Store e importações (jogos, web apps)
├── Games/       jogos HTML importados/extraídos
├── Apps/        web apps (URL + metadados)
├── Models/      modelos 3D (OBJ/GLB/glTF)
├── Images/      imagens importadas / capas escolhidas pelo usuário
├── Downloads/   downloads em andamento e concluídos
├── Projects/    projetos do Model Lab (project.json + cover.png)
├── Settings/    settings.json exportado
└── Covers/      capas geradas automaticamente
```

---

## Build (local e CI)

### GitHub Actions (caminho principal)
`.github/workflows/build.yml` — em todo push:
1. JDK 17 (temurin) + cache Gradle;
2. `./gradlew downloadHandModel` — baixa o `hand_landmarker.task` oficial (sha256 pinado;
   `-PAGUS_ALLOW_MISSING_MODEL=1` constrói sem o modelo e o app desativa mãos honestamente);
3. `assembleDebug` + `assembleRelease` (release **assinado** com a key demo do repo);
4. Verificação de assinatura (`apksigner`) e upload do artefato **AgusVR-APKs**.

### Local
```bash
./gradlew assembleDebug        # o modelo de mão é baixado automaticamente (pré-build)
./gradlew assembleRelease      # usa keystore/agusvr-release.p12 (demo)
```
Requisitos: JDK 17, Android SDK 35, internet para dependências + modelo.

Stack: AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.9 · compileSdk 35 · minSdk 26 · targetSdk 35 ·
CameraX 1.3.4 · MediaPipe tasks-vision 0.10.32 · Filament 1.75.1 · kotlinx-coroutines 1.9.0.
Sem Compose/Room/Hilt/OkHttp — HTTP via `HttpURLConnection`, JSON via `org.json`, prefs via
`SharedPreferences`.

---

## Assinatura

`keystore/agusvr-release.p12` é uma **chave de demonstração commitada de propósito** para o CI
gerar um release assinado e instalável (senha no `gradle.properties` / `keystore/README.md`).
**Não use para publicar** — substitua pela sua própria chave via propriedades `AGUS_KEYSTORE_*`.

---

## Limitações conhecidas (honestas)

- **Hand tracking exige câmera traseira + o modelo baixado no build** e, nesta versão, está
  **intencionalmente desligado** (`BuildFlags.HAND_TRACKING`) — build UI-first; o app informa em
  vez de fingir. Sem câmera/modelo (ou com o gate off), tudo segue utilizável por toque.
- **Janelas curvas são render-to-texture**: o conteúdo é redesenhado num bitmap (focada ~30 fps,
  secundárias ~3 fps, sob toque imediato). Conteúdo muito animado em janela não focada pode
  parecer congelado até receber foco/toque — é o custo honesto de curvar views Android reais sem
  um compositor 3D próprio.
- **Sem 6DoF real**: a "espacialidade" é um mundo 3D projetado em tela 2D com paralaxe de
  giroscópio — depth/perspectiva/sombras reais nas views, mas sem SLAM (exigiria ARCore, e o
  objetivo aqui é o ambiente operacional próprio).
- **Duplicar no Model Lab** duplica objetos OBJ em memória; modelos glTF são reimportados do
  arquivo (cópia profunda de asset glTF não é suportada pelo gltfio sem re-load — comunicado ao usuário).
- **Temperatura** só aparece quando o dispositivo expõe sensor térmico; caso contrário: "sensor indisponível".
- `.java`/`.apk` não são "executáveis" dentro do app — a Library explica o porquê em vez de fingir.
- ZArchiver é apenas um atalho por intent público (legal), nunca empacotado/modificado.
- R8/minificação desligados no release demo (superfícies JNI do Filament/MediaPipe); seguro para
  sideload, não é a configuração final de loja.

---

## Licenças e créditos

- **Fontes**: Orbitron (SIL OFL), Manrope (SIL OFL), JetBrains Mono (SIL OFL) — `third_party/licenses/`.
- **MediaPipe Hand Landmarker** (modelo + tasks-vision): Apache-2.0, Google — baixado no build, não redistribuído.
- **Filament / gltfio / filamat**: Apache-2.0, Google.
- **Jogos embutidos**: 2048 (MIT, Gabriele Cirulli) e HexGL (MIT, BKcore) via catálogo remoto da Store;
  os 3 jogos em `assets/games/` são originais deste projeto.
- Detalhes completos em `third_party/NOTICES.md`.

---

*Agus VR — identidade própria: ciano profundo, violeta e menta sobre o vazio. Feito para parecer um
sistema, não um demo.*
