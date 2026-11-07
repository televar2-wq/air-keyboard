// Maps recorded body landmarks (MediaPipe Pose, 33 points) onto a rigged character.
//
// Recording coordinates: MediaPipe "world" landmarks in meters, centered on the hips,
// x = right in the camera image, y = down, z = away from the camera.
// three.js: x = right, y = up, z = toward the viewer, so we flip y and z.
// The character faces the viewer (glTF convention: faces +Z), so it mirrors the
// performer like a puppet facing you: raise your right hand, it raises its right hand.

import * as THREE from 'three';

export const PL = {
  nose: 0, lEar: 7, rEar: 8, lSh: 11, rSh: 12, lEl: 13, rEl: 14, lWr: 15, rWr: 16,
  lIdx: 19, rIdx: 20, lHip: 23, rHip: 24, lKn: 25, rKn: 26, lAn: 27, rAn: 28,
  lHeel: 29, rHeel: 30, lToe: 31, rToe: 32,
};

// Pairs for the stick figure.
export const POSE_LINKS = [
  [11, 12], [11, 13], [13, 15], [12, 14], [14, 16], [15, 19], [16, 20],
  [11, 23], [12, 24], [23, 24], [23, 25], [25, 27], [24, 26], [26, 28],
  [27, 29], [29, 31], [27, 31], [28, 30], [30, 32], [28, 32], [0, 7], [0, 8],
];

// ------------------------------------------------------------------ bone finding

function sideOf(name) {
  const n = name.toLowerCase();
  if (/left/.test(n) || /(^|[^a-z])l([^a-z]|$)/.test(n)) return 'l';
  if (/right/.test(n) || /(^|[^a-z])r([^a-z]|$)/.test(n)) return 'r';
  return '';
}

function partOf(name) {
  let s = name.toLowerCase()
    .replace(/^.*?:/, '')                 // "mixamorig:LeftArm" -> "leftarm"
    .replace(/mixamorig/g, '')
    .replace(/left|right/g, '')
    .replace(/(^|[^a-z])[lr](?=[^a-z]|$)/g, '$1')
    .replace(/[^a-z0-9]/g, '');
  if (/thumb|index|middle|ring|pinky|finger|toe|end|twist|roll|ik|pole|target|nub/.test(s)) {
    if (/^toe(base)?$|^ball$/.test(s)) return 'toe';
    return '';
  }
  if (/forearm|lowerarm|elbow/.test(s)) return 'lowerArm';
  if (/upperarm/.test(s) || s === 'arm' || /^arm\d?$/.test(s)) return 'upperArm';
  if (/clavicle|shoulder|collar/.test(s)) return 'shoulder';
  if (/^hand$|^wrist$/.test(s)) return 'hand';
  if (/upleg|thigh|upperleg/.test(s)) return 'thigh';
  if (/calf|shin|lowerleg|knee/.test(s) || s === 'leg' || /^leg\d?$/.test(s)) return 'shin';
  if (/^foot$|ankle/.test(s)) return 'foot';
  if (/hips|pelvis/.test(s) || s === 'hip') return 'hips';
  if (/spine|chest|torso|abdomen/.test(s)) return 'spine';
  if (/neck/.test(s)) return 'neck';
  if (/^head$/.test(s)) return 'head';
  return '';
}

function depth(o) { let d = 0; while (o.parent) { d++; o = o.parent; } return d; }

/** Finds the humanoid bones of a rig by name (Mixamo, Meshy, Blender, Unreal-style names). */
export function findBones(root) {
  const b = {};
  const spines = [];
  root.traverse((o) => {
    if (!o.isBone) return;
    const part = partOf(o.name);
    if (!part) return;
    if (part === 'spine') { spines.push(o); return; }
    const side = sideOf(o.name);
    const center = ['hips', 'neck', 'head'].includes(part);
    const key = center ? part : (side ? side + part[0].toUpperCase() + part.slice(1) : null);
    if (key && !b[key]) b[key] = o;
  });
  if (!b.hips) {
    // Fall back to the top-most bone that has both thighs under it.
    root.traverse((o) => { if (!b.hips && o.isBone && b.lThigh && isAncestor(o, b.lThigh) && isAncestor(o, b.rThigh)) b.hips = o; });
  }
  spines.sort((a, c) => depth(a) - depth(c));
  if (spines.length) { b.spine = spines[0]; b.chest = spines[spines.length - 1]; }
  return b;
}

function isAncestor(a, o) { while (o) { if (o === a) return true; o = o.parent; } return false; }

export function missingBones(b) {
  const need = ['hips', 'spine', 'lUpperArm', 'lLowerArm', 'rUpperArm', 'rLowerArm', 'lThigh', 'lShin', 'rThigh', 'rShin'];
  return need.filter((k) => !b[k]);
}

// ------------------------------------------------------------------ recording prep

/** Lightly smooths each person's landmarks over time and adds root motion info. */
export function prepareRecording(rec) {
  const aspect = rec.aspect || (4 / 3);
  const tracks = new Map();
  rec.frames.forEach((f, fi) => f.people.forEach((p) => {
    if (!tracks.has(p.id)) tracks.set(p.id, []);
    tracks.get(p.id).push({ fi, p });
  }));
  const kernel = [1, 4, 6, 4, 1];
  for (const list of tracks.values()) {
    const sm = list.map((e, i) => {
      const w = new Float32Array(99), n = new Float32Array(66);
      let tot = 0;
      for (let k = -2; k <= 2; k++) {
        const j = i + k;
        if (j < 0 || j >= list.length || Math.abs(list[j].fi - e.fi) > 3) continue;
        const wt = kernel[k + 2]; tot += wt;
        const q = list[j].p;
        for (let a = 0; a < 99; a++) w[a] += q.w[a] * wt;
        for (let a = 0; a < 66; a++) n[a] += q.n[a] * wt;
      }
      for (let a = 0; a < 99; a++) w[a] /= tot;
      for (let a = 0; a < 66; a++) n[a] /= tot;
      return { w, n };
    });
    // Meters per image-height unit for this person (torso in world vs. torso in image).
    const ratios = [];
    sm.forEach(({ w, n }) => {
      const tw = Math.hypot((w[33] + w[36]) / 2 - (w[69] + w[72]) / 2, (w[34] + w[37]) / 2 - (w[70] + w[73]) / 2, (w[35] + w[38]) / 2 - (w[71] + w[74]) / 2);
      const tn = Math.hypot(((n[22] + n[24]) / 2 - (n[46] + n[48]) / 2) * aspect, (n[23] + n[25]) / 2 - (n[47] + n[49]) / 2);
      if (tn > 1e-3) ratios.push(tw / tn);
    });
    ratios.sort((a, c) => a - c);
    const k = ratios.length ? ratios[ratios.length >> 1] : 1.5;
    list.forEach((e, i) => {
      e.p.ws = sm[i].w;
      const hipX = (sm[i].n[46] + sm[i].n[48]) / 2;
      e.p.rootX = (hipX - 0.5) * aspect * k;               // meters, + = image right
      const tw = sm[i].w;
      e.p.torso = Math.hypot((tw[33] + tw[36]) / 2, (tw[34] + tw[37]) / 2, (tw[35] + tw[38]) / 2);
    });
  }
  rec.peopleIds = [...tracks.keys()].sort((a, c) => a - c);
  rec.duration = rec.frames.length ? rec.frames[rec.frames.length - 1].t - rec.frames[0].t : 0;
  return rec;
}

/** Landmark i of a person as a three.js vector (y up, z toward viewer). */
export function lm(p, i, out = new THREE.Vector3()) {
  const w = p.ws || p.w;
  return out.set(w[i * 3], -w[i * 3 + 1], -w[i * 3 + 2]);
}

export function visible(p, ...idx) {
  if (!p.v) return true;
  return idx.every((i) => (p.v[i] ?? 1) > 0.5);
}

// ------------------------------------------------------------------ retargeting

const _a = new THREE.Vector3(), _b = new THREE.Vector3(), _c = new THREE.Vector3(), _d = new THREE.Vector3();
const _q = new THREE.Quaternion(), _wq = new THREE.Quaternion(), _pq = new THREE.Quaternion(), _gq = new THREE.Quaternion();
const _m = new THREE.Matrix4(), _m2 = new THREE.Matrix4();

function basis(right, up, out) {
  const x = right.clone().normalize();
  const y = up.clone().sub(x.clone().multiplyScalar(up.dot(x))).normalize();
  const z = new THREE.Vector3().crossVectors(x, y).normalize();
  return out.makeBasis(x, y, z);
}

/** Rotate `bone` (in world space) by `delta`, keeping its parent as is. */
function rotateWorld(bone, delta) {
  bone.getWorldQuaternion(_wq);
  const target = _q.copy(delta).multiply(_wq);
  if (bone.parent) bone.parent.getWorldQuaternion(_pq); else _pq.identity();
  bone.quaternion.copy(_pq.invert().multiply(target));
  bone.updateMatrixWorld(true);
}

/** Turn `bone` so the direction from it to `child` points along `dir` (world). */
function aim(bone, child, dir) {
  if (!bone || !child || dir.lengthSq() < 1e-8) return;
  bone.getWorldPosition(_a);
  child.getWorldPosition(_b);
  const cur = _b.sub(_a);
  if (cur.lengthSq() < 1e-10) return;
  const delta = new THREE.Quaternion().setFromUnitVectors(cur.normalize(), _c.copy(dir).normalize());
  rotateWorld(bone, delta);
}

function childBone(bone, pattern) {
  if (!bone) return null;
  let found = null;
  bone.traverse((o) => { if (!found && o !== bone && o.isBone && pattern.test(o.name.toLowerCase())) found = o; });
  return found;
}

export class Retargeter {
  /** model: the character (gltf.scene); holder: its parent, moved up/down to keep feet grounded. */
  constructor(model, holder) {
    this.model = model;
    this.holder = holder || model.parent || model;
    this.b = findBones(model);
    this.missing = missingBones(this.b);
    this.rest = [];
    model.traverse((o) => { if (o.isBone) this.rest.push([o, o.quaternion.clone(), o.position.clone()]); });
    this.upper = this.b.neck || this.b.head || this.b.chest;
    this.handTip = { l: childBone(this.b.lHand, /middle|index/), r: childBone(this.b.rHand, /middle|index/) };
    this.toe = { l: childBone(this.b.lFoot, /toe|ball/), r: childBone(this.b.rFoot, /toe|ball/) };
    this.holder.position.y = 0;
    this.holder.updateMatrixWorld(true);
    this.restFootY = this.footY();
    this.torso = (this.b.hips && this.upper)
      ? this.b.hips.getWorldPosition(new THREE.Vector3()).distanceTo(this.upper.getWorldPosition(new THREE.Vector3()))
      : 0.5;
  }

  footY() {
    let y = Infinity;
    for (const k of ['lFoot', 'rFoot']) if (this.b[k]) y = Math.min(y, this.b[k].getWorldPosition(_d).y);
    for (const s of ['l', 'r']) if (this.toe[s]) y = Math.min(y, this.toe[s].getWorldPosition(_d).y);
    return y === Infinity ? 0 : y;
  }

  reset() {
    for (const [o, q, p] of this.rest) { o.quaternion.copy(q); o.position.copy(p); }
    this.holder.position.y = 0;
    this.holder.updateMatrixWorld(true);
  }

  /** Pose the character from one person's landmarks. */
  apply(p) {
    const b = this.b;
    this.reset();
    // Directions are in world space; account for how the character is turned.
    this.holder.getWorldQuaternion(_gq);
    const g = (v) => v.applyQuaternion(_gq);
    const L = (i) => lm(p, i);
    const mid = (i, j) => L(i).add(L(j)).multiplyScalar(0.5);

    // Hips: full orientation from the hip line and the torso's up direction.
    if (b.hips && b.lThigh && b.rThigh && this.upper) {
      const restRight = b.lThigh.getWorldPosition(new THREE.Vector3()).sub(b.rThigh.getWorldPosition(new THREE.Vector3()));
      const restUp = this.upper.getWorldPosition(new THREE.Vector3()).sub(b.hips.getWorldPosition(new THREE.Vector3()));
      const tRight = g(L(PL.lHip).sub(L(PL.rHip)));
      const tUp = g(mid(PL.lSh, PL.rSh).sub(mid(PL.lHip, PL.rHip)));
      basis(restRight, restUp, _m);
      basis(tRight, tUp, _m2);
      const delta = new THREE.Quaternion().setFromRotationMatrix(_m2.multiply(_m.invert()));
      rotateWorld(b.hips, delta);
    }
    // Spine leans toward the shoulders; neck toward the head.
    const shoulders = mid(PL.lSh, PL.rSh);
    if (b.spine && this.upper && b.spine !== this.upper) aim(b.spine, this.upper, g(shoulders.clone().sub(mid(PL.lHip, PL.rHip))));
    if (b.neck && b.head) aim(b.neck, b.head, g(mid(PL.lEar, PL.rEar).sub(shoulders)));

    for (const s of ['l', 'r']) {
      const S = s === 'l' ? { sh: PL.lSh, el: PL.lEl, wr: PL.lWr, ix: PL.lIdx, hip: PL.lHip, kn: PL.lKn, an: PL.lAn, toe: PL.lToe }
                          : { sh: PL.rSh, el: PL.rEl, wr: PL.rWr, ix: PL.rIdx, hip: PL.rHip, kn: PL.rKn, an: PL.rAn, toe: PL.rToe };
      if (visible(p, S.sh, S.el)) aim(b[s + 'UpperArm'], b[s + 'LowerArm'], g(L(S.el).sub(L(S.sh))));
      if (visible(p, S.el, S.wr)) aim(b[s + 'LowerArm'], b[s + 'Hand'], g(L(S.wr).sub(L(S.el))));
      if (visible(p, S.wr, S.ix)) aim(b[s + 'Hand'], this.handTip[s], g(L(S.ix).sub(L(S.wr))));
      if (visible(p, S.hip, S.kn)) aim(b[s + 'Thigh'], b[s + 'Shin'], g(L(S.kn).sub(L(S.hip))));
      if (visible(p, S.kn, S.an)) aim(b[s + 'Shin'], b[s + 'Foot'], g(L(S.an).sub(L(S.kn))));
      if (visible(p, S.an, S.toe)) aim(b[s + 'Foot'], this.toe[s], g(L(S.toe).sub(L(S.an))));
    }

    // Keep the feet on the floor.
    this.holder.updateMatrixWorld(true);
    this.holder.position.y += this.restFootY - this.footY();
    this.holder.updateMatrixWorld(true);
  }
}
