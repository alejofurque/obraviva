import {
  AfterViewInit,
  Component,
  ElementRef,
  OnDestroy,
  effect,
  input,
  untracked,
  viewChild,
} from '@angular/core';
import * as THREE from 'three';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { SparkRenderer, SplatMesh } from '@sparkjsdev/spark';
import { CaptureTransformDto } from '../../core/api/api.models';

/** Punto de vista inicial: a la altura de una persona, mirando hacia el centro de la escena. */
const INITIAL_CAMERA_POSITION = { x: 0, y: 1.5, z: 3 };
const INITIAL_TARGET = { x: 0, y: 1, z: 0 };

/** Velocidad de desplazamiento con teclado, en unidades por segundo. */
const MOVE_SPEED = 2.0;

export interface ViewerCapture {
  id: string;
  url: string;
  transform: CaptureTransformDto;
}

interface FadeState {
  fromIndex: number;
  toIndex: number;
  elapsed: number;
}

@Component({
  selector: 'app-splat-viewer',
  template: `
    <div class="viewer" #container>
      @if (captures().length === 0) {
        <div class="overlay">Sin capturas disponibles.</div>
      }
    </div>
  `,
  styles: `
    .viewer {
      position: relative;
      width: 100%;
      height: 100%;
      min-height: 400px;
      background: #1a1a1a;
      overflow: hidden;
    }

    .viewer canvas {
      display: block;
      touch-action: none;
    }

    .overlay {
      position: absolute;
      inset: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      color: #fff;
      pointer-events: none;
    }
  `,
})
export class SplatViewerComponent implements AfterViewInit, OnDestroy {

  readonly captures = input.required<ViewerCapture[]>();
  readonly activeIndex = input<number>(0);
  readonly crossfade = input<boolean>(true);
  readonly fadeDurationSeconds = input<number>(1.2);

  private readonly container = viewChild.required<ElementRef<HTMLDivElement>>('container');

  private renderer?: THREE.WebGLRenderer;
  private scene?: THREE.Scene;
  private camera?: THREE.PerspectiveCamera;
  private controls?: OrbitControls;
  private resizeObserver?: ResizeObserver;

  private meshes: SplatMesh[] = [];
  private loadedKey = '';
  private currentIndex = -1;
  private fade: FadeState | null = null;

  private readonly clock = new THREE.Clock();
  private readonly pressedKeys = new Set<string>();

  private readonly onKeyDown = (event: KeyboardEvent) => this.pressedKeys.add(event.code);
  private readonly onKeyUp = (event: KeyboardEvent) => this.pressedKeys.delete(event.code);
  private readonly onBlur = () => this.pressedKeys.clear();

  constructor() {
    effect(() => {
      const captures = this.captures();
      // Solo recrea los splats si cambió el conjunto; si solo cambió una
      // transformación, se reaplica sobre el mesh ya cargado (alineación en vivo).
      this.syncCaptures(captures);
      this.refreshTransforms(captures);
    });

    effect(() => {
      const index = this.activeIndex();
      this.showCapture(index, untracked(() => this.crossfade()));
    });
  }

  ngAfterViewInit(): void {
    this.initScene();
    const captures = untracked(() => this.captures());
    this.syncCaptures(captures);
    this.refreshTransforms(captures);
  }

  /** Vuelve al punto de vista inicial, para quien se perdió navegando la escena. */
  recenter(): void {
    if (!this.camera || !this.controls) {
      return;
    }
    this.camera.position.set(INITIAL_CAMERA_POSITION.x, INITIAL_CAMERA_POSITION.y, INITIAL_CAMERA_POSITION.z);
    this.controls.target.set(INITIAL_TARGET.x, INITIAL_TARGET.y, INITIAL_TARGET.z);
    this.controls.update();
  }

  ngOnDestroy(): void {
    this.dispose();
  }

  private initScene(): void {
    const host = this.container().nativeElement;
    const width = host.clientWidth || 1;
    const height = host.clientHeight || 1;

    this.scene = new THREE.Scene();

    this.camera = new THREE.PerspectiveCamera(60, width / height, 0.01, 1000);
    this.camera.position.set(INITIAL_CAMERA_POSITION.x, INITIAL_CAMERA_POSITION.y, INITIAL_CAMERA_POSITION.z);

    this.renderer = new THREE.WebGLRenderer({ antialias: false });
    this.renderer.setSize(width, height);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    host.appendChild(this.renderer.domElement);

    const spark = new SparkRenderer({ renderer: this.renderer });
    this.scene.add(spark);

    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = true;
    this.controls.dampingFactor = 0.08;
    this.controls.zoomSpeed = 2.5;
    this.controls.panSpeed = 1.2;
    this.controls.target.set(INITIAL_TARGET.x, INITIAL_TARGET.y, INITIAL_TARGET.z);

    window.addEventListener('keydown', this.onKeyDown);
    window.addEventListener('keyup', this.onKeyUp);
    window.addEventListener('blur', this.onBlur);

    this.resizeObserver = new ResizeObserver(() => this.resize());
    this.resizeObserver.observe(host);

    this.renderer.setAnimationLoop(() => this.renderFrame());
  }

  private syncCaptures(captures: ViewerCapture[]): void {
    if (!this.scene) {
      return;
    }

    const key = captures.map(capture => capture.id).join('|');
    if (key === this.loadedKey) {
      return;
    }

    this.disposeMeshes();

    this.meshes = captures.map(capture => {
      const mesh = new SplatMesh({ url: capture.url });
      mesh.visible = false;
      mesh.opacity = 0;
      this.scene!.add(mesh);
      return mesh;
    });

    this.loadedKey = key;
    this.currentIndex = -1;
    this.fade = null;
    this.showCapture(untracked(() => this.activeIndex()), false);
  }

  /** Reaplica transformaciones sin recargar archivos: base de la alineación en vivo. */
  private refreshTransforms(captures: ViewerCapture[]): void {
    captures.forEach((capture, index) => {
      const mesh = this.meshes[index];
      if (mesh) {
        this.applyTransform(mesh, capture.transform);
      }
    });
  }

  private showCapture(index: number, withCrossfade: boolean): void {
    if (this.meshes.length === 0 || index < 0 || index >= this.meshes.length) {
      return;
    }
    if (index === this.currentIndex) {
      return;
    }

    const previousIndex = this.currentIndex;
    this.currentIndex = index;

    if (!withCrossfade || previousIndex < 0) {
      this.meshes.forEach((mesh, i) => {
        mesh.visible = i === index;
        mesh.opacity = i === index ? 1 : 0;
      });
      this.fade = null;
      return;
    }

    this.meshes[index].visible = true;
    this.meshes[index].opacity = 0;
    this.fade = { fromIndex: previousIndex, toIndex: index, elapsed: 0 };
  }

  private updateFade(deltaSeconds: number): void {
    if (!this.fade) {
      return;
    }

    this.fade.elapsed += deltaSeconds;
    const progress = Math.min(this.fade.elapsed / this.fadeDurationSeconds(), 1);

    this.meshes[this.fade.fromIndex].opacity = 1 - progress;
    this.meshes[this.fade.toIndex].opacity = progress;

    if (progress >= 1) {
      this.meshes[this.fade.fromIndex].visible = false;
      this.fade = null;
    }
  }

  private renderFrame(): void {
    if (!this.renderer || !this.scene || !this.camera || !this.controls) {
      return;
    }
    const delta = this.clock.getDelta();
    this.applyKeyboardMovement(delta);
    this.updateFade(delta);
    this.controls.update();
    this.renderer.render(this.scene, this.camera);
  }

  private applyKeyboardMovement(deltaSeconds: number): void {
    if (this.pressedKeys.size === 0 || !this.camera || !this.controls) {
      return;
    }

    const forward = new THREE.Vector3();
    this.camera.getWorldDirection(forward);
    forward.y = 0;
    forward.normalize();

    const right = new THREE.Vector3()
      .crossVectors(forward, new THREE.Vector3(0, 1, 0))
      .normalize();

    const movement = new THREE.Vector3();
    if (this.pressedKeys.has('KeyW')) movement.add(forward);
    if (this.pressedKeys.has('KeyS')) movement.sub(forward);
    if (this.pressedKeys.has('KeyD')) movement.add(right);
    if (this.pressedKeys.has('KeyA')) movement.sub(right);
    if (this.pressedKeys.has('KeyE')) movement.y += 1;
    if (this.pressedKeys.has('KeyQ')) movement.y -= 1;

    if (movement.lengthSq() === 0) {
      return;
    }

    movement.normalize().multiplyScalar(MOVE_SPEED * deltaSeconds);
    this.camera.position.add(movement);
    this.controls.target.add(movement);
  }

  private applyTransform(mesh: SplatMesh, transform: CaptureTransformDto): void {
    mesh.position.set(transform.position.x, transform.position.y, transform.position.z);
    mesh.rotation.set(
      THREE.MathUtils.degToRad(transform.rotationDeg.x),
      THREE.MathUtils.degToRad(transform.rotationDeg.y),
      THREE.MathUtils.degToRad(transform.rotationDeg.z)
    );
    mesh.scale.setScalar(transform.scale);
  }

  private resize(): void {
    const host = this.container().nativeElement;
    const width = host.clientWidth || 1;
    const height = host.clientHeight || 1;

    if (!this.renderer || !this.camera) {
      return;
    }
    this.camera.aspect = width / height;
    this.camera.updateProjectionMatrix();
    this.renderer.setSize(width, height);
  }

  private disposeMeshes(): void {
    this.meshes.forEach(mesh => {
      this.scene?.remove(mesh);
      const disposable = mesh as unknown as { dispose?: () => void };
      disposable.dispose?.();
    });
    this.meshes = [];
  }

  private dispose(): void {
    window.removeEventListener('keydown', this.onKeyDown);
    window.removeEventListener('keyup', this.onKeyUp);
    window.removeEventListener('blur', this.onBlur);
    this.resizeObserver?.disconnect();

    this.renderer?.setAnimationLoop(null);
    this.controls?.dispose();
    this.disposeMeshes();

    this.renderer?.dispose();
    this.renderer?.domElement.remove();

    this.renderer = undefined;
    this.scene = undefined;
    this.camera = undefined;
    this.controls = undefined;
  }
}