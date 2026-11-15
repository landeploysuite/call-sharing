import React, { useState, useEffect, useRef, useMemo } from 'react';
import {
  Wifi,
  Radio,
  Phone,
  PhoneIncoming,
  PhoneOff,
  Mic,
  MicOff,
  Volume2,
  Bluetooth,
  ShieldCheck,
  Activity,
  Cpu,
  BatteryCharging,
  Smartphone,
  RotateCcw,
  EyeOff,
  Zap,
  FastForward,
  Code,
  Terminal,
  CheckCircle2,
  AlertTriangle,
  Layers,
  Sliders,
  Copy,
  Check,
  RefreshCw,
  Lock,
  ArrowUpRight,
  Signal,
  Server,
  Waves,
  Settings,
  Plus,
  Trash2,
  Move,
  Maximize2,
  Minimize2,
  Grid,
  Image as ImageIcon,
  Tag,
  ChevronRight,
  ChevronDown,
  CheckSquare,
  Square,
  Sparkles,
  Clock,
  Monitor,
  Upload,
  Compass,
  Layout,
  Sun
} from 'lucide-react';

// ============================================================================
// DESIGN TOKENS & JETPACK COMPOSE / XML CODE SNIPPETS FOR SPEC MODAL
// ============================================================================
const DESIGN_SPECS = {
  colors: `<!-- CallShare P2P - AMOLED Cyber-Telephony Palette (colors.xml) -->
<resources>
    <color name="amoled_pitch_black">#000000</color>
    <color name="surface_obsidian">#0A0D12</color>
    <color name="surface_tactical_glass">#121721</color>
    <color name="border_hairline">#1E2636</color>
    
    <!-- Surgical Accents -->
    <color name="emerald_active">#10B981</color>
    <color name="cyan_opus_mesh">#06B6D4</color>
    <color name="amber_jitter_warn">#F59E0B</color>
    <color name="crimson_hangup">#EF4444</color>
    
    <!-- Typography Hierarchy -->
    <color name="text_primary">#F8FAFC</color>
    <color name="text_secondary">#94A3B8</color>
    <color name="text_telemetry_muted">#475569</color>
</resources>`,
  composeTheme: `// CallShareTheme.kt - Jetpack Compose Material3 OLED Overrides & Lissajous Container
val AmoledPitchColorScheme = darkColorScheme(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF0A0D12),
    primary = Color(0xFF10B981),     // Emerald Active
    secondary = Color(0xFF06B6D4),   // Cyan P2P Stream
    tertiary = Color(0xFFF59E0B),    // Amber Jitter/PLC
    error = Color(0xFFEF4444),       // Crimson Reject
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC)
)

@Composable
fun LissajousBurnInContainer(
    isLandscape: Boolean,
    offsetX: Float,
    offsetY: Float,
    content: @Composable BoxScope.() -> Unit
) {
    // Clamped hardware-accelerated RenderNode translation
    val maxX = if (isLandscape) 10f else 6f
    val maxY = if (isLandscape) 5f else 8f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer {
                translationX = offsetX.coerceIn(-maxX, maxX).dp.toPx()
                translationY = offsetY.coerceIn(-maxY, maxY).dp.toPx()
            },
        content = content
    )
}`,
  promptUpgrade: `Act as a Senior Android Systems & UI Architect.
Implement CallShare P2P's upgraded native modules:
1. RoleManagerHelper.kt with Android Q+ RoleManager.ROLE_DIALER intent launcher and fallback.
2. DraggableAodCanvasView.kt with normalized (x,y) coordinates persistence across Portrait and Landscape.
3. Safe-zone Lissajous clamping boundaries: Portrait ±6px X / ±8px Y; Landscape ±10px X / ±5px Y.`
};

// Default Layout Coordinate Profiles
const DEFAULT_LAYOUTS = {
  portrait: {
    clock: { x: 48, y: 140, scale: 1.0, zIndex: 10 },
    datePill: { x: 80, y: 250, scale: 1.0, zIndex: 9 },
    simBadge: { x: 95, y: 310, scale: 1.0, zIndex: 8 },
    orbitReticle: { x: 70, y: 410, scale: 1.0, zIndex: 7 }
  },
  landscape: {
    clock: { x: 40, y: 90, scale: 1.05, zIndex: 10 },
    datePill: { x: 42, y: 200, scale: 0.95, zIndex: 9 },
    simBadge: { x: 42, y: 245, scale: 0.9, zIndex: 8 },
    orbitReticle: { x: 420, y: 90, scale: 1.0, zIndex: 7 }
  }
};

export default function CallShareUI() {
  // --- NAVIGATION & SCREEN STATE ---
  // Screens: 'dashboard' | 'aod' | 'call' | 'settings'
  const [activeScreen, setActiveScreen] = useState('dashboard');
  const [showSpecsModal, setShowSpecsModal] = useState(false);
  const [copiedTab, setCopiedTab] = useState(null);

  // --- FEATURE 1: ROLEMANAGER DEFAULT CALLER STATE ---
  const [isDefaultCallerActive, setIsDefaultCallerActive] = useState(false);
  const [showRoleManagerModal, setShowRoleManagerModal] = useState(false);
  const [selectedRoleApp, setSelectedRoleApp] = useState('callshare'); // 'system' | 'callshare'
  const [relayGsmOverLan, setRelayGsmOverLan] = useState(true);
  const [autoWakeAodOnIncoming, setAutoWakeAodOnIncoming] = useState(true);
  const [exclusiveAudioFocus, setExclusiveAudioFocus] = useState(true);

  // --- FEATURE 2: SETTINGS & AOD ORIENTATION CONTROLS ---
  // Orientation Mode: 'portrait_lock' | 'landscape_lock' | 'auto_sensor'
  const [orientationMode, setOrientationMode] = useState('portrait_lock');
  const [simulatedSensorLandscape, setSimulatedSensorLandscape] = useState(false);

  // Actual rendered orientation computed from orientationMode & sensor
  const isLandscape = useMemo(() => {
    if (orientationMode === 'portrait_lock') return false;
    if (orientationMode === 'landscape_lock') return true;
    return simulatedSensorLandscape;
  }, [orientationMode, simulatedSensorLandscape]);

  // AOD Clock Theme Presets:
  // 'cyber_mesh' | 'nothing_dot' | 'analog_chrono' | 'split_flap'
  const [aodClockTheme, setAodClockTheme] = useState('cyber_mesh');

  // AMOLED & Hardware Gates
  const [screenBrightness, setScreenBrightness] = useState(0.01); // 0.01f to 0.20f
  const [orbitSpeedMultiplier, setOrbitSpeedMultiplier] = useState(1); // 1x (60s) to 20x preview

  // Hardware Sensor Simulators
  const [proximityCovered, setProximityCovered] = useState(false);
  const [incomingCallSim, setIncomingCallSim] = useState(false);
  const [jitterSpikeActive, setJitterSpikeActive] = useState(false);
  const [apIsolationFallback, setApIsolationFallback] = useState(false);

  // Audio Telemetry
  const [callDuration, setCallDuration] = useState(142);
  const [isMuted, setIsMuted] = useState(false);
  const [isOnHold, setIsOnHold] = useState(false);
  const [audioRoute, setAudioRoute] = useState('wifi_relay');
  const [ownershipRevokedMsg, setOwnershipRevokedMsg] = useState(null);
  const [jitterMs, setJitterMs] = useState(24.5);
  const [bufferFrames, setBufferFrames] = useState(2);
  const [plcActive, setPlcActive] = useState(false);
  const [prunedPackets, setPrunedPackets] = useState(0);
  const [seqNumber, setSeqNumber] = useState(65410);
  const [vadEnergy, setVadEnergy] = useState(340);
  const [hangoverFrames, setHangoverFrames] = useState(6);

  // --- CLOCK & LISSAJOUS BURN-IN ORBIT STATE ---
  const [currentTime, setCurrentTime] = useState(new Date());
  const [orbitAngle, setOrbitAngle] = useState(0);

  // Clamping boundaries per orientation:
  // Portrait: X = ±6px, Y = ±8px
  // Landscape: X = ±10px, Y = ±5px
  const maxShiftX = isLandscape ? 10 : 6;
  const maxShiftY = isLandscape ? 5 : 8;

  const lissajousOffset = useMemo(() => {
    const rawX = Math.sin(orbitAngle * 3) * maxShiftX;
    const rawY = Math.sin(orbitAngle * 2 + Math.PI / 4) * maxShiftY;
    return {
      x: Math.max(-maxShiftX, Math.min(maxShiftX, Number(rawX.toFixed(1)))),
      y: Math.max(-maxShiftY, Math.min(maxShiftY, Number(rawY.toFixed(1))))
    };
  }, [orbitAngle, maxShiftX, maxShiftY]);

  // Orbit loop
  useEffect(() => {
    const intervalMs = orbitSpeedMultiplier > 1 ? 50 : 1000;
    const timer = setInterval(() => {
      setCurrentTime(new Date());
      setOrbitAngle((prev) => (prev + (orbitSpeedMultiplier > 1 ? 0.08 : 0.015)) % (Math.PI * 2));
    }, intervalMs);
    return () => clearInterval(timer);
  }, [orbitSpeedMultiplier]);

  // Audio & telemetry loop
  useEffect(() => {
    const tick = setInterval(() => {
      setSeqNumber((prev) => (prev + 1) & 0xffff);

      if (activeScreen === 'call' && !isOnHold) {
        setCallDuration((d) => d + 1);
      }

      if (!isMuted && !isOnHold) {
        const simulatedRms = Math.floor(Math.random() * 480) + 40;
        setVadEnergy(simulatedRms);
        if (simulatedRms > 120) {
          setHangoverFrames(6);
        } else {
          setHangoverFrames((h) => Math.max(0, h - 1));
        }
      } else {
        setVadEnergy(12);
        setHangoverFrames(0);
      }

      if (jitterSpikeActive) {
        setJitterMs((prev) => {
          if (prev > 45) {
            const next = Math.max(26, prev * 0.72);
            setBufferFrames(Math.ceil(next / 20));
            setPrunedPackets((p) => p + 2);
            setPlcActive(next > 65);
            if (next <= 30) setJitterSpikeActive(false);
            return Number(next.toFixed(1));
          }
          return prev;
        });
      } else {
        const naturalJitter = 21 + Math.sin(Date.now() / 600) * 4.5;
        setJitterMs(Number(naturalJitter.toFixed(1)));
        setBufferFrames(2);
        setPlcActive(false);
      }
    }, 180);

    return () => clearInterval(tick);
  }, [activeScreen, isMuted, isOnHold, jitterSpikeActive]);

  // --- FEATURE 3: FREEFORM DRAG-AND-DROP CANVAS STATE ---
  const [isAodEditMode, setIsAodEditMode] = useState(false);
  const [isSnapGridEnabled, setIsSnapGridEnabled] = useState(true);
  const [selectedAssetId, setSelectedAssetId] = useState(null);
  const [showAssetDrawer, setShowAssetDrawer] = useState(false);

  // Asset Layout Profiles: separate coordinates for portrait and landscape
  const [layoutProfiles, setLayoutProfiles] = useState(DEFAULT_LAYOUTS);

  // Dynamic custom user assets (pills, stickers, logos)
  const [customAssets, setCustomAssets] = useState([
    {
      id: 'batteryBar',
      type: 'telemetry_battery',
      title: 'Peer Battery Bar',
      portrait: { x: 95, y: 280, scale: 1.0, zIndex: 5 },
      landscape: { x: 420, y: 240, scale: 1.0, zIndex: 5 }
    },
    {
      id: 'jitterWave',
      type: 'telemetry_jitter',
      title: 'Live Jitter Waveform',
      portrait: { x: 75, y: 350, scale: 1.0, zIndex: 6 },
      landscape: { x: 420, y: 170, scale: 1.0, zIndex: 6 }
    }
  ]);

  // Current canvas dimensions
  const canvasWidth = isLandscape ? 820 : 380;
  const canvasHeight = isLandscape ? 400 : 780;

  // Safe zone bounds (accounting for ±10px Lissajous displacement + 12px bezel)
  const safeMarginX = isLandscape ? 22 : 18;
  const safeMarginY = isLandscape ? 17 : 20;

  // Active Dragging refs
  const dragRef = useRef({
    isDragging: false,
    assetId: null,
    startX: 0,
    startY: 0,
    initialAssetX: 0,
    initialAssetY: 0
  });

  // Get current position for an asset based on orientation
  const getAssetCoord = (assetId) => {
    const mode = isLandscape ? 'landscape' : 'portrait';
    if (layoutProfiles[mode] && layoutProfiles[mode][assetId]) {
      return layoutProfiles[mode][assetId];
    }
    const foundCustom = customAssets.find((a) => a.id === assetId);
    if (foundCustom && foundCustom[mode]) {
      return foundCustom[mode];
    }
    return { x: 60, y: 120, scale: 1.0, zIndex: 1 };
  };

  // Update an asset's position in current profile
  const updateAssetCoord = (assetId, updates) => {
    const mode = isLandscape ? 'landscape' : 'portrait';

    setLayoutProfiles((prev) => {
      const currentModeLayout = { ...prev[mode] };
      const currentAssetLayout = currentModeLayout[assetId] || { x: 60, y: 120, scale: 1.0, zIndex: 1 };
      currentModeLayout[assetId] = { ...currentAssetLayout, ...updates };
      return { ...prev, [mode]: currentModeLayout };
    });

    setCustomAssets((prev) =>
      prev.map((asset) => {
        if (asset.id === assetId) {
          const currentPos = asset[mode] || { x: 60, y: 120, scale: 1.0, zIndex: 1 };
          return {
            ...asset,
            [mode]: { ...currentPos, ...updates }
          };
        }
        return asset;
      })
    );
  };

  // Pointer Drag Handlers
  const handlePointerDown = (e, assetId) => {
    if (!isAodEditMode) return;
    e.stopPropagation();
    e.currentTarget.setPointerCapture(e.pointerId);

    const currentPos = getAssetCoord(assetId);
    dragRef.current = {
      isDragging: true,
      assetId,
      startX: e.clientX,
      startY: e.clientY,
      initialAssetX: currentPos.x,
      initialAssetY: currentPos.y
    };
    setSelectedAssetId(assetId);
  };

  const handlePointerMove = (e) => {
    if (!dragRef.current.isDragging) return;
    e.preventDefault();

    const { assetId, startX, startY, initialAssetX, initialAssetY } = dragRef.current;
    let newX = initialAssetX + (e.clientX - startX);
    let newY = initialAssetY + (e.clientY - startY);

    // Grid snapping (16px grid)
    if (isSnapGridEnabled) {
      newX = Math.round(newX / 16) * 16;
      newY = Math.round(newY / 16) * 16;
    }

    // Safe zone clamping (accounting for Lissajous shift)
    const clampedX = Math.max(safeMarginX, Math.min(canvasWidth - safeMarginX - 80, newX));
    const clampedY = Math.max(safeMarginY, Math.min(canvasHeight - safeMarginY - 40, newY));

    updateAssetCoord(assetId, { x: clampedX, y: clampedY });
  };

  const handlePointerUp = (e) => {
    if (dragRef.current.isDragging) {
      dragRef.current.isDragging = false;
      dragRef.current.assetId = null;
    }
  };

  // Custom asset creation
  const handleAddCustomPill = (type, label) => {
    const id = `asset_${Date.now()}`;
    const newAsset = {
      id,
      type,
      title: label,
      portrait: { x: 60, y: 220 + customAssets.length * 40, scale: 1.0, zIndex: 10 + customAssets.length },
      landscape: { x: 420, y: 80 + customAssets.length * 40, scale: 1.0, zIndex: 10 + customAssets.length }
    };
    setCustomAssets((prev) => [...prev, newAsset]);
    setSelectedAssetId(id);
    setShowAssetDrawer(false);
  };

  const handleCustomImageUpload = (e) => {
    const file = e.target.files?.[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (event) => {
        const id = `sticker_${Date.now()}`;
        const newAsset = {
          id,
          type: 'custom_image',
          title: file.name,
          dataUrl: event.target.result,
          portrait: { x: 130, y: 380, scale: 1.0, zIndex: 15 },
          landscape: { x: 380, y: 150, scale: 1.0, zIndex: 15 }
        };
        setCustomAssets((prev) => [...prev, newAsset]);
        setSelectedAssetId(id);
        setShowAssetDrawer(false);
      };
      reader.readAsDataURL(file);
    }
  };

  const handleDeleteAsset = (id) => {
    setCustomAssets((prev) => prev.filter((a) => a.id !== id));
    if (selectedAssetId === id) setSelectedAssetId(null);
  };

  const handleResetLayout = () => {
    const mode = isLandscape ? 'landscape' : 'portrait';
    setLayoutProfiles((prev) => ({
      ...prev,
      [mode]: { ...DEFAULT_LAYOUTS[mode] }
    }));
  };

  const copyToClipboard = (key, text) => {
    navigator.clipboard.writeText(text);
    setCopiedTab(key);
    setTimeout(() => setCopiedTab(null), 2000);
  };

  return (
    <div className="min-h-screen bg-[#05070A] text-slate-100 font-sans selection:bg-emerald-500/30 selection:text-emerald-200 flex flex-col justify-between overflow-x-hidden">
      {/* =================================================================== */}
      {/* TOP ENGINEERING CONTROL DECK                                        */}
      {/* =================================================================== */}
      <header className="border-b border-slate-800/80 bg-[#080B10]/95 backdrop-blur-xl sticky top-0 z-40 px-4 py-3 shadow-md">
        <div className="max-w-7xl mx-auto flex flex-col lg:flex-row items-start lg:items-center justify-between gap-3">
          {/* Brand & Default Caller Status */}
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-black border border-emerald-500/40 flex items-center justify-center shadow-[0_0_20px_rgba(16,185,129,0.25)]">
              <Radio className="w-5 h-5 text-emerald-400 animate-pulse" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <h1 className="font-bold tracking-tight text-sm sm:text-base text-white">
                  CALLSHARE <span className="text-emerald-400 font-mono">P2P</span>
                </h1>
                {isDefaultCallerActive ? (
                  <span className="text-[10px] font-mono uppercase px-2 py-0.5 rounded-full bg-emerald-500/20 text-emerald-300 border border-emerald-500/50 flex items-center gap-1 shadow-[0_0_10px_rgba(16,185,129,0.2)]">
                    <ShieldCheck className="w-3 h-3 text-emerald-400" />
                    DEFAULT CALLER ACTIVE
                  </span>
                ) : (
                  <button
                    onClick={() => setShowRoleManagerModal(true)}
                    className="text-[10px] font-mono uppercase px-2 py-0.5 rounded-full bg-amber-500/15 text-amber-300 border border-amber-500/40 hover:bg-amber-500/25 transition-all flex items-center gap-1"
                  >
                    <AlertTriangle className="w-3 h-3 text-amber-400" />
                    Set Default Caller
                  </button>
                )}
              </div>
              <p className="text-xs text-slate-400 font-mono">
                True #000000 AMOLED • RoleManager Dialer • Freeform AOD Studio
              </p>
            </div>
          </div>

          {/* Navigation Tabs (Dashboard, AOD, Call, Settings) */}
          <div className="flex flex-wrap items-center gap-1.5 bg-black/80 p-1 rounded-xl border border-slate-800">
            {[
              { id: 'dashboard', label: '1. Mesh Dashboard', icon: Wifi },
              { id: 'aod', label: '2. Universal AOD', icon: Smartphone },
              { id: 'call', label: '3. Active Call', icon: Phone },
              { id: 'settings', label: '4. Settings & Studio', icon: Settings }
            ].map((tab) => {
              const Icon = tab.icon;
              const active = activeScreen === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveScreen(tab.id)}
                  className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-all ${
                    active
                      ? 'bg-emerald-500/15 text-emerald-300 border border-emerald-500/40 shadow-[0_0_12px_rgba(16,185,129,0.15)]'
                      : 'text-slate-400 hover:text-slate-200 hover:bg-slate-900'
                  }`}
                >
                  <Icon className="w-3.5 h-3.5" />
                  <span>{tab.label}</span>
                </button>
              );
            })}
          </div>

          {/* Quick Actions (Specs Modal & System Role Trigger) */}
          <div className="flex items-center gap-2 self-end lg:self-auto">
            <button
              onClick={() => setShowRoleManagerModal(true)}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-mono bg-emerald-950/40 hover:bg-emerald-900/50 text-emerald-300 border border-emerald-500/30 transition-all"
            >
              <ShieldCheck className="w-3.5 h-3.5 text-emerald-400" />
              <span>RoleManager</span>
            </button>

            <button
              onClick={() => setShowSpecsModal(true)}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl text-xs font-mono bg-slate-900 hover:bg-slate-800 text-cyan-400 border border-cyan-500/30 transition-all"
            >
              <Code className="w-3.5 h-3.5" />
              <span className="hidden sm:inline">Specs / Code</span>
            </button>
          </div>
        </div>

        {/* Sub-Bar: Hardware Simulators & Deformation Matrix */}
        <div className="max-w-7xl mx-auto mt-2.5 pt-2.5 border-t border-slate-800/60 flex flex-wrap items-center justify-between gap-2 text-xs">
          <div className="flex items-center gap-2 text-slate-400 font-mono">
            <Sliders className="w-3.5 h-3.5 text-cyan-400" />
            <span className="uppercase tracking-wider text-[11px] text-slate-300">
              Live Hardware & Network Controls:
            </span>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/* Incoming Call Simulation */}
            <button
              onClick={() => {
                const next = !incomingCallSim;
                setIncomingCallSim(next);
                if (next && activeScreen === 'dashboard') setActiveScreen('aod');
              }}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg font-mono text-[11px] border transition-all ${
                incomingCallSim
                  ? 'bg-emerald-500/20 border-emerald-400 text-emerald-300 animate-pulse'
                  : 'bg-slate-900/70 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              <PhoneIncoming className="w-3 h-3" />
              <span>{incomingCallSim ? 'Ringing (30-FPS Ring Pulse)' : 'Simulate Call'}</span>
            </button>

            {/* Proximity Sensor Gate */}
            <button
              onClick={() => setProximityCovered((p) => !p)}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg font-mono text-[11px] border transition-all ${
                proximityCovered
                  ? 'bg-amber-500/20 border-amber-400 text-amber-300'
                  : 'bg-slate-900/70 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              <EyeOff className="w-3 h-3" />
              <span>Proximity: {proximityCovered ? 'NEAR (0 cm - 100% Blanked)' : 'FAR (5 cm)'}</span>
            </button>

            {/* Wi-Fi Burst Jitter */}
            <button
              onClick={() => {
                setJitterSpikeActive(true);
                setJitterMs(395.0);
                setBufferFrames(20);
                setPlcActive(true);
              }}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg font-mono text-[11px] border transition-all ${
                jitterSpikeActive
                  ? 'bg-amber-500/20 border-amber-400 text-amber-300'
                  : 'bg-slate-900/70 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              <Zap className="w-3 h-3" />
              <span>{jitterSpikeActive ? `Pruning (${jitterMs}ms)` : 'Spike Jitter (400ms)'}</span>
            </button>

            {/* Orbit Speed Preview */}
            <button
              onClick={() => setOrbitSpeedMultiplier((m) => (m === 1 ? 20 : 1))}
              className={`flex items-center gap-1.5 px-2.5 py-1 rounded-lg font-mono text-[11px] border transition-all ${
                orbitSpeedMultiplier > 1
                  ? 'bg-cyan-500/20 border-cyan-400 text-cyan-300'
                  : 'bg-slate-900/70 border-slate-800 text-slate-400 hover:text-slate-200'
              }`}
            >
              <FastForward className="w-3 h-3" />
              <span>Lissajous: {orbitSpeedMultiplier > 1 ? '20x Fast-Forward' : '1x (60s)'}</span>
            </button>
          </div>
        </div>
      </header>

      {/* =================================================================== */}
      {/* MAIN WORKSPACE                                                      */}
      {/* =================================================================== */}
      <main
        onPointerMove={handlePointerMove}
        onPointerUp={handlePointerUp}
        className="flex-1 max-w-7xl w-full mx-auto px-4 py-6 flex flex-col xl:flex-row items-center justify-center gap-8 select-none"
      >
        {/* LEFT COLUMN: ANDROID PHYSICAL EMULATOR FRAME */}
        <div className="flex flex-col items-center justify-center flex-1 w-full max-w-full">
          {/* Hardware Frame Outer Bezel */}
          <div
            style={{
              width: `${canvasWidth}px`,
              height: `${canvasHeight}px`
            }}
            className="relative bg-[#000000] rounded-[44px] border-[6px] border-[#182030] shadow-[0_0_80px_rgba(0,0,0,0.98),0_0_30px_rgba(16,185,129,0.06)] transition-all duration-500 ease-out overflow-hidden flex flex-col justify-between"
          >
            {/* AMOLED Software Dimming Filter (screenBrightness slider) */}
            <div
              style={{
                opacity: 1.0 - Math.min(1.0, screenBrightness * 3.5)
              }}
              className="absolute inset-0 bg-black/60 pointer-events-none z-30 transition-opacity"
            />

            {/* Punch-Hole Camera & Proximity Sensor */}
            <div
              className={`z-40 pointer-events-none flex items-center justify-center ${
                isLandscape
                  ? 'absolute left-3 top-1/2 -translate-y-1/2 flex-col gap-2'
                  : 'w-full pt-2.5 flex-row gap-2'
              }`}
            >
              <div className="w-3 h-3 rounded-full bg-[#090D14] border border-slate-800 flex items-center justify-center">
                <div className="w-1 h-1 rounded-full bg-cyan-950" />
              </div>
              <div
                title="Sensor.TYPE_PROXIMITY"
                className={`w-2 h-2 rounded-full transition-colors ${
                  proximityCovered ? 'bg-red-500 animate-ping' : 'bg-emerald-500/30'
                }`}
              />
            </div>

            {/* PROXIMITY SENSOR POWER GATE BLANKING OVERLAY */}
            {proximityCovered && (
              <div className="absolute inset-0 z-50 bg-[#000000] flex flex-col items-center justify-center p-6 text-center">
                <div className="w-12 h-12 rounded-full border border-slate-900 flex items-center justify-center mb-3">
                  <EyeOff className="w-5 h-5 text-slate-700" />
                </div>
                <p className="font-mono text-xs uppercase tracking-widest text-slate-600">
                  sensor_gate.rs • PROXIMITY_NEAR (0.0 cm)
                </p>
                <p className="text-[11px] text-slate-700 font-mono mt-1 max-w-xs">
                  AMOLED Matrix 100% Blanked • 0.00mW Display Draw • Audio Bridge Running in Background
                </p>
                <button
                  onClick={() => setProximityCovered(false)}
                  className="mt-6 px-4 py-2 rounded-full bg-slate-950 border border-slate-800 text-slate-400 hover:text-white text-xs font-mono transition-all"
                >
                  Uncover Sensor
                </button>
              </div>
            )}

            {/* TOP ANDROID SYSTEM STATUS BAR */}
            <div
              className={`px-6 pt-2 pb-1 flex items-center justify-between text-[11px] font-mono text-slate-400 z-30 ${
                isLandscape ? 'pl-10' : ''
              }`}
            >
              <div className="flex items-center gap-2">
                <span className="text-white font-semibold">
                  {currentTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}
                </span>
                <span className="text-[9px] px-1.5 py-0.2 rounded bg-emerald-500/15 text-emerald-400 border border-emerald-500/30">
                  LAN ONLY
                </span>
              </div>
              <div className="flex items-center gap-2.5">
                <span className="text-cyan-400 flex items-center gap-1">
                  <Radio className="w-3 h-3" /> P2P
                </span>
                <span className="text-emerald-400">88%</span>
                <BatteryCharging className="w-3.5 h-3.5 text-emerald-400" />
              </div>
            </div>

            {/* =============================================================== */}
            {/* TAB 1: MESH DASHBOARD (MainActivity.kt)                          */}
            {/* =============================================================== */}
            {activeScreen === 'dashboard' && (
              <div
                className={`flex-1 bg-[#000000] px-5 py-3 overflow-y-auto ${
                  isLandscape ? 'pl-10 grid grid-cols-12 gap-4 items-start' : 'flex flex-col gap-3.5'
                }`}
              >
                <div className={`${isLandscape ? 'col-span-6' : ''} flex flex-col gap-3`}>
                  {/* Host Node Header */}
                  <div className="flex items-center justify-between border-b border-slate-900 pb-2">
                    <div>
                      <span className="text-[10px] font-mono uppercase tracking-widest text-emerald-400 block">
                        discovery.rs • UDP :45781
                      </span>
                      <h2 className="text-base font-bold text-white tracking-tight">
                        Local Mesh Node #01
                      </h2>
                    </div>
                    <div className="text-right font-mono">
                      <span className="text-[10px] text-slate-400 block">HOST IP</span>
                      <span className="text-xs text-cyan-400 font-semibold">192.168.1.104</span>
                    </div>
                  </div>

                  {/* Discovered Peer Card */}
                  <div className="p-3.5 rounded-2xl bg-[#070A0F] border border-emerald-500/30 relative overflow-hidden">
                    <div className="flex items-start justify-between">
                      <div className="flex items-center gap-2.5">
                        <div className="w-9 h-9 rounded-xl bg-emerald-500/10 border border-emerald-500/40 flex items-center justify-center">
                          <Smartphone className="w-4 h-4 text-emerald-400" />
                        </div>
                        <div>
                          <div className="flex items-center gap-1.5">
                            <span className="text-xs font-bold text-white">Pixel 9 Pro (Peer)</span>
                            <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
                          </div>
                          <p className="text-[11px] font-mono text-slate-400">
                            IP: 192.168.1.119 • Jio 5G SIM Ready
                          </p>
                        </div>
                      </div>
                      <span className="text-[10px] font-mono px-2 py-0.5 rounded-md bg-emerald-500/15 text-emerald-300 border border-emerald-500/30">
                        RTT 1.4ms
                      </span>
                    </div>

                    <div className="grid grid-cols-3 gap-2 mt-3 pt-2.5 border-t border-slate-900 font-mono text-[10px]">
                      <div className="bg-black p-2 rounded-lg border border-slate-900">
                        <span className="text-slate-500 block">SIGNALING</span>
                        <span className="text-emerald-400">TCP :45782</span>
                      </div>
                      <div className="bg-black p-2 rounded-lg border border-slate-900">
                        <span className="text-slate-500 block">MEDIA</span>
                        <span className="text-cyan-400">UDP :45783</span>
                      </div>
                      <div className="bg-black p-2 rounded-lg border border-slate-900">
                        <span className="text-slate-500 block">DPD HEARTBEAT</span>
                        <span className="text-emerald-400">500ms OK</span>
                      </div>
                    </div>
                  </div>

                  {/* AP Isolation Matrix */}
                  <div className="p-3 rounded-2xl bg-[#070A0F] border border-slate-800 flex items-center justify-between">
                    <div className="pr-2">
                      <div className="flex items-center gap-1.5">
                        <span className="text-xs font-semibold text-white">AP Isolation Fallback</span>
                        <span className="text-[9px] font-mono px-1.5 py-0.2 rounded bg-cyan-500/15 text-cyan-300">
                          Wi-Fi Direct
                        </span>
                      </div>
                      <p className="text-[10px] text-slate-400 font-mono mt-0.5">
                        {apIsolationFallback
                          ? 'Active: LocalOnlyHotspot P2P (Bypassing Router)'
                          : 'Standby: Multicast 239.255.255.250'}
                      </p>
                    </div>
                    <button
                      onClick={() => setApIsolationFallback((v) => !v)}
                      className={`px-2.5 py-1.5 rounded-xl font-mono text-[10px] border transition-all shrink-0 ${
                        apIsolationFallback
                          ? 'bg-cyan-500/20 border-cyan-400 text-cyan-300'
                          : 'bg-black border-slate-800 text-slate-400'
                      }`}
                    >
                      {apIsolationFallback ? 'HOTSPOT ON' : 'MULTICAST'}
                    </button>
                  </div>
                </div>

                <div className={`${isLandscape ? 'col-span-6' : ''} flex flex-col gap-3`}>
                  {/* Default Caller Status Banner */}
                  <div className="p-3.5 rounded-2xl bg-[#070A0F] border border-slate-900 space-y-2.5">
                    <div className="flex items-center justify-between">
                      <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400">
                        RoleManager Status
                      </span>
                      {isDefaultCallerActive ? (
                        <span className="text-[10px] font-mono text-emerald-400 flex items-center gap-1">
                          <CheckCircle2 className="w-3 h-3" /> VERIFIED
                        </span>
                      ) : (
                        <span className="text-[10px] font-mono text-amber-400">UNREGISTERED</span>
                      )}
                    </div>

                    <div className="flex items-center justify-between text-xs font-mono bg-black p-2 rounded-lg border border-slate-900">
                      <div>
                        <span className="text-white font-semibold block">ROLE_DIALER</span>
                        <span className="text-[10px] text-slate-400">
                          {isDefaultCallerActive ? 'Default Caller Granted' : 'System Phone is Default'}
                        </span>
                      </div>
                      <button
                        onClick={() => setShowRoleManagerModal(true)}
                        className="px-2.5 py-1 rounded bg-emerald-500/15 hover:bg-emerald-500/25 border border-emerald-500/40 text-emerald-300 text-[10px]"
                      >
                        {isDefaultCallerActive ? 'Configure' : 'Claim Role'}
                      </button>
                    </div>
                  </div>

                  {/* Navigation shortcuts */}
                  <div className="grid grid-cols-2 gap-2.5">
                    <button
                      onClick={() => setActiveScreen('aod')}
                      className="p-3 rounded-2xl bg-black hover:bg-slate-950 border border-slate-800 hover:border-cyan-500/50 text-left transition-all group"
                    >
                      <Smartphone className="w-4 h-4 text-cyan-400 mb-1 group-hover:scale-110 transition-transform" />
                      <span className="text-xs font-bold text-white block">Enter Universal AOD</span>
                      <span className="text-[10px] font-mono text-slate-500">1-FPS Ambient + Drag Canvas</span>
                    </button>

                    <button
                      onClick={() => setActiveScreen('settings')}
                      className="p-3 rounded-2xl bg-black hover:bg-slate-950 border border-slate-800 hover:border-emerald-500/50 text-left transition-all group"
                    >
                      <Layout className="w-4 h-4 text-emerald-400 mb-1 group-hover:scale-110 transition-transform" />
                      <span className="text-xs font-bold text-white block">AOD Studio & Settings</span>
                      <span className="text-[10px] font-mono text-slate-500">Presets • Orientations • Assets</span>
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* =============================================================== */}
            {/* TAB 2: UNIVERSAL ALWAYS-ON DISPLAY & DRAGGABLE CANVAS           */}
            {/* =============================================================== */}
            {activeScreen === 'aod' && (
              <div className="flex-1 bg-[#000000] relative overflow-hidden flex flex-col justify-between p-4">
                {/* Top Control Overlay for AOD (Edit Mode Toggle, Snap Grid, Add Asset) */}
                <div className={`flex items-center justify-between z-20 ${isLandscape ? 'pl-6' : ''}`}>
                  <div className="flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#06080C] border border-slate-900 font-mono text-[10px]">
                    <span
                      className={`w-1.5 h-1.5 rounded-full ${
                        incomingCallSim ? 'bg-emerald-400 animate-ping' : 'bg-cyan-400'
                      }`}
                    />
                    <span className="text-slate-400">
                      {incomingCallSim
                        ? '30-FPS RENDERTHREAD'
                        : isAodEditMode
                        ? 'STUDIO EDIT MODE'
                        : '1-FPS AMBIENT IDLE'}
                    </span>
                  </div>

                  <div className="flex items-center gap-1.5">
                    {/* Snap Grid Toggle */}
                    {isAodEditMode && (
                      <button
                        onClick={() => setIsSnapGridEnabled((s) => !s)}
                        className={`p-1.5 rounded-lg border font-mono text-[10px] flex items-center gap-1 transition-all ${
                          isSnapGridEnabled
                            ? 'bg-cyan-500/20 border-cyan-400 text-cyan-300'
                            : 'bg-black border-slate-800 text-slate-500'
                        }`}
                        title="Toggle 16px Alignment Snap Grid"
                      >
                        <Grid className="w-3 h-3" />
                        <span>Snap</span>
                      </button>
                    )}

                    {/* Add Custom Asset Drawer Button */}
                    {isAodEditMode && (
                      <button
                        onClick={() => setShowAssetDrawer(true)}
                        className="px-2 py-1 rounded-lg bg-emerald-500/20 border border-emerald-400/50 text-emerald-300 font-mono text-[10px] flex items-center gap-1"
                      >
                        <Plus className="w-3 h-3" />
                        <span>Asset</span>
                      </button>
                    )}

                    {/* Edit Mode Toggle Button */}
                    <button
                      onClick={() => {
                        setIsAodEditMode((m) => !m);
                        setSelectedAssetId(null);
                      }}
                      className={`px-2.5 py-1 rounded-full font-mono text-[10px] border transition-all flex items-center gap-1 ${
                        isAodEditMode
                          ? 'bg-emerald-500 text-black font-bold border-emerald-400 shadow-[0_0_15px_rgba(16,185,129,0.4)]'
                          : 'bg-[#06080C] border-slate-800 text-slate-400 hover:text-white'
                      }`}
                    >
                      <Move className="w-3 h-3" />
                      <span>{isAodEditMode ? 'Done Editing' : 'Customize Canvas'}</span>
                    </button>
                  </div>
                </div>

                {/* DRAGGABLE AOD CANVAS AREA */}
                <div
                  style={{
                    transform: isAodEditMode
                      ? 'none'
                      : `translate(${lissajousOffset.x}px, ${lissajousOffset.y}px)`
                  }}
                  className="relative flex-1 w-full my-auto transition-transform duration-300 overflow-hidden"
                >
                  {/* Visual Snap-Grid Guides (Visible in Edit Mode) */}
                  {isAodEditMode && (
                    <div className="absolute inset-0 pointer-events-none z-0 opacity-20">
                      <div
                        className="w-full h-full"
                        style={{
                          backgroundImage:
                            'radial-gradient(circle, #06B6D4 1px, transparent 1px)',
                          backgroundSize: '16px 16px'
                        }}
                      />
                    </div>
                  )}

                  {/* Safe Zone Bounding Box Outline (Lissajous Clamping Margin ±10px) */}
                  {isAodEditMode && (
                    <div
                      style={{
                        top: `${safeMarginY}px`,
                        bottom: `${safeMarginY}px`,
                        left: `${safeMarginX}px`,
                        right: `${safeMarginX}px`
                      }}
                      className="absolute border border-dashed border-emerald-500/40 rounded-2xl pointer-events-none z-0 flex items-start justify-end p-1.5"
                    >
                      <span className="text-[9px] font-mono text-emerald-500/70 bg-black/80 px-1 rounded">
                        Lissajous Safe Zone (±{maxShiftX}px X / ±{maxShiftY}px Y)
                      </span>
                    </div>
                  )}

                  {/* =========================================================== */}
                  {/* ASSET 1: DYNAMIC CLOCK THEME PRESETS                        */}
                  {/* =========================================================== */}
                  <div
                    onPointerDown={(e) => handlePointerDown(e, 'clock')}
                    style={{
                      left: `${getAssetCoord('clock').x}px`,
                      top: `${getAssetCoord('clock').y}px`,
                      transform: `scale(${getAssetCoord('clock').scale})`,
                      zIndex: getAssetCoord('clock').zIndex
                    }}
                    className={`absolute cursor-move transition-shadow ${
                      isAodEditMode && selectedAssetId === 'clock'
                        ? 'ring-2 ring-emerald-400 rounded-2xl shadow-[0_0_20px_rgba(16,185,129,0.3)] bg-black/60 p-2'
                        : ''
                    }`}
                  >
                    {/* Theme 1: Cyber-Mesh Minimal */}
                    {aodClockTheme === 'cyber_mesh' && (
                      <div className="flex flex-col items-center">
                        <div className="text-6xl sm:text-7xl font-extralight tracking-tighter text-white font-mono leading-none">
                          {currentTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}
                        </div>
                        <div className="mt-1 flex items-center gap-2 text-[10px] font-mono text-cyan-400">
                          <span>UDP :45781</span>
                          <span>•</span>
                          <span>RTT 1.2ms</span>
                          <span>•</span>
                          <span>JITTER {jitterMs}ms</span>
                        </div>
                      </div>
                    )}

                    {/* Theme 2: Nothing-OS Dot Matrix */}
                    {aodClockTheme === 'nothing_dot' && (
                      <div className="flex flex-col items-center">
                        <div className="relative font-mono font-black text-6xl sm:text-7xl tracking-widest text-slate-100 flex items-center gap-1">
                          <span className="text-red-500 animate-pulse text-2xl absolute -top-1 -right-4">●</span>
                          <span>{currentTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })}</span>
                        </div>
                        <div className="mt-1 px-2 py-0.5 rounded bg-slate-900 border border-slate-800 text-[10px] font-mono tracking-widest uppercase text-slate-300">
                          NOTHING // P2P_ACTIVE // BAT_88%
                        </div>
                      </div>
                    )}

                    {/* Theme 3: Analog Chrono-Ring */}
                    {aodClockTheme === 'analog_chrono' && (
                      <div className="flex flex-col items-center">
                        <div className="relative w-36 h-36 rounded-full border-2 border-slate-800 bg-[#020406] flex items-center justify-center shadow-lg">
                          {/* Radial Gauge Arc for Battery */}
                          <svg className="absolute inset-0 w-full h-full -rotate-90">
                            <circle
                              cx="72"
                              cy="72"
                              r="64"
                              fill="none"
                              stroke="#0A0F1A"
                              strokeWidth="4"
                            />
                            <circle
                              cx="72"
                              cy="72"
                              r="64"
                              fill="none"
                              stroke="#10B981"
                              strokeWidth="4"
                              strokeDasharray="402"
                              strokeDashoffset="60"
                              strokeLinecap="round"
                            />
                          </svg>

                          {/* Dial Hour / Minute / Second Hands */}
                          {(() => {
                            const hours = currentTime.getHours() % 12;
                            const minutes = currentTime.getMinutes();
                            const seconds = currentTime.getSeconds();
                            const hourDeg = (hours + minutes / 60) * 30;
                            const minDeg = (minutes + seconds / 60) * 6;
                            const secDeg = seconds * 6;

                            return (
                              <>
                                <div
                                  style={{ transform: `rotate(${hourDeg}deg)` }}
                                  className="absolute w-1 h-10 bg-white rounded-full origin-bottom top-8"
                                />
                                <div
                                  style={{ transform: `rotate(${minDeg}deg)` }}
                                  className="absolute w-0.5 h-14 bg-cyan-400 rounded-full origin-bottom top-4"
                                />
                                <div
                                  style={{ transform: `rotate(${secDeg}deg)` }}
                                  className="absolute w-[1px] h-16 bg-red-500 origin-bottom top-2"
                                />
                                <div className="w-2.5 h-2.5 rounded-full bg-white z-10" />
                              </>
                            );
                          })()}
                        </div>
                        <span className="mt-1 font-mono text-xs text-slate-400">
                          {currentTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                        </span>
                      </div>
                    )}

                    {/* Theme 4: Split-Flap Nightstand */}
                    {aodClockTheme === 'split_flap' && (
                      <div className="flex items-center gap-2">
                        {currentTime
                          .toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hour12: false })
                          .split(':')
                          .map((val, idx) => (
                            <React.Fragment key={idx}>
                              {idx > 0 && <span className="text-4xl font-mono text-emerald-400">:</span>}
                              <div className="relative bg-[#0E131F] border border-slate-700/80 rounded-xl px-4 py-3 shadow-2xl flex flex-col items-center">
                                <div className="text-5xl font-mono font-bold text-white tracking-tight">
                                  {val}
                                </div>
                                <div className="w-full h-[1px] bg-black shadow-inner my-0.5" />
                                <div className="text-[9px] font-mono text-slate-500 uppercase tracking-widest">
                                  {idx === 0 ? 'HR' : 'MIN'}
                                </div>
                              </div>
                            </React.Fragment>
                          ))}
                      </div>
                    )}
                  </div>

                  {/* =========================================================== */}
                  {/* ASSET 2: DATE & STATUS PILL                                 */}
                  {/* =========================================================== */}
                  <div
                    onPointerDown={(e) => handlePointerDown(e, 'datePill')}
                    style={{
                      left: `${getAssetCoord('datePill').x}px`,
                      top: `${getAssetCoord('datePill').y}px`,
                      transform: `scale(${getAssetCoord('datePill').scale})`,
                      zIndex: getAssetCoord('datePill').zIndex
                    }}
                    className={`absolute cursor-move flex items-center gap-2 text-xs font-mono uppercase tracking-widest text-slate-400 ${
                      isAodEditMode && selectedAssetId === 'datePill'
                        ? 'ring-2 ring-emerald-400 rounded-lg p-1 bg-black/60'
                        : ''
                    }`}
                  >
                    <span>{currentTime.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' })}</span>
                    <span>•</span>
                    <span className="text-emerald-400">SIM 1 BRIDGE</span>
                  </div>

                  {/* =========================================================== */}
                  {/* ASSET 3: SIM CARRIER BADGE                                  */}
                  {/* =========================================================== */}
                  <div
                    onPointerDown={(e) => handlePointerDown(e, 'simBadge')}
                    style={{
                      left: `${getAssetCoord('simBadge').x}px`,
                      top: `${getAssetCoord('simBadge').y}px`,
                      transform: `scale(${getAssetCoord('simBadge').scale})`,
                      zIndex: getAssetCoord('simBadge').zIndex
                    }}
                    className={`absolute cursor-move flex items-center gap-1.5 px-2.5 py-1 rounded-xl bg-[#080D17] border border-slate-800 text-[10px] font-mono ${
                      isAodEditMode && selectedAssetId === 'simBadge'
                        ? 'ring-2 ring-emerald-400 shadow-md'
                        : ''
                    }`}
                  >
                    <Signal className="w-3 h-3 text-emerald-400" />
                    <span className="text-slate-300">JIO 5G TRUE UNLIMITED</span>
                  </div>

                  {/* =========================================================== */}
                  {/* ASSET 4: LISSAJOUS ORBIT RETICLE / MINI RADAR               */}
                  {/* =========================================================== */}
                  <div
                    onPointerDown={(e) => handlePointerDown(e, 'orbitReticle')}
                    style={{
                      left: `${getAssetCoord('orbitReticle').x}px`,
                      top: `${getAssetCoord('orbitReticle').y}px`,
                      transform: `scale(${getAssetCoord('orbitReticle').scale})`,
                      zIndex: getAssetCoord('orbitReticle').zIndex
                    }}
                    className={`absolute cursor-move inline-flex items-center gap-2 px-3 py-1.5 rounded-xl bg-[#05070B] border border-slate-900 ${
                      isAodEditMode && selectedAssetId === 'orbitReticle'
                        ? 'ring-2 ring-emerald-400 shadow-md'
                        : ''
                    }`}
                  >
                    <div className="relative w-6 h-6 rounded bg-black border border-slate-800 flex items-center justify-center">
                      <div
                        style={{
                          transform: `translate(${lissajousOffset.x * 0.8}px, ${lissajousOffset.y * 0.8}px)`
                        }}
                        className="w-1.5 h-1.5 rounded-full bg-cyan-400 shadow-[0_0_6px_#06B6D4]"
                      />
                    </div>
                    <div className="text-left font-mono text-[9px]">
                      <span className="text-slate-300 block">Lissajous Clamped</span>
                      <span className="text-slate-500">
                        ±{maxShiftX}px X / ±{maxShiftY}px Y
                      </span>
                    </div>
                  </div>

                  {/* =========================================================== */}
                  {/* USER DYNAMIC ASSETS (Custom Telemetry Pills & Stickers)     */}
                  {/* =========================================================== */}
                  {customAssets.map((asset) => {
                    const pos = getAssetCoord(asset.id);
                    const isSelected = isAodEditMode && selectedAssetId === asset.id;

                    return (
                      <div
                        key={asset.id}
                        onPointerDown={(e) => handlePointerDown(e, asset.id)}
                        style={{
                          left: `${pos.x}px`,
                          top: `${pos.y}px`,
                          transform: `scale(${pos.scale})`,
                          zIndex: pos.zIndex
                        }}
                        className={`absolute cursor-move transition-all ${
                          isSelected
                            ? 'ring-2 ring-emerald-400 rounded-xl bg-black/70 p-1 shadow-lg'
                            : ''
                        }`}
                      >
                        {/* Type 1: Peer Battery Bar */}
                        {asset.type === 'telemetry_battery' && (
                          <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-[#070B12] border border-slate-800 text-[11px] font-mono">
                            <BatteryCharging className="w-4 h-4 text-emerald-400" />
                            <div className="flex flex-col">
                              <span className="text-slate-300 text-[10px]">Pixel 9 Pro</span>
                              <div className="w-16 h-1.5 rounded-full bg-slate-900 overflow-hidden mt-0.5">
                                <div className="h-full bg-emerald-400 rounded-full w-[88%]" />
                              </div>
                            </div>
                            <span className="text-emerald-400 font-bold ml-1">88%</span>
                          </div>
                        )}

                        {/* Type 2: Live Jitter Waveform */}
                        {asset.type === 'telemetry_jitter' && (
                          <div className="flex items-center gap-2 px-3 py-1.5 rounded-xl bg-[#070B12] border border-slate-800 text-[10px] font-mono">
                            <Activity className="w-3.5 h-3.5 text-cyan-400" />
                            <span className="text-slate-400">Jitter:</span>
                            <span className="text-cyan-300 font-bold">{jitterMs} ms</span>
                            <div className="flex items-end gap-0.5 h-3 ml-1">
                              {[8, 14, 6, 12, 10].map((h, i) => (
                                <div
                                  key={i}
                                  style={{ height: `${h}px` }}
                                  className="w-1 bg-cyan-400 rounded-t"
                                />
                              ))}
                            </div>
                          </div>
                        )}

                        {/* Type 3: Quick Mute Toggle */}
                        {asset.type === 'quick_mute' && (
                          <button
                            onClick={(e) => {
                              if (!isAodEditMode) {
                                e.stopPropagation();
                                setIsMuted((m) => !m);
                              }
                            }}
                            className={`flex items-center gap-1.5 px-3 py-1.5 rounded-xl border text-[10px] font-mono transition-all ${
                              isMuted
                                ? 'bg-amber-500/20 border-amber-400 text-amber-300'
                                : 'bg-[#070B12] border-slate-800 text-slate-300'
                            }`}
                          >
                            {isMuted ? <MicOff className="w-3.5 h-3.5" /> : <Mic className="w-3.5 h-3.5" />}
                            <span>{isMuted ? 'UNMUTE MIC' : 'MUTE MIC'}</span>
                          </button>
                        )}

                        {/* Type 4: Custom Text Label */}
                        {asset.type === 'custom_text' && (
                          <div className="px-3 py-1 rounded-xl bg-[#070B12] border border-slate-800 text-xs font-mono text-emerald-300 flex items-center gap-1">
                            <Tag className="w-3 h-3 text-emerald-400" />
                            <span>{asset.title}</span>
                          </div>
                        )}

                        {/* Type 5: Custom Image Sticker */}
                        {asset.type === 'custom_image' && (
                          <div className="relative group">
                            <img
                              src={asset.dataUrl}
                              alt={asset.title}
                              className="w-14 h-14 object-contain filter drop-shadow-[0_0_10px_rgba(6,182,212,0.4)]"
                            />
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* Incoming Call Overlay on AOD (If simulated call is ringing) */}
                {incomingCallSim && (
                  <div className="p-4 rounded-3xl bg-[#060C0A] border border-emerald-500/60 shadow-[0_0_40px_rgba(16,185,129,0.25)] flex flex-col items-center text-center z-30 mb-2">
                    <div className="w-12 h-12 rounded-full bg-emerald-500/20 border border-emerald-400 flex items-center justify-center animate-bounce mb-2">
                      <PhoneIncoming className="w-5 h-5 text-emerald-300" />
                    </div>
                    <span className="text-[10px] font-mono uppercase tracking-widest text-emerald-400">
                      RELAYED VIA PHONE 1 (JIO 5G)
                    </span>
                    <h3 className="text-lg font-bold text-white mt-0.5">Work Boss • +91 98765 43210</h3>
                    <p className="text-[11px] font-mono text-slate-400 mt-0.5">
                      Opus 16kHz Stream Ready • &lt;25ms Est. Latency
                    </p>

                    <div className="flex items-center gap-3 w-full mt-3">
                      <button
                        onClick={() => setIncomingCallSim(false)}
                        className="flex-1 py-2.5 rounded-2xl bg-red-500/15 hover:bg-red-500/25 border border-red-500/40 text-red-300 font-mono text-xs flex items-center justify-center gap-1.5"
                      >
                        <PhoneOff className="w-3.5 h-3.5" />
                        <span>Reject</span>
                      </button>
                      <button
                        onClick={() => {
                          setIncomingCallSim(false);
                          setActiveScreen('call');
                        }}
                        className="flex-1 py-2.5 rounded-2xl bg-emerald-500 hover:bg-emerald-400 text-black font-bold font-mono text-xs flex items-center justify-center gap-1.5 shadow-[0_0_20px_rgba(16,185,129,0.4)]"
                      >
                        <Phone className="w-3.5 h-3.5" />
                        <span>Answer Relay</span>
                      </button>
                    </div>
                  </div>
                )}

                {/* Bottom Selected Asset Toolbar (When editing an asset) */}
                {isAodEditMode && selectedAssetId && (
                  <div className="p-2 rounded-2xl bg-[#080D15] border border-emerald-500/40 flex items-center justify-between text-xs font-mono z-30">
                    <span className="text-emerald-300 text-[11px] truncate max-w-[120px]">
                      Selected: {selectedAssetId}
                    </span>

                    <div className="flex items-center gap-1.5">
                      {/* Scale down */}
                      <button
                        onClick={() => {
                          const cur = getAssetCoord(selectedAssetId);
                          updateAssetCoord(selectedAssetId, {
                            scale: Math.max(0.6, Number((cur.scale - 0.1).toFixed(1)))
                          });
                        }}
                        className="p-1 rounded bg-slate-900 border border-slate-700 text-slate-300 hover:text-white"
                        title="Scale Down"
                      >
                        <Minimize2 className="w-3 h-3" />
                      </button>

                      {/* Scale up */}
                      <button
                        onClick={() => {
                          const cur = getAssetCoord(selectedAssetId);
                          updateAssetCoord(selectedAssetId, {
                            scale: Math.min(1.8, Number((cur.scale + 0.1).toFixed(1)))
                          });
                        }}
                        className="p-1 rounded bg-slate-900 border border-slate-700 text-slate-300 hover:text-white"
                        title="Scale Up"
                      >
                        <Maximize2 className="w-3 h-3" />
                      </button>

                      {/* Delete asset if custom */}
                      {customAssets.some((a) => a.id === selectedAssetId) && (
                        <button
                          onClick={() => handleDeleteAsset(selectedAssetId)}
                          className="p-1 rounded bg-red-950/40 border border-red-500/40 text-red-300 hover:bg-red-900/50"
                          title="Delete Asset"
                        >
                          <Trash2 className="w-3 h-3" />
                        </button>
                      )}
                    </div>
                  </div>
                )}

                {/* Bottom Dock Mode Indicator */}
                {!isAodEditMode && (
                  <div className="text-center font-mono text-[10px] text-slate-600">
                    TRUE #000000 AMOLED • {isLandscape ? 'LANDSCAPE NIGHTSTAND DOCK' : 'PORTRAIT AOD'}
                  </div>
                )}
              </div>
            )}

            {/* =============================================================== */}
            {/* TAB 3: ACTIVE BRIDGED CALL (CallActivity.kt)                     */}
            {/* =============================================================== */}
            {activeScreen === 'call' && (
              <div
                className={`flex-1 bg-[#000000] px-5 py-3 overflow-y-auto ${
                  isLandscape ? 'pl-10 grid grid-cols-12 gap-5 items-center' : 'flex flex-col justify-between'
                }`}
              >
                <div className={`${isLandscape ? 'col-span-6' : ''} flex flex-col gap-3`}>
                  <div className="flex items-center justify-between border-b border-slate-900 pb-2.5">
                    <div>
                      <div className="flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full bg-emerald-400 animate-ping" />
                        <span className="text-[10px] font-mono uppercase tracking-widest text-emerald-400">
                          BRIDGED CALL • {Math.floor(callDuration / 60)}:{(callDuration % 60).toString().padStart(2, '0')}
                        </span>
                      </div>
                      <h2 className="text-lg font-bold text-white mt-0.5">Work Boss</h2>
                      <p className="text-xs font-mono text-slate-400">
                        Relayed via Phone 1 SIM • 192.168.1.104:45783
                      </p>
                    </div>
                    <div className="text-right font-mono">
                      <span className="text-[10px] px-2 py-0.5 rounded bg-cyan-500/15 text-cyan-300 border border-cyan-500/30 block">
                        OPUS 16kHz
                      </span>
                      <span className="text-[10px] text-slate-500 mt-1 block">SEQ #{seqNumber}</span>
                    </div>
                  </div>

                  {ownershipRevokedMsg && (
                    <div className="p-2.5 rounded-xl bg-amber-500/15 border border-amber-500/40 text-amber-300 font-mono text-[10px] flex items-center gap-2">
                      <Lock className="w-3.5 h-3.5 shrink-0" />
                      <span>{ownershipRevokedMsg}</span>
                    </div>
                  )}

                  {/* Waveform & VAD Hangover Visualizer */}
                  <div className="p-3.5 rounded-2xl bg-[#070A0F] border border-slate-800 space-y-2.5">
                    <div className="flex items-center justify-between text-[10px] font-mono">
                      <span className="text-slate-400 flex items-center gap-1">
                        <Waves className="w-3.5 h-3.5 text-cyan-400" /> PCM / OPUS STREAM
                      </span>
                      <span className={hangoverFrames > 0 ? 'text-emerald-400' : 'text-slate-500'}>
                        VAD: {hangoverFrames > 0 ? `ACTIVE (${hangoverFrames * 20}ms)` : 'SILENCE'}
                      </span>
                    </div>

                    <div className="h-12 flex items-center justify-between gap-1 px-1">
                      {Array.from({ length: 24 }).map((_, idx) => {
                        const activeWave = !isMuted && !isOnHold && hangoverFrames > 0;
                        const heightPct = activeWave
                          ? Math.max(18, Math.min(100, ((Math.sin(idx * 0.7 + seqNumber * 0.6) + 1) * 42) + (vadEnergy % 20)))
                          : 12;
                        return (
                          <div
                            key={idx}
                            style={{ height: `${heightPct}%` }}
                            className={`flex-1 rounded-full transition-all duration-150 ${
                              jitterSpikeActive
                                ? 'bg-amber-400'
                                : activeWave
                                ? 'bg-gradient-to-t from-emerald-500 to-cyan-400'
                                : 'bg-slate-800'
                            }`}
                          />
                        );
                      })}
                    </div>

                    <div className="grid grid-cols-3 gap-2 pt-2 border-t border-slate-900 font-mono text-[10px]">
                      <div>
                        <span className="text-slate-500 block">JITTER DELAY</span>
                        <span className={jitterMs > 60 ? 'text-amber-400 font-bold' : 'text-emerald-400'}>
                          {jitterMs} ms ({bufferFrames}f)
                        </span>
                      </div>
                      <div>
                        <span className="text-slate-500 block">PLC SYNTH</span>
                        <span className={plcActive ? 'text-amber-400 font-bold' : 'text-slate-300'}>
                          {plcActive ? '0.78x DECAY' : '0% LOSS'}
                        </span>
                      </div>
                      <div>
                        <span className="text-slate-500 block">PRUNED</span>
                        <span className="text-cyan-400">{prunedPackets} pkts</span>
                      </div>
                    </div>
                  </div>
                </div>

                {/* Route Selector & Controls */}
                <div className={`${isLandscape ? 'col-span-6' : ''} flex flex-col gap-3 mt-2 sm:mt-0`}>
                  <div className="p-3 rounded-2xl bg-[#070A0F] border border-slate-900">
                    <span className="text-[10px] font-mono uppercase tracking-wider text-slate-400 block mb-2">
                      AudioRouteManager.kt • Route Ownership
                    </span>
                    <div className="grid grid-cols-2 gap-1.5 font-mono text-[11px]">
                      {[
                        { id: 'wifi_relay', label: 'Wi-Fi P2P Bridge', icon: Wifi },
                        { id: 'speaker', label: 'Speakerphone', icon: Volume2 },
                        { id: 'earpiece', label: 'Earpiece', icon: Smartphone },
                        { id: 'bluetooth', label: 'Local BT Headset', icon: Bluetooth }
                      ].map((r) => {
                        const Icon = r.icon;
                        const selected = audioRoute === r.id;
                        return (
                          <button
                            key={r.id}
                            onClick={() => {
                              setAudioRoute(r.id);
                              if (r.id === 'bluetooth') {
                                setOwnershipRevokedMsg('CALL_REVOKED -> Local BT SCO locked');
                                setTimeout(() => setOwnershipRevokedMsg(null), 4000);
                              } else {
                                setOwnershipRevokedMsg(null);
                              }
                            }}
                            className={`flex items-center gap-1.5 px-2.5 py-2 rounded-xl border text-left transition-all ${
                              selected
                                ? 'bg-cyan-500/15 border-cyan-500/50 text-cyan-300'
                                : 'bg-black border-slate-900 text-slate-400 hover:text-slate-200'
                            }`}
                          >
                            <Icon className="w-3.5 h-3.5 shrink-0" />
                            <span className="truncate">{r.label}</span>
                          </button>
                        );
                      })}
                    </div>
                  </div>

                  <div className="grid grid-cols-3 gap-2.5 pt-1">
                    <button
                      onClick={() => setIsMuted((m) => !m)}
                      className={`py-3 rounded-2xl font-mono text-xs border flex flex-col items-center justify-center gap-1 transition-all ${
                        isMuted
                          ? 'bg-amber-500/20 border-amber-500/50 text-amber-300'
                          : 'bg-[#070A0F] border-slate-800 text-slate-300'
                      }`}
                    >
                      {isMuted ? <MicOff className="w-4 h-4" /> : <Mic className="w-4 h-4" />}
                      <span className="text-[10px]">{isMuted ? 'Muted' : 'Mute Mic'}</span>
                    </button>

                    <button
                      onClick={() => setIsOnHold((h) => !h)}
                      className={`py-3 rounded-2xl font-mono text-xs border flex flex-col items-center justify-center gap-1 transition-all ${
                        isOnHold
                          ? 'bg-cyan-500/20 border-cyan-500/50 text-cyan-300'
                          : 'bg-[#070A0F] border-slate-800 text-slate-300'
                      }`}
                    >
                      <RefreshCw className="w-4 h-4" />
                      <span className="text-[10px]">{isOnHold ? 'Resume' : 'Hold Call'}</span>
                    </button>

                    <button
                      onClick={() => setActiveScreen('dashboard')}
                      className="py-3 rounded-2xl bg-red-500 hover:bg-red-400 text-black font-bold font-mono text-xs flex flex-col items-center justify-center gap-1 shadow-[0_0_20px_rgba(239,68,68,0.35)]"
                    >
                      <PhoneOff className="w-4 h-4" />
                      <span className="text-[10px]">End Relay</span>
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* =============================================================== */}
            {/* TAB 4: COMPREHENSIVE SETTINGS & AOD CUSTOMIZATION MENU          */}
            {/* =============================================================== */}
            {activeScreen === 'settings' && (
              <div
                className={`flex-1 bg-[#000000] px-5 py-4 overflow-y-auto space-y-4 ${
                  isLandscape ? 'pl-10 grid grid-cols-12 gap-5 items-start space-y-0' : ''
                }`}
              >
                {/* Column 1: AOD Orientation Lock & Theme Presets */}
                <div className={`${isLandscape ? 'col-span-6' : ''} space-y-3.5`}>
                  {/* Setting 1: AOD Orientation Lock & Auto-Dock */}
                  <div className="p-3.5 rounded-2xl bg-[#080D15] border border-slate-800/90 space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-bold text-white flex items-center gap-1.5">
                        <Compass className="w-3.5 h-3.5 text-cyan-400" />
                        AOD Orientation Lock & Auto-Dock
                      </span>
                      <span className="text-[10px] font-mono text-cyan-400">
                        {isLandscape ? '820×400 Nightstand' : '380×780 Portrait'}
                      </span>
                    </div>

                    {/* 3-Way Segmented Control */}
                    <div className="grid grid-cols-3 gap-1 bg-black p-1 rounded-xl border border-slate-900 text-xs font-mono">
                      {[
                        { id: 'portrait_lock', label: 'Portrait Lock' },
                        { id: 'landscape_lock', label: 'Landscape Dock' },
                        { id: 'auto_sensor', label: 'Auto (Sensor)' }
                      ].map((item) => (
                        <button
                          key={item.id}
                          onClick={() => setOrientationMode(item.id)}
                          className={`py-1.5 rounded-lg text-center transition-all ${
                            orientationMode === item.id
                              ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/40 shadow-sm'
                              : 'text-slate-400 hover:text-white'
                          }`}
                        >
                          {item.label}
                        </button>
                      ))}
                    </div>

                    {/* Simulated Sensor Toggle for Auto mode */}
                    {orientationMode === 'auto_sensor' && (
                      <div className="flex items-center justify-between pt-1 text-[11px] font-mono text-slate-400">
                        <span>Simulated Gyro Orientation:</span>
                        <button
                          onClick={() => setSimulatedSensorLandscape((s) => !s)}
                          className="px-2 py-0.5 rounded bg-slate-900 text-emerald-300 border border-emerald-500/30"
                        >
                          {simulatedSensorLandscape ? 'Horizontal (Nightstand)' : 'Vertical'}
                        </button>
                      </div>
                    )}

                    <p className="text-[10px] font-mono text-slate-500">
                      Clamping limits: {isLandscape ? '±10px X / ±5px Y' : '±6px X / ±8px Y'}
                    </p>
                  </div>

                  {/* Setting 2: AOD Clock & Theme Styles (4 Visual Presets) */}
                  <div className="p-3.5 rounded-2xl bg-[#080D15] border border-slate-800/90 space-y-2">
                    <span className="text-xs font-bold text-white flex items-center gap-1.5">
                      <Clock className="w-3.5 h-3.5 text-emerald-400" />
                      AOD Clock Visual Presets
                    </span>

                    <div className="grid grid-cols-2 gap-2 text-left font-mono">
                      {[
                        {
                          id: 'cyber_mesh',
                          name: 'Cyber-Mesh Minimal',
                          desc: 'Monospace thin + live UDP ping ticker'
                        },
                        {
                          id: 'nothing_dot',
                          name: 'Nothing-OS Dot Matrix',
                          desc: 'Segmented dots with red signature accent'
                        },
                        {
                          id: 'analog_chrono',
                          name: 'Analog Chrono-Ring',
                          desc: 'Radial SVG watch dial + battery arcs'
                        },
                        {
                          id: 'split_flap',
                          name: 'Split-Flap Nightstand',
                          desc: 'High-contrast bold bedside split clock'
                        }
                      ].map((preset) => {
                        const active = aodClockTheme === preset.id;
                        return (
                          <button
                            key={preset.id}
                            onClick={() => setAodClockTheme(preset.id)}
                            className={`p-2.5 rounded-xl border text-left transition-all ${
                              active
                                ? 'bg-emerald-500/15 border-emerald-400/60 shadow-[0_0_12px_rgba(16,185,129,0.15)]'
                                : 'bg-black border-slate-900 hover:border-slate-800'
                            }`}
                          >
                            <span
                              className={`text-xs font-bold block ${
                                active ? 'text-emerald-300' : 'text-slate-200'
                              }`}
                            >
                              {preset.name}
                            </span>
                            <span className="text-[10px] text-slate-500 leading-tight block mt-0.5">
                              {preset.desc}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                </div>

                {/* Column 2: AMOLED Hardware Gates & Canvas Customizer */}
                <div className={`${isLandscape ? 'col-span-6' : ''} space-y-3.5`}>
                  {/* Setting 3: AMOLED Hardware & Software Dimming Gates */}
                  <div className="p-3.5 rounded-2xl bg-[#080D15] border border-slate-800/90 space-y-3">
                    <span className="text-xs font-bold text-white flex items-center gap-1.5">
                      <Sun className="w-3.5 h-3.5 text-amber-400" />
                      AMOLED Brightness & Orbit Speeds
                    </span>

                    {/* Dimming Slider */}
                    <div>
                      <div className="flex justify-between text-xs font-mono text-slate-400">
                        <span>Software Dimming:</span>
                        <span className="text-emerald-400 font-bold">
                          {Math.round(screenBrightness * 100)}% ({screenBrightness.toFixed(2)}f)
                        </span>
                      </div>
                      <input
                        type="range"
                        min="0.01"
                        max="0.20"
                        step="0.01"
                        value={screenBrightness}
                        onChange={(e) => setScreenBrightness(parseFloat(e.target.value))}
                        className="w-full mt-1 accent-emerald-400 cursor-pointer"
                      />
                      <span className="text-[9px] font-mono text-slate-500 block">
                        0.01f (True OLED Pitch Black) to 0.20f (IPS / LCD Fallback)
                      </span>
                    </div>

                    {/* Lissajous Orbit Speed Slider */}
                    <div>
                      <div className="flex justify-between text-xs font-mono text-slate-400">
                        <span>Lissajous Orbit Speed:</span>
                        <span className="text-cyan-400 font-bold">
                          {orbitSpeedMultiplier}x Speed ({orbitSpeedMultiplier === 1 ? '60s Natural' : 'Fast-Forward'})
                        </span>
                      </div>
                      <input
                        type="range"
                        min="1"
                        max="20"
                        step="1"
                        value={orbitSpeedMultiplier}
                        onChange={(e) => setOrbitSpeedMultiplier(parseInt(e.target.value))}
                        className="w-full mt-1 accent-cyan-400 cursor-pointer"
                      />
                    </div>
                  </div>

                  {/* Setting 4: Freeform Drag Canvas Actions & Persistence */}
                  <div className="p-3.5 rounded-2xl bg-[#080D15] border border-slate-800/90 space-y-2.5">
                    <div className="flex items-center justify-between">
                      <span className="text-xs font-bold text-white flex items-center gap-1.5">
                        <Layout className="w-3.5 h-3.5 text-emerald-400" />
                        AOD Asset Canvas Studio
                      </span>
                      <span className="text-[10px] font-mono text-slate-400">
                        {customAssets.length + 4} Elements Active
                      </span>
                    </div>

                    <div className="grid grid-cols-2 gap-2 text-xs font-mono">
                      <button
                        onClick={() => {
                          setActiveScreen('aod');
                          setIsAodEditMode(true);
                        }}
                        className="p-2.5 rounded-xl bg-emerald-500/20 hover:bg-emerald-500/30 border border-emerald-400/50 text-emerald-300 font-bold flex items-center justify-center gap-1.5 transition-all"
                      >
                        <Move className="w-3.5 h-3.5" />
                        <span>Launch Canvas Editor</span>
                      </button>

                      <button
                        onClick={handleResetLayout}
                        className="p-2.5 rounded-xl bg-slate-900 hover:bg-slate-800 border border-slate-800 text-slate-300 flex items-center justify-center gap-1.5 transition-all"
                      >
                        <RotateCcw className="w-3.5 h-3.5" />
                        <span>Reset Coordinates</span>
                      </button>
                    </div>

                    <p className="text-[10px] font-mono text-slate-500 leading-relaxed">
                      Coordinates are stored separately for Portrait and Landscape modes. Rotating the device preserves individual coordinate profiles without distortion.
                    </p>
                  </div>
                </div>
              </div>
            )}

            {/* ANDROID GESTURE NAVIGATION PILL */}
            <div className="py-2 bg-[#000000] flex justify-center z-30">
              <div className="w-28 h-1 rounded-full bg-slate-800" />
            </div>
          </div>
        </div>

        {/* RIGHT COLUMN: LIVE PROTOCOL & ROLE TELEMETRY INSPECTOR */}
        <aside className="w-full xl:w-[410px] flex flex-col gap-4 self-stretch">
          <div className="p-5 rounded-3xl bg-[#090D14] border border-slate-800/90 flex-1 flex flex-col justify-between space-y-4">
            <div>
              <div className="flex items-center justify-between border-b border-slate-800 pb-3">
                <div className="flex items-center gap-2">
                  <Terminal className="w-4 h-4 text-emerald-400" />
                  <h3 className="text-sm font-bold uppercase tracking-wider text-white font-mono">
                    Telecom & Canvas Inspector
                  </h3>
                </div>
                <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-emerald-500/10 text-emerald-400 border border-emerald-500/30">
                  ONLINE
                </span>
              </div>

              {/* Status Verification Matrix */}
              <div className="mt-4 space-y-2.5">
                {[
                  {
                    code: 'FEATURE 1 • ROLE_DIALER',
                    title: isDefaultCallerActive ? 'Default Caller Verified' : 'Standard Companion Mode',
                    desc: isDefaultCallerActive
                      ? 'Full GSM telephony hook granted; audio focus transient lock enabled.'
                      : 'Tap "RoleManager" to trigger native Android system dialog.',
                    status: isDefaultCallerActive ? 'GRANTED' : 'STANDBY',
                    color: isDefaultCallerActive ? 'text-emerald-400' : 'text-amber-400'
                  },
                  {
                    code: 'FEATURE 2 • AOD STYLING',
                    title: `${aodClockTheme.toUpperCase()} • ${isLandscape ? 'LANDSCAPE' : 'PORTRAIT'}`,
                    desc: `Clamping limits: ±${maxShiftX}px X, ±${maxShiftY}px Y. Orbit speed: ${orbitSpeedMultiplier}x.`,
                    status: 'LOCKED',
                    color: 'text-cyan-400'
                  },
                  {
                    code: 'FEATURE 3 • DRAGGABLE CANVAS',
                    title: `Snap Grid: ${isSnapGridEnabled ? '16px ON' : 'OFF'} • ${customAssets.length} Custom`,
                    desc: `Safe zone collision guards active. Dragging enabled in Canvas Studio.`,
                    status: isAodEditMode ? 'EDITING' : 'IDLE',
                    color: isAodEditMode ? 'text-emerald-400' : 'text-slate-400'
                  },
                  {
                    code: 'BUG 1.3 & 1.4 • DPD & JITTER',
                    title: `DPD 500ms Beat • Jitter ${jitterMs}ms`,
                    desc: `RFC 1982 wrap arithmetic active; audio buffer pruned on Wi-Fi spikes.`,
                    status: 'STABLE',
                    color: 'text-emerald-400'
                  }
                ].map((item, idx) => (
                  <div
                    key={idx}
                    className="p-3 rounded-2xl bg-black/80 border border-slate-800/80 hover:border-slate-700 transition-all"
                  >
                    <div className="flex items-center justify-between text-[10px] font-mono">
                      <span className="text-slate-400">{item.code}</span>
                      <span className={`font-bold ${item.color}`}>{item.status}</span>
                    </div>
                    <div className="text-xs font-semibold text-white mt-0.5">{item.title}</div>
                    <p className="text-[11px] text-slate-400 mt-0.5 leading-relaxed">{item.desc}</p>
                  </div>
                ))}
              </div>
            </div>

            {/* Quick Action Buttons */}
            <div className="pt-3 border-t border-slate-800/80 space-y-2">
              <button
                onClick={() => setShowRoleManagerModal(true)}
                className="w-full py-2.5 px-4 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-black font-bold font-mono text-xs flex items-center justify-center gap-2 shadow-[0_0_20px_rgba(16,185,129,0.3)] transition-all"
              >
                <ShieldCheck className="w-4 h-4" />
                <span>Test RoleManager System Dialog</span>
              </button>

              <button
                onClick={() => setShowSpecsModal(true)}
                className="w-full py-2.5 px-4 rounded-xl bg-slate-900 hover:bg-slate-800 text-cyan-400 font-mono text-xs flex items-center justify-center gap-2 border border-cyan-500/30 transition-all"
              >
                <Code className="w-4 h-4" />
                <span>View Kotlin Bridge Code & Specs</span>
              </button>
            </div>
          </div>
        </aside>
      </main>

      {/* =================================================================== */}
      {/* FEATURE 1: NATIVE ANDROID ROLEMANAGER SYSTEM SHEET DIALOG           */}
      {/* =================================================================== */}
      {showRoleManagerModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#10141D] border border-slate-800 rounded-3xl max-w-md w-full overflow-hidden shadow-2xl animate-in fade-in zoom-in-95 duration-200">
            {/* Native Android Header */}
            <div className="p-5 border-b border-slate-800/80">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-2xl bg-emerald-500/10 border border-emerald-500/40 flex items-center justify-center">
                  <Phone className="w-5 h-5 text-emerald-400" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-white tracking-tight">
                    Set CallShare P2P as your default phone app?
                  </h3>
                  <p className="text-[11px] text-slate-400 font-sans mt-0.5">
                    Select an app to handle incoming and outgoing phone calls
                  </p>
                </div>
              </div>
            </div>

            {/* App List Comparison */}
            <div className="p-5 space-y-3 font-sans">
              {/* Option A: System Default Phone App */}
              <div
                onClick={() => setSelectedRoleApp('system')}
                className={`p-3 rounded-2xl border cursor-pointer flex items-center justify-between transition-all ${
                  selectedRoleApp === 'system'
                    ? 'bg-slate-900 border-slate-700'
                    : 'bg-black/40 border-slate-900 hover:bg-slate-950'
                }`}
              >
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-full bg-blue-600 flex items-center justify-center text-white">
                    <Phone className="w-4 h-4" />
                  </div>
                  <div>
                    <span className="text-xs font-semibold text-white block">Phone</span>
                    <span className="text-[10px] text-slate-400">Current Default System Dialer</span>
                  </div>
                </div>
                <div
                  className={`w-4 h-4 rounded-full border flex items-center justify-center ${
                    selectedRoleApp === 'system' ? 'border-cyan-400' : 'border-slate-700'
                  }`}
                >
                  {selectedRoleApp === 'system' && <div className="w-2 h-2 rounded-full bg-cyan-400" />}
                </div>
              </div>

              {/* Option B: CallShare P2P */}
              <div
                onClick={() => setSelectedRoleApp('callshare')}
                className={`p-3 rounded-2xl border cursor-pointer flex items-center justify-between transition-all ${
                  selectedRoleApp === 'callshare'
                    ? 'bg-emerald-950/20 border-emerald-500/60 shadow-[0_0_15px_rgba(16,185,129,0.15)]'
                    : 'bg-black/40 border-slate-900 hover:bg-slate-950'
                }`}
              >
                <div className="flex items-center gap-3">
                  <div className="w-8 h-8 rounded-full bg-emerald-500 flex items-center justify-center text-black shadow-md">
                    <Radio className="w-4 h-4" />
                  </div>
                  <div>
                    <span className="text-xs font-semibold text-white block">CallShare P2P</span>
                    <span className="text-[10px] text-emerald-400 font-mono">
                      ROLE_DIALER • Offline Mesh Relay
                    </span>
                  </div>
                </div>
                <div
                  className={`w-4 h-4 rounded-full border flex items-center justify-center ${
                    selectedRoleApp === 'callshare' ? 'border-emerald-400' : 'border-slate-700'
                  }`}
                >
                  {selectedRoleApp === 'callshare' && <div className="w-2 h-2 rounded-full bg-emerald-400" />}
                </div>
              </div>

              {/* Permissions & Feature Checkboxes */}
              <div className="pt-2 border-t border-slate-800/80 space-y-2 font-mono text-[11px]">
                <span className="text-[10px] uppercase tracking-wider text-slate-400 block mb-1">
                  Telephony & Audio Hooks:
                </span>

                <label className="flex items-center gap-2 text-slate-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={relayGsmOverLan}
                    onChange={(e) => setRelayGsmOverLan(e.target.checked)}
                    className="accent-emerald-400 rounded"
                  />
                  <span>Relay incoming GSM calls over local LAN</span>
                </label>

                <label className="flex items-center gap-2 text-slate-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={autoWakeAodOnIncoming}
                    onChange={(e) => setAutoWakeAodOnIncoming(e.target.checked)}
                    className="accent-emerald-400 rounded"
                  />
                  <span>Auto-wake AOD on incoming TCP CallIncoming packet</span>
                </label>

                <label className="flex items-center gap-2 text-slate-300 cursor-pointer">
                  <input
                    type="checkbox"
                    checked={exclusiveAudioFocus}
                    onChange={(e) => setExclusiveAudioFocus(e.target.checked)}
                    className="accent-emerald-400 rounded"
                  />
                  <span>Acquire exclusive transient audio focus</span>
                </label>
              </div>
            </div>

            {/* Action Buttons */}
            <div className="p-4 bg-[#0A0E17] border-t border-slate-800/80 flex items-center justify-end gap-2 text-xs font-mono">
              <button
                onClick={() => setShowRoleManagerModal(false)}
                className="px-4 py-2 rounded-xl text-slate-400 hover:text-white transition-all"
              >
                Cancel
              </button>
              <button
                onClick={() => {
                  if (selectedRoleApp === 'callshare') {
                    setIsDefaultCallerActive(true);
                  } else {
                    setIsDefaultCallerActive(false);
                  }
                  setShowRoleManagerModal(false);
                }}
                className="px-5 py-2 rounded-xl bg-emerald-500 hover:bg-emerald-400 text-black font-bold transition-all shadow-[0_0_15px_rgba(16,185,129,0.3)]"
              >
                Set as Default
              </button>
            </div>
          </div>
        </div>
      )}

      {/* =================================================================== */}
      {/* FEATURE 3: ADD CUSTOM ASSET DRAWER / MODAL                          */}
      {/* =================================================================== */}
      {showAssetDrawer && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#0D121B] border border-slate-800 rounded-3xl max-w-md w-full overflow-hidden shadow-2xl">
            <div className="p-5 border-b border-slate-800 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Plus className="w-5 h-5 text-emerald-400" />
                <h3 className="text-sm font-bold text-white font-mono">
                  Inject Custom AOD Asset
                </h3>
              </div>
              <button
                onClick={() => setShowAssetDrawer(false)}
                className="text-slate-400 hover:text-white text-xs font-mono"
              >
                Close
              </button>
            </div>

            <div className="p-5 space-y-4 font-mono text-xs">
              {/* Option A: Modular Telemetry Pills */}
              <div>
                <span className="text-[11px] uppercase tracking-wider text-slate-400 block mb-2">
                  1. Modular Telemetry Pills
                </span>
                <div className="grid grid-cols-2 gap-2">
                  <button
                    onClick={() => handleAddCustomPill('telemetry_battery', 'Peer Battery Bar')}
                    className="p-2.5 rounded-xl bg-black border border-slate-800 hover:border-emerald-500/50 text-left text-slate-300 hover:text-white flex items-center gap-2"
                  >
                    <BatteryCharging className="w-4 h-4 text-emerald-400" />
                    <span>Peer Battery</span>
                  </button>

                  <button
                    onClick={() => handleAddCustomPill('telemetry_jitter', 'Live Jitter Waveform')}
                    className="p-2.5 rounded-xl bg-black border border-slate-800 hover:border-cyan-500/50 text-left text-slate-300 hover:text-white flex items-center gap-2"
                  >
                    <Activity className="w-4 h-4 text-cyan-400" />
                    <span>Jitter Wave</span>
                  </button>

                  <button
                    onClick={() => handleAddCustomPill('quick_mute', 'Quick-Mute Toggle')}
                    className="p-2.5 rounded-xl bg-black border border-slate-800 hover:border-amber-500/50 text-left text-slate-300 hover:text-white flex items-center gap-2"
                  >
                    <MicOff className="w-4 h-4 text-amber-400" />
                    <span>Quick Mute</span>
                  </button>

                  <button
                    onClick={() => {
                      const text = prompt('Enter custom label:', 'Bedside Dock') || 'Custom Hub';
                      handleAddCustomPill('custom_text', text);
                    }}
                    className="p-2.5 rounded-xl bg-black border border-slate-800 hover:border-emerald-500/50 text-left text-slate-300 hover:text-white flex items-center gap-2"
                  >
                    <Tag className="w-4 h-4 text-emerald-400" />
                    <span>Custom Text</span>
                  </button>
                </div>
              </div>

              {/* Option B: Custom PNG/SVG Sticker Upload */}
              <div className="pt-2 border-t border-slate-800">
                <span className="text-[11px] uppercase tracking-wider text-slate-400 block mb-2">
                  2. Upload Transparent PNG / SVG Sticker
                </span>
                <label className="p-4 rounded-2xl bg-black border border-dashed border-slate-700 hover:border-cyan-400 cursor-pointer flex flex-col items-center justify-center gap-2 transition-all">
                  <Upload className="w-5 h-5 text-cyan-400" />
                  <span className="text-slate-300 font-semibold text-xs">
                    Choose Transparent Logo / Image
                  </span>
                  <span className="text-[10px] text-slate-500">
                    PNG, SVG, WEBP (Base64 URL persistence)
                  </span>
                  <input
                    type="file"
                    accept="image/png, image/svg+xml, image/webp"
                    onChange={handleCustomImageUpload}
                    className="hidden"
                  />
                </label>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* =================================================================== */}
      {/* CODE & SPECS MODAL                                                  */}
      {/* =================================================================== */}
      {showSpecsModal && (
        <div className="fixed inset-0 z-50 bg-black/85 backdrop-blur-md flex items-center justify-center p-4">
          <div className="bg-[#090D14] border border-slate-800 rounded-3xl max-w-3xl w-full max-h-[85vh] flex flex-col overflow-hidden shadow-2xl">
            <div className="px-6 py-4 border-b border-slate-800 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Code className="w-5 h-5 text-emerald-400" />
                <h2 className="text-base font-bold text-white font-mono">
                  Upgraded Android Systems & Canvas Specs
                </h2>
              </div>
              <button
                onClick={() => setShowSpecsModal(false)}
                className="px-3 py-1 rounded-lg bg-slate-900 text-slate-400 hover:text-white text-xs font-mono"
              >
                ESC / Close
              </button>
            </div>

            <div className="p-6 overflow-y-auto space-y-6 text-xs font-mono">
              {/* Block 1: Master Prompt */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-emerald-400 font-bold uppercase">
                    1. Architecture Prompt & RoleManager Contract
                  </span>
                  <button
                    onClick={() => copyToClipboard('prompt', DESIGN_SPECS.promptUpgrade)}
                    className="flex items-center gap-1 px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200"
                  >
                    {copiedTab === 'prompt' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    <span>{copiedTab === 'prompt' ? 'Copied!' : 'Copy Prompt'}</span>
                  </button>
                </div>
                <pre className="p-4 rounded-2xl bg-black border border-slate-800 text-slate-300 whitespace-pre-wrap leading-relaxed">
                  {DESIGN_SPECS.promptUpgrade}
                </pre>
              </div>

              {/* Block 2: Colors.xml */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-cyan-400 font-bold uppercase">
                    2. res/values/colors.xml (True AMOLED Tokens)
                  </span>
                  <button
                    onClick={() => copyToClipboard('colors', DESIGN_SPECS.colors)}
                    className="flex items-center gap-1 px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200"
                  >
                    {copiedTab === 'colors' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    <span>{copiedTab === 'colors' ? 'Copied!' : 'Copy XML'}</span>
                  </button>
                </div>
                <pre className="p-4 rounded-2xl bg-black border border-slate-800 text-slate-300 overflow-x-auto">
                  {DESIGN_SPECS.colors}
                </pre>
              </div>

              {/* Block 3: Compose Lissajous */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-emerald-400 font-bold uppercase">
                    3. Jetpack Compose Lissajous Burn-In Container
                  </span>
                  <button
                    onClick={() => copyToClipboard('compose', DESIGN_SPECS.composeTheme)}
                    className="flex items-center gap-1 px-2.5 py-1 rounded bg-slate-800 hover:bg-slate-700 text-slate-200"
                  >
                    {copiedTab === 'compose' ? <Check className="w-3.5 h-3.5 text-emerald-400" /> : <Copy className="w-3.5 h-3.5" />}
                    <span>{copiedTab === 'compose' ? 'Copied!' : 'Copy Kotlin'}</span>
                  </button>
                </div>
                <pre className="p-4 rounded-2xl bg-black border border-slate-800 text-slate-300 overflow-x-auto">
                  {DESIGN_SPECS.composeTheme}
                </pre>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
