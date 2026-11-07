// Stage: plays a recorded movement with a character (or a stick figure) on it,
// lets you pick a GLB character per person, orbit the camera and save a video.

import * as THREE from 'three';
import { GLTFLoader } from 'three/addons/loaders/GLTFLoader.js';
import { OrbitControls } from 'three/addons/controls/OrbitControls.js';
import { Retargeter, prepareRecording, POSE_LINKS, lm } from './retarget.js';

const bridge = window.Android || null;
const params = new URLSearchParams(location.search);
const $ = (id) => document.getElementById(id);
const COLORS = [0x5eead4, 0xf472b6];

// ------------------------------------------------------------------ scene

const canvas = $('view');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, preserveDrawingBuffer: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
renderer.outputColorSpace = THREE.SRGBColorSpace;
renderer.shadowMap.enabled = true;
renderer.shadowMap.type = THREE.PCFSoftShadowMap;

const scene = new THREE.Scene();
scene.background = new THREE.Color(0x151a24);
scene.fog = new THREE.Fog(0x151a24, 8, 20);

const camera = new THREE.PerspectiveCamera(35, 1, 0.05, 100);
const controls = new OrbitControls(camera, canvas);
controls.enableDamping = true;
function resetView() {
  camera.position.set(0, 1.25, 4.6);
  controls.target.set(0, 0.95, 0);
  controls.update();
}
resetView();

scene.add(new THREE.HemisphereLight(0xdfe8ff, 0x2a2f3a, 1.4));
const sun = new THREE.DirectionalLight(0xffffff, 2.2);
sun.position.set(2.5, 5, 3);
sun.castShadow = true;
sun.shadow.mapSize.set(1024, 1024);
Object.assign(sun.shadow.camera, { left: -3, right: 3, top: 3, bottom: -3, near: 0.5, far: 15 });
scene.add(sun);
const fill = new THREE.DirectionalLight(0x9fb4ff, 0.6);
fill.position.set(-3, 2, -2);
scene.add(fill);

const floor = new THREE.Mesh(new THREE.CircleGeometry(6, 64), new THREE.MeshStandardMaterial({ color: 0x1f2533, roughness: 0.95 }));
floor.rotation.x = -Math.PI / 2;
floor.receiveShadow = true;
scene.add(floor);
const grid = new THREE.GridHelper(12, 24, 0x2f3747, 0x252b38);
grid.position.y = 0.002;
scene.add(grid);

function resize() {
  const w = window.innerWidth, h = window.innerHeight;
  renderer.setSize(w, h, false);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
window.addEventListener('resize', resize);
resize();

// ------------------------------------------------------------------ people

/** One performer: a stick figure and, optionally, a character. */
class Slot {
  constructor(index) {
    this.index = index;
    this.root = new THREE.Group();          // moves sideways with the performer
    this.holder = new THREE.Group();        // moved up/down to keep feet on the floor
    this.root.add(this.holder);
    scene.add(this.root);
    this.character = null;
    this.retarget = null;
    this.scale = 1;

    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(POSE_LINKS.length * 6), 3));
    this.stick = new THREE.LineSegments(geo, new THREE.LineBasicMaterial({ color: COLORS[index] }));
    this.stick.frustumCulled = false;
    this.joints = new THREE.Points(new THREE.BufferGeometry().setAttribute('position', new THREE.BufferAttribute(new Float32Array(33 * 3), 3)),
      new THREE.PointsMaterial({ color: COLORS[index], size: 0.045 }));
    this.joints.frustumCulled = false;
    this.root.add(this.stick, this.joints);
  }

  setCharacter(model) {
    if (this.character) { this.holder.remove(this.character); disposeTree(this.character); }
    this.character = model;
    this.retarget = null;
    if (!model) return;
    // Scale to about 1.7 m tall, feet on the floor, centered.
    model.updateMatrixWorld(true);
    const box = new THREE.Box3().setFromObject(model);
    const size = box.getSize(new THREE.Vector3());
    const s = size.y > 1e-4 ? 1.7 / size.y : 1;
    model.scale.multiplyScalar(s);
    model.updateMatrixWorld(true);
    const box2 = new THREE.Box3().setFromObject(model);
    const c = box2.getCenter(new THREE.Vector3());
    model.position.x -= c.x;
    model.position.z -= c.z;
    model.position.y -= box2.min.y;
    model.traverse((o) => {
      if (o.isMesh) { o.castShadow = true; o.frustumCulled = false; }
    });
    this.holder.add(model);
    this.retarget = new Retargeter(model, this.holder);
    this.scale = 1;
    return this.retarget.missing;
  }

  show(p) {
    const has = !!p;
    this.root.visible = has;
    if (!has) return;
    const useChar = !!this.retarget;
    this.stick.visible = !useChar;
    this.joints.visible = !useChar;
    if (useChar) {
      // Scale sideways movement to the character's size.
      const ratio = p.torso > 0.05 ? this.retarget.torso / p.torso : 1;
      this.root.position.set((p.rootX || 0) * ratio, 0, 0);
      this.retarget.apply(p);
    } else {
      this.root.position.set(p.rootX || 0, 0, 0);
      this.holder.position.y = 0;
      const pos = this.stick.geometry.attributes.position;
      const jp = this.joints.geometry.attributes.position;
      const v = new THREE.Vector3();
      let minY = Infinity;
      for (const i of [27, 28, 29, 30, 31, 32]) minY = Math.min(minY, lm(p, i, v).y);
      const lift = -minY + 0.02;
      POSE_LINKS.forEach(([a, b], k) => {
        lm(p, a, v); pos.setXYZ(k * 2, v.x, v.y + lift, v.z);
        lm(p, b, v); pos.setXYZ(k * 2 + 1, v.x, v.y + lift, v.z);
      });
      for (let i = 0; i < 33; i++) { lm(p, i, v); jp.setXYZ(i, v.x, v.y + lift, v.z); }
      pos.needsUpdate = true;
      jp.needsUpdate = true;
    }
  }
}

function disposeTree(o) {
  o.traverse((n) => {
    if (n.geometry) n.geometry.dispose();
    if (n.material) [].concat(n.material).forEach((m) => { for (const k in m) if (m[k] && m[k].isTexture) m[k].dispose(); m.dispose(); });
  });
}

const slots = [new Slot(0), new Slot(1)];

// ------------------------------------------------------------------ recording + playback

let rec = null;
let playing = true;
let loop = true;
let t = 0;               // ms into the recording
let last = performance.now();

function frameAt(ms) {
  const f = rec.frames;
  const t0 = f[0].t;
  let lo = 0, hi = f.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (f[mid].t - t0 <= ms) lo = mid; else hi = mid - 1;
  }
  return f[lo];
}

function fmt(ms) {
  const s = Math.max(0, Math.round(ms / 1000));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

function setStatus(html) { $('status').innerHTML = html; }

let toastTimer = 0;
function toast(msg, ms = 2600) {
  const el = $('toast');
  el.textContent = msg;
  el.style.display = 'block';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.style.display = 'none'; }, ms);
}

async function loadRecording(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error('Could not open the recording');
  rec = prepareRecording(await res.json());
  if (!rec.frames.length) throw new Error('This recording is empty');
  const people = rec.peopleIds.length;
  $('bChar1').hidden = people < 2;
  // Map recorded person ids to slots in order of first appearance.
  rec.slotOf = new Map(rec.peopleIds.slice(0, 2).map((id, i) => [id, i]));
  setStatus(`<b>${rec.name || 'Recording'}</b> · ${fmt(rec.duration)} · ${people} ${people === 1 ? 'person' : 'people'}`);
}

function updatePose() {
  if (!rec) return;
  const f = frameAt(t);
  const shown = [null, null];
  for (const p of f.people) {
    const s = rec.slotOf.get(p.id);
    if (s !== undefined) shown[s] = p;
  }
  slots.forEach((slot, i) => slot.show(shown[i]));
  $('scrub').value = rec.duration ? Math.round((t / rec.duration) * 1000) : 0;
  $('time').textContent = `${fmt(t)} / ${fmt(rec.duration)}`;
}

function tick(now) {
  const dt = Math.min(now - last, 100);
  last = now;
  if (rec && playing) {
    t += dt;
    if (t > rec.duration) {
      if (loop) t = 0; else { t = rec.duration; playing = false; onPlaybackEnd(); }
    }
  }
  updatePose();
  controls.update();
  renderer.render(scene, camera);
  requestAnimationFrame(tick);
}

let onPlaybackEnd = () => {};

function setPlaying(p) {
  playing = p;
  $('bPlay').innerHTML = p ? '<span class="i">⏸</span><span>Pause</span>' : '<span class="i">▶</span><span>Play</span>';
}

$('scrub').addEventListener('input', (e) => {
  if (!rec) return;
  t = (e.target.value / 1000) * rec.duration;
});
$('bPlay').onclick = () => setPlaying(!playing);
$('bView').onclick = resetView;
$('bBack').onclick = () => { if (bridge) bridge.close(); else history.back(); };

// ------------------------------------------------------------------ characters

const loader = new GLTFLoader();

async function loadCharacter(slot, url, name) {
  toast(`Loading ${name || 'character'}…`, 10000);
  try {
    const gltf = await loader.loadAsync(url);
    const model = gltf.scene;
    let hasSkin = false;
    model.traverse((o) => { if (o.isSkinnedMesh) hasSkin = true; });
    if (!hasSkin) {
      toast('That model has no skeleton (rig). In Meshy, use the rigged/animated GLB export.', 6000);
      disposeTree(model);
      return;
    }
    const missing = slots[slot].setCharacter(model);
    $('n' + slot).textContent = name || 'character';
    if (missing && missing.length) toast(`Loaded, but couldn't find: ${missing.join(', ')} — those parts won't move.`, 7000);
    else toast(`${name || 'Character'} loaded`);
  } catch (e) {
    console.error(e);
    toast(`Couldn't load that file: ${e.message || e}`, 6000);
  }
}

let pickingSlot = 0;
function pick(slot) {
  pickingSlot = slot;
  if (bridge) bridge.pickCharacter(slot);
  else $('file').click();
}
$('bChar0').onclick = () => pick(0);
$('bChar1').onclick = () => pick(1);
$('file').addEventListener('change', (e) => {
  const f = e.target.files[0];
  if (!f) return;
  loadCharacter(pickingSlot, URL.createObjectURL(f), f.name);
  e.target.value = '';
});

// Called by the app after you choose a file.
window.onCharacterPicked = (slot, url, name) => loadCharacter(slot, url, name);
window.onCharacterCleared = (slot) => { slots[slot].setCharacter(null); $('n' + slot).textContent = 'stick figure'; };

// ------------------------------------------------------------------ video

function pickMime() {
  const options = ['video/mp4;codecs=avc1', 'video/mp4', 'video/webm;codecs=vp9', 'video/webm;codecs=vp8', 'video/webm'];
  return options.find((m) => window.MediaRecorder && MediaRecorder.isTypeSupported(m)) || '';
}

async function saveVideo() {
  if (!rec) return;
  const mime = pickMime();
  if (!mime) { toast('Video recording isn\'t supported on this device.'); return; }
  const stream = canvas.captureStream(30);
  const recorder = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: 8_000_000 });
  const chunks = [];
  recorder.ondataavailable = (e) => { if (e.data && e.data.size) chunks.push(e.data); };
  const done = new Promise((resolve) => { recorder.onstop = resolve; });

  document.body.classList.add('recording');
  $('recdot').style.display = 'block';
  const prevLoop = loop;
  loop = false;
  t = 0;
  setPlaying(true);
  recorder.start(500);
  await new Promise((resolve) => { onPlaybackEnd = resolve; });
  onPlaybackEnd = () => {};
  await new Promise((r) => setTimeout(r, 300));
  recorder.stop();
  await done;
  stream.getTracks().forEach((tr) => tr.stop());
  loop = prevLoop;
  document.body.classList.remove('recording');
  $('recdot').style.display = 'none';

  const blob = new Blob(chunks, { type: mime.split(';')[0] });
  const ext = mime.startsWith('video/mp4') ? 'mp4' : 'webm';
  if (bridge) {
    toast('Saving video…', 20000);
    bridge.videoBegin(ext);
    const step = 512 * 1024;
    for (let off = 0; off < blob.size; off += step) {
      const b64 = await new Promise((resolve) => {
        const r = new FileReader();
        r.onload = () => resolve(String(r.result).split(',')[1] || '');
        r.readAsDataURL(blob.slice(off, off + step));
      });
      bridge.videoChunk(b64);
    }
    const saved = bridge.videoEnd();
    toast(saved ? `Saved to Movies/Motion Puppet: ${saved}` : 'Couldn\'t save the video', 5000);
  } else {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `motion-puppet.${ext}`;
    a.click();
  }
  t = 0;
  setPlaying(true);
}
$('bVideo').onclick = () => saveVideo();

// ------------------------------------------------------------------ start

(async () => {
  try {
    const recUrl = params.get('rec');
    if (!recUrl) throw new Error('No recording chosen');
    await loadRecording(recUrl);
    for (const i of [0, 1]) {
      const fromUrl = params.get('char' + i);
      const saved = bridge ? (bridge.savedCharacter(i) || '') : '';
      if (fromUrl) loadCharacter(i, fromUrl, fromUrl.split('/').pop());
      else if (saved) { const [url, name] = saved.split('|'); loadCharacter(i, url, name); }
    }
  } catch (e) {
    setStatus(`<b>Couldn't open</b> · ${e.message || e}`);
  }
  requestAnimationFrame(tick);
})();

// For automated checks.
window.__stage = { setTime: (ms) => { t = ms; setPlaying(false); }, slots, get rec() { return rec; } };
