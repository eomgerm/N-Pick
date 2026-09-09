'use client';

import { Canvas, type ThreeEvent, useFrame } from '@react-three/fiber';
import { Component, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';
import {
  AdditiveBlending,
  CanvasTexture,
  DoubleSide,
  EdgesGeometry,
  LinearFilter,
  type Group,
  MathUtils,
  SRGBColorSpace,
} from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';

import type { WireframeTheme } from '@/features/wireframes/wireframe-themes';

interface LandingCubeProps {
  onReady?: () => void;
  theme: WireframeTheme;
}

interface GlassCubeProps extends LandingCubeProps {
  reducedMotion: boolean;
}

interface CubePalette {
  accent: string;
  highlight: string;
  secondary: string;
}

interface CubeErrorBoundaryProps {
  children: ReactNode;
}

interface CubeErrorBoundaryState {
  failed: boolean;
}

const cubePalettes: Record<WireframeTheme, CubePalette> = {
  shinhan: { accent: '#2c7dff', highlight: '#f3ffff', secondary: '#4ce6c5' },
};

class CubeErrorBoundary extends Component<CubeErrorBoundaryProps, CubeErrorBoundaryState> {
  state: CubeErrorBoundaryState = { failed: false };

  static getDerivedStateFromError(): CubeErrorBoundaryState {
    return { failed: true };
  }

  render() {
    return this.state.failed ? null : this.props.children;
  }
}

function supportsWebGl() {
  try {
    const probeCanvas = document.createElement('canvas');
    const context = probeCanvas.getContext('webgl2');

    if (!context) {
      return false;
    }

    context.getExtension('WEBGL_lose_context')?.loseContext();
    return true;
  } catch {
    return false;
  }
}

function useReducedMotion() {
  const [reducedMotion, setReducedMotion] = useState(
    () => window.matchMedia('(prefers-reduced-motion: reduce)').matches,
  );

  useEffect(() => {
    const mediaQuery = window.matchMedia('(prefers-reduced-motion: reduce)');
    const updatePreference = () => setReducedMotion(mediaQuery.matches);

    updatePreference();
    mediaQuery.addEventListener('change', updatePreference);

    return () => mediaQuery.removeEventListener('change', updatePreference);
  }, []);

  return reducedMotion;
}

function createTypographyTexture(palette: CubePalette) {
  const canvas = document.createElement('canvas');
  const context = canvas.getContext('2d');

  canvas.width = 1600;
  canvas.height = 800;

  if (!context) {
    return new CanvasTexture(canvas);
  }

  for (let index = 0; index < 70; index += 1) {
    const x = (index * 239 + 71) % canvas.width;
    const y = (index * 131 + 43) % canvas.height;
    const radius = index % 9 === 0 ? 2.2 : 1.1;
    context.beginPath();
    context.arc(x, y, radius, 0, Math.PI * 2);
    context.fillStyle = `rgba(255, 255, 255, ${index % 5 === 0 ? 0.68 : 0.32})`;
    context.fill();
  }

  const textGradient = context.createLinearGradient(260, 260, 1340, 590);
  textGradient.addColorStop(0, palette.accent);
  textGradient.addColorStop(0.48, '#94b9ef');
  textGradient.addColorStop(0.72, palette.secondary);
  textGradient.addColorStop(1, palette.accent);

  context.save();
  context.textAlign = 'center';
  context.textBaseline = 'middle';
  context.font = '900 270px Arial Black, Arial, sans-serif';
  context.letterSpacing = '-18px';
  context.shadowBlur = 38;
  context.shadowColor = palette.secondary;
  context.fillStyle = textGradient;
  context.fillText('N-PICK', 800, 410);
  context.restore();

  const texture = new CanvasTexture(canvas);
  texture.colorSpace = SRGBColorSpace;
  texture.magFilter = LinearFilter;
  texture.minFilter = LinearFilter;
  texture.needsUpdate = true;

  return texture;
}

function BackdropTypography({ theme }: Pick<LandingCubeProps, 'theme'>) {
  const palette = cubePalettes[theme];
  const texture = useMemo(() => createTypographyTexture(palette), [palette]);

  useEffect(() => () => texture.dispose(), [texture]);

  return (
    <mesh position={[0, 0, -2.35]}>
      <planeGeometry args={[5.8, 2.9]} />
      {/* Alpha cutout keeps the lettering in Three.js's opaque transmission capture. */}
      <meshBasicMaterial alphaTest={0.5} alphaToCoverage map={texture} toneMapped={false} />
    </mesh>
  );
}

function GlassCube({ onReady, reducedMotion, theme }: GlassCubeProps) {
  const groupRef = useRef<Group>(null);
  const readyAnimationFrameRef = useRef<number | null>(null);
  const readyRef = useRef(false);
  const hoveredRef = useRef(false);
  const expandedRef = useRef(false);
  const palette = cubePalettes[theme];
  const cubeGeometry = useMemo(() => new RoundedBoxGeometry(2.35, 2.35, 2.35, 6, 0.14), []);
  const edgeGeometry = useMemo(() => new EdgesGeometry(cubeGeometry, 8), [cubeGeometry]);

  useEffect(
    () => () => {
      if (readyAnimationFrameRef.current !== null) {
        window.cancelAnimationFrame(readyAnimationFrameRef.current);
      }

      edgeGeometry.dispose();
      cubeGeometry.dispose();
    },
    [cubeGeometry, edgeGeometry],
  );

  useFrame(({ clock, pointer }, delta) => {
    const group = groupRef.current;

    if (!group) {
      return;
    }

    if (!readyRef.current) {
      readyRef.current = true;
      readyAnimationFrameRef.current = window.requestAnimationFrame(() => onReady?.());
    }

    if (reducedMotion) {
      return;
    }

    const settleProgress = MathUtils.smoothstep(clock.elapsedTime, 1.85, 3.05);
    const baseScale = MathUtils.lerp(1.02, 0.84, settleProgress);
    const interactionScale = expandedRef.current ? 1.08 : hoveredRef.current ? 1.035 : 1;
    const rotationSpeed = hoveredRef.current ? 0.72 : 0.48;

    group.scale.setScalar(MathUtils.damp(group.scale.x, baseScale * interactionScale, 5.4, delta));
    group.position.y = MathUtils.damp(group.position.y, settleProgress * 0.08, 4.2, delta);
    group.rotation.x += delta * rotationSpeed * 0.58;
    group.rotation.y += delta * rotationSpeed;
    group.rotation.z = MathUtils.damp(
      group.rotation.z,
      -0.08 - pointer.x * 0.16 + pointer.y * 0.06,
      2.8,
      delta,
    );
  });

  function handlePointerOver(event: ThreeEvent<PointerEvent>) {
    event.stopPropagation();

    if (reducedMotion) {
      return;
    }

    hoveredRef.current = true;

    if (event.nativeEvent.target instanceof HTMLCanvasElement) {
      event.nativeEvent.target.style.cursor = 'pointer';
    }
  }

  function handlePointerOut(event: ThreeEvent<PointerEvent>) {
    hoveredRef.current = false;

    if (event.nativeEvent.target instanceof HTMLCanvasElement) {
      event.nativeEvent.target.style.cursor = 'default';
    }
  }

  function handleClick(event: ThreeEvent<MouseEvent>) {
    event.stopPropagation();

    if (!reducedMotion) {
      expandedRef.current = !expandedRef.current;
    }
  }

  return (
    <group
      position={[0, reducedMotion ? 0.08 : 0, 0]}
      ref={groupRef}
      rotation={[0.42, -0.58, 0.16]}
      scale={reducedMotion ? 0.84 : 1.02}
    >
      <mesh
        geometry={cubeGeometry}
        onClick={handleClick}
        onPointerOut={handlePointerOut}
        onPointerOver={handlePointerOver}
      >
        {/* Use transmission without alpha blending so unrefracted text cannot bleed through. */}
        <meshPhysicalMaterial
          attenuationColor="#ffffff"
          attenuationDistance={10}
          clearcoat={1}
          clearcoatRoughness={0.035}
          color="#ffffff"
          depthWrite={false}
          dispersion={0.45}
          ior={1.5}
          iridescence={0.08}
          iridescenceIOR={1.28}
          iridescenceThicknessRange={[120, 720]}
          metalness={0}
          opacity={1}
          premultipliedAlpha
          roughness={0.025}
          specularColor={palette.highlight}
          specularIntensity={1}
          thickness={2.35}
          transmission={1}
        />
      </mesh>

      <mesh geometry={cubeGeometry} scale={0.83}>
        <meshBasicMaterial
          color={palette.secondary}
          depthWrite={false}
          opacity={0.035}
          side={DoubleSide}
          transparent
        />
      </mesh>

      <mesh geometry={cubeGeometry} renderOrder={1} scale={1.018}>
        <meshBasicMaterial
          blending={AdditiveBlending}
          color={palette.highlight}
          depthWrite={false}
          opacity={0.045}
          side={DoubleSide}
          transparent
        />
      </mesh>

      <lineSegments geometry={edgeGeometry} renderOrder={3} scale={1.006}>
        <lineBasicMaterial
          blending={AdditiveBlending}
          color={palette.highlight}
          depthWrite={false}
          opacity={0.94}
          transparent
        />
      </lineSegments>
      <lineSegments
        geometry={edgeGeometry}
        position={[-0.025, 0.012, 0]}
        renderOrder={2}
        scale={1.014}
      >
        <lineBasicMaterial
          blending={AdditiveBlending}
          color={palette.secondary}
          depthWrite={false}
          opacity={0.64}
          transparent
        />
      </lineSegments>
      <lineSegments
        geometry={edgeGeometry}
        position={[0.025, -0.012, 0]}
        renderOrder={2}
        scale={1.01}
      >
        <lineBasicMaterial
          blending={AdditiveBlending}
          color={palette.accent}
          depthWrite={false}
          opacity={0.52}
          transparent
        />
      </lineSegments>
    </group>
  );
}

export function LandingCube({ onReady, theme }: LandingCubeProps) {
  const reducedMotion = useReducedMotion();
  const [webGlSupported] = useState(supportsWebGl);

  if (!webGlSupported) {
    return null;
  }

  return (
    <CubeErrorBoundary>
      <Canvas
        camera={{ fov: 42, position: [0, 0, 7.2] }}
        dpr={[1, 1.5]}
        fallback={null}
        frameloop={reducedMotion ? 'demand' : 'always'}
        gl={{ alpha: true, antialias: true, powerPreference: 'high-performance' }}
      >
        <ambientLight intensity={0.48} />
        <directionalLight
          color={cubePalettes[theme].highlight}
          intensity={4.8}
          position={[4, 5, 6]}
        />
        <pointLight color={cubePalettes[theme].accent} intensity={34} position={[-3.4, -2, 4]} />
        <pointLight color={cubePalettes[theme].secondary} intensity={28} position={[3.6, 2.2, 2]} />
        <BackdropTypography theme={theme} />
        <GlassCube onReady={onReady} reducedMotion={reducedMotion} theme={theme} />
      </Canvas>
    </CubeErrorBoundary>
  );
}
