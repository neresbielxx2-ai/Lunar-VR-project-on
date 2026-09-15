# Auditoria pré-APK — AGUS VR

Checklist executada antes da primeira compilação no GitHub Actions. Itens marcados
com ✅ foram verificados estaticamente no repositório; itens 🔁 são verificados a
cada build pelo CI; itens 📱 exigem aparelho físico.

## 1. Compilação e empacotamento
- ✅ `settings.gradle`, `build.gradle` (raiz e app), `gradle.properties` e wrapper
  Gradle 8.9 presentes e consistentes (AGP 8.7.3 · Kotlin 2.0.21 · JDK 17).
- ✅ `gradle-wrapper.jar` válido (zip íntegro, contém `GradleWrapperMain`).
- ✅ Todas as 58 fontes Kotlin com chaves/parênteses balanceados (scanner próprio).
- ✅ Todas as referências `R.string` / `R.drawable` / `R.style` / `R.font` /
  `R.color` / `R.layout` (Kotlin, manifest e layouts) resolvem para recursos existentes.
- ✅ Imports internos (`com.agusvr.*`) resolvem para tipos declarados no projeto.
- ✅ APIs entre módulos conferidas (PanelHost, PanelFactory, DisposableView,
  TestListener, Events do stage, repositórios, engines).
- 🔁 `./gradlew assembleDebug assembleRelease` no CI (única validação de tipos 100%
  real — não há JDK neste ambiente de desenvolvimento).

## 2. Permissões e privacidade
- ✅ Manifest declara apenas: CAMERA (com `uses-feature required=false`), INTERNET,
  ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, VIBRATE.
- ✅ Sem permissões de storage legadas — importações usam SAF (`OpenDocument`) e
  arquivos próprios vivem em `getExternalFilesDir`.
- ✅ Fluxo de permissão de câmera: explicação → solicitação → negação com 3 saídas
  (tentar novamente / abrir configurações / continuar sem câmera). Nada é contornado.
- ✅ Permissão de notificação solicitada via `PanelHost.requestNotificationPermission`
  (API 33+), com fallback honesto.
- ✅ FileProvider `${applicationId}.fileprovider` + `res/xml/file_paths.xml` para
  compartilhar ZIPs com apps externos (ZArchiver) sem expor o sandbox.

## 3. Subsistemas (funcionalidade real, sem simulação)
- ✅ **Câmera**: CameraX `LENS_FACING_BACK` obrigatório; selfie nunca é usada;
  fallback para ambiente virtual com aviso quando não há câmera/permissão.
- ✅ **Hand tracking**: MediaPipe HandLandmarker LIVE_STREAM (GPU→CPU), 2 mãos,
  21 landmarks, handedness corrigida para câmera traseira, divisor de frames do
  perfil de performance; modelo baixado no build com sha256 pinado; ausência do
  modelo → estado `UNSUPPORTED` comunicado na UI.
- ✅ **Apontar/selecionar**: raio + círculo na ponta, hover real no view tree
  (inclusive views 3D-transformadas), dwell com debounce/distância mínima/
  estabilidade/cancelamento, pinça instantânea, grab→toque sintetizado, toque real
  tem prioridade (supressão de 350 ms).
- ✅ **Janelas**: abrir/fechar/minimizar/focar/arrastar em 3D/redimensionar;
  posições persistem durante a sessão; máximo de 6 com fechamento da menos recente.
- ✅ **Home**: 8 tiles em arco cilíndrico + holograma central; paralaxe de giroscópio.
- ✅ **Store**: catálogo JSON válido (10 itens, tipos builtin_html/builtin_models/
  remote_zip/web), busca, categorias, progresso por item, import por URL e SAF,
  mensagens de erro reais.
- ✅ **Library**: repositorio JSON, capas geradas, renomear (inclui diretório),
  trocar capa, info, excluir; player WebView; `.java`/`.apk` → "Formato não
  suportado pelo Agus VR." com explicação.
- ✅ **Browser**: WebView sem acesso a file/content, mixed-content bloqueado,
  schemes externos via intent, busca Google para não-URLs.
- ✅ **Files**: navegação real da árvore AgusVR, CRUD completo, extração de ZIP
  validada, atalho ZArchiver apenas via intent público (com verificação de presença).
- ✅ **Model Lab**: Filament real (gltfio para GLB/glTF; parser OBJ próprio +
  material filamat em runtime); mover/girar/escala/duplicar/excluir; picking por
  AABB projetado; projetos com capa (snapshot do TextureView) e transforms;
  restauração sequencial com espera de recursos.
- ✅ **Hand Lab**: 7 testes ligados ao `PointInteraction.TestListener` real.
- ✅ **Performance**: monitor com FPS/memória/CPU/bateria/temperatura/estado das
  mãos, 3 modos, auto-degradação com log de eventos.
- ✅ **Settings**: todos os controles aplicados ao vivo (distância/tamanho da UI,
  sensibilidade, dwell, raio, mãos, haptics, áudio, efeitos, sombras, partículas,
  modo perf, auto-qualidade, bateria, tier de câmera, debug, home do navegador).
- ✅ **Notificações**: toasts espaciais discretos + notificações de sistema para downloads.
- ✅ **Splash/Shell**: logo animado próprio (Canvas puro) + shell com status honesto
  do dispositivo antes do motor VR.

## 4. Robustez / crash-safety
- ✅ Todo I/O em `Dispatchers.IO` com `runCatching`; mensagens PT-BR.
- ✅ Engines (câmera, mãos, Filament, WebView) com ciclo de vida explícito
  (stop/release/destroy) amarrado a attach/detach e onPause/onDestroy.
- ✅ `try/catch` em criação de engine 3D, binding de câmera, pickers SAF,
  intents externos e desenho do overlay.
- ✅ Consumers de `ImageProxy` fecham o proxy em todos os caminhos.
- ✅ WebView destruído no dispose; FileProvider sem export.

## 5. Responsividade
- ✅ Home usa `spreadFactor(min(1, max(0.62, w/980)))` — arcos se adaptam à largura.
- ✅ Janelas com tamanho em dp escalado pela densidade e clamp 240–760 × 160–560 dp.
- ✅ VR em `sensorLandscape`; splash/shell em portrait; `configChanges` declarados.

## 6. Verificações que só o CI/aparelho completam
- ✅ Download do `hand_landmarker.task` no build (sha256 pinado; escape
  `-PAGUS_ALLOW_MISSING_MODEL=1`) — verificado no GitHub Actions (run 34911358534).
- ✅ Compilação Kotlin real (tipos) + AAPT (recursos) — `assembleDebug` e
  `assembleRelease` passaram no GitHub Actions (run 34911358534, commit 06710ee);
  erros de tipos da rodada anterior (Filament 1.75.1, Kotlin 2.0.21) corrigidos
  e documentados no histórico do branch.
- ✅ Assinatura release com a keystore demo + `apksigner verify --print-certs`
  executado no CI (build-tools do runner) + resumo do APK (sha256, ABIs, dex,
  assets, modelo de mão) publicado no step summary da run.
- 📱 Teste em aparelho: câmera traseira real, Filament em GPU real, drag de janelas
  curvas por toque, jogos HTML; gestos de mão ficam para a build com o gate ligado.

## 7. UI espacial v2 (build UI-first)
- ✅ `CurvedSurface`: 14 fatias cilíndricas por janela, toque mapeado de volta ao
  conteúdo (botões/scroll/WebView funcionais dentro da curva).
- ✅ `EnvironmentRoom`: panorama cilíndrico 18 fatias + piso em perspectiva + brilhos;
  modo passthrough translúcido sobre a câmera traseira.
- ✅ `SpatialMath.applyCylinder`: janelas e tiles do home num cilindro côncavo ao
  redor do usuário (yaw/pitch viram azimute/elevação reais).
- ✅ Gate `BuildFlags.HAND_TRACKING = false`: motor de mãos dorme; HUD, Configurações,
  Shell e Hand Lab comunicam o status com honestidade.
- ⚠️ Redraw render-to-texture: focada ~30 fps / secundárias ~3 fps (documentado no README).

## Decisões registradas
- R8/minify desligados no release demo (superfície JNI do Filament/MediaPipe).
- Keystore demo commitada de propósito para o CI assinar (ver `keystore/README.md`);
  não usar em produção.
- Conteúdo da Store somente legal (MIT/originais); nada de terceiros é
  modificado ou redistribuído (ZArchiver só via intent).

## 8. Sistema de menus espaciais 3DOF (vr3d/)
- ✅ WorldRoot/CameraRig/menus irmãos da câmera (estabilidade por construção).
- ✅ 3DOF puro: rotation-vector → quaternion → rig; slerp suavizado + deadzone; recenter.
- ✅ SBS: 2 viewports, 2 câmeras ±IPD/2, mesma cena/frame; gaze-dwell para seleção.
- ✅ Animações abrir/fechar 0.92→1.0 + fade + dolly; sombras suaves compartilhadas.
- ✅ Fallback de performance por modo (cols da malha + px/metro da textura).
- ⚠️ Interação por toque no mono e gaze-dwell no SBS (sem controllers); arrasto de painel é
  manipulação virtual (azimute/elevação), nunca posição física — coerente com 3DOF.
